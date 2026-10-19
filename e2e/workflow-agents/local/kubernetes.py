#!/usr/bin/env python3
"""Run the synthetic acceptance stack in its own, guarded local-kind namespace."""
import argparse
import base64
import json
import os
from pathlib import Path
import signal
import shlex
import socket
import subprocess
import sys
import time

HERE = Path(__file__).resolve().parent
NAMESPACE = "ec-workflow-agent-test"
EXPECTED_NODE = "ec-demo1-local-control-plane"
APPS = {"ia-control-plane": "ia-control-plane.jar", "ia-workflow-worker": "ia-agent.jar",
        "orchestrator": "orchestrator.jar"}
INFRA = ("engine-db", "catalogue-db", "redpanda", "keycloak", "fixtures")
FORWARDS = {"keycloak": (18080, 8080), "fixtures": (18090, 8080),
            "ia-control-plane": (18110, 8110), "ia-workflow-worker": (18095, 8095),
            "orchestrator": (18105, 8105)}


def private(path, value):
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.write_text(json.dumps(value, indent=2) + "\n")
    path.chmod(0o600)


def resource(kind, name, **body):
    return {"apiVersion": "apps/v1" if kind == "Deployment" else "v1", "kind": kind,
            "metadata": {"name": name, "namespace": NAMESPACE,
                         "labels": {"app.kubernetes.io/part-of": NAMESPACE}}, **body}


def pvc(name, size="1Gi"):
    return resource("PersistentVolumeClaim", name, spec={"accessModes": ["ReadWriteOnce"],
                    "resources": {"requests": {"storage": size}}})


def config(name, files, binary=False):
    key = "binaryData" if binary else "data"
    return resource("ConfigMap", name, **{key: {
        target: base64.b64encode(path.read_bytes()).decode() if binary else path.read_text()
        for target, path in files.items()}})


def volume(name, source, mount, **kwargs):
    return ({"name": name, **source}, {"name": name, "mountPath": mount, **kwargs})


def manifests(state):
    result = subprocess.run(["docker", "compose", "--env-file", str(HERE / ".env"),
                             "-f", str(HERE / "compose.yaml"), "config", "--format", "json"],
                            check=True, capture_output=True, text=True)
    services = json.loads(result.stdout)["services"]
    common = [{"apiVersion": "v1", "kind": "Namespace", "metadata": {"name": NAMESPACE,
               "labels": {"app.kubernetes.io/part-of": NAMESPACE}}}]
    common += [pvc(name) for name in ("engine-data", "catalogue-data", "kafka-data",
                                     "keycloak-data", "fixture-data", "artifacts")]
    common += [config("engine-init", {"10-runtime.sh": HERE / "db-init.sh"}),
               config("catalogue-init", {"10-handbook.sql": HERE.parent / "seed.sql"}),
               config("fixture-code", {"fixture_server.py": HERE.parent / "fixture_server.py"}),
               config("fixture-catalogue", {"catalogue.tar.gz": state / "catalogue.tar.gz"}, True),
               config("workflow-definitions", {p.name: p for p in (state / "definitions").iterdir()
                                                if p.is_file()})]
    common.append(resource("Secret", "keycloak-realm", type="Opaque", stringData={
        "workflow-agents-realm.json": (state / "keycloak-import/workflow-agents-realm.json").read_text()}))
    groups = {}
    for name, service in services.items():
        common.append(resource("Secret", name + "-env", type="Opaque", stringData={
            key: str(value) for key, value in service.get("environment", {}).items()}))
        port = FORWARDS.get(name, (None, {"engine-db": 5432, "catalogue-db": 5432,
                                        "redpanda": 9092}.get(name)))[1]
        svc = resource("Service", name, spec={"selector": {"app": name}, "ports": [
                       {"name": "main", "port": port, "targetPort": port}]})
        vols, mounts = [], []
        def add(v):
            a, b = v
            vols.append(a)
            mounts.append(b)
        if name in APPS:
            add(volume("artifacts", {"persistentVolumeClaim": {"claimName": "artifacts"}},
                       "/artifacts", readOnly=True))
        for mount in service.get("volumes", []):
            target = mount["target"]
            if mount["type"] == "volume":
                add(volume(mount["source"], {"persistentVolumeClaim": {"claimName": mount["source"]}}, target))
            elif target == "/docker-entrypoint-initdb.d/10-runtime.sh":
                add(volume("engine-init", {"configMap": {"name": "engine-init", "defaultMode": 0o755}}, target,
                           subPath="10-runtime.sh", readOnly=True))
            elif target == "/docker-entrypoint-initdb.d/10-synthetic-handbook.sql":
                add(volume("catalogue-init", {"configMap": {"name": "catalogue-init"}}, target,
                           subPath="10-handbook.sql", readOnly=True))
            elif target == "/opt/keycloak/data/import":
                add(volume("realm", {"secret": {"secretName": "keycloak-realm"}}, target, readOnly=True))
            elif target == "/fixture/fixture_server.py":
                add(volume("fixture-code", {"configMap": {"name": "fixture-code"}}, target,
                           subPath="fixture_server.py", readOnly=True))
            elif target == "/fixture/catalogue.tar.gz":
                add(volume("fixture-catalogue", {"configMap": {"name": "fixture-catalogue"}}, target,
                           subPath="catalogue.tar.gz", readOnly=True))
            elif target == "/definitions":
                add(volume("definitions", {"configMap": {"name": "workflow-definitions"}}, target, readOnly=True))
            else:
                raise SystemExit("Unmapped Compose volume for " + name + ": " + target)
        container = {"name": name, "image": service["image"], "ports": [{"containerPort": port}],
                     "envFrom": [{"secretRef": {"name": name + "-env"}}], "volumeMounts": mounts,
                     "resources": {"requests": {"cpu": "100m", "memory": "128Mi"},
                                   "limits": {"memory": str(service.get("mem_limit", 1073741824))}}}
        if name in APPS:
            container["image"] = "eclipse-temurin:21-jre-alpine"
            container["command"] = ["java", "-Xms128m", "-Xmx768m" if name != "orchestrator" else "-Xmx1024m",
                                    "-jar", "/artifacts/" + APPS[name]]
        elif service.get("command"):
            container["args"] = service["command"]
        if name in ("engine-db", "catalogue-db", "redpanda", "fixtures"):
            test = service["healthcheck"]["test"]
            command = ["sh", "-ec", test[1]] if test[0] == "CMD-SHELL" else test[1:]
            probe = {"exec": {"command": command}, "periodSeconds": 3, "timeoutSeconds": 5}
        else:
            path = "/realms/workflow-agents/protocol/openid-connect/certs" if name == "keycloak" else "/actuator/health"
            probe = {"httpGet": {"path": path, "port": port}, "periodSeconds": 3, "timeoutSeconds": 5}
        container["readinessProbe"] = {**probe, "failureThreshold": 5}
        container["startupProbe"] = {**probe, "failureThreshold": 120}
        pod = {"containers": [container], "volumes": vols, "terminationGracePeriodSeconds": 30,
               "securityContext": {"fsGroup": 10001, "fsGroupChangePolicy": "OnRootMismatch"}}
        deployment = resource("Deployment", name, spec={"replicas": 1,
            "strategy": {"type": "Recreate"}, "selector": {"matchLabels": {"app": name}},
            "template": {"metadata": {"labels": {"app": name}}, "spec": pod}})
        groups[name] = [svc, deployment]
    # Compose aliases become regular ClusterIP Services selecting the same fixtures pod.
    for alias in ("workflow-model-mock", "workflow-embedding-mock", "workflow-mcp-mock"):
        groups["fixtures"].append(resource("Service", alias, spec={"selector": {"app": "fixtures"},
            "ports": [{"name": "http", "port": 8080, "targetPort": 8080}]}))
    uploader = resource("Pod", "artifact-upload", spec={"restartPolicy": "Never", "securityContext": {"fsGroup": 10001}, "containers": [
        {"name": "upload", "image": "python:3.12-slim", "command": ["python", "-c", "import time; time.sleep(86400)"],
         "volumeMounts": [{"name": "artifacts", "mountPath": "/artifacts"}]}],
        "volumes": [{"name": "artifacts", "persistentVolumeClaim": {"claimName": "artifacts"}}]})
    folder = state / "kubernetes"
    private(folder / "base.json", {"apiVersion": "v1", "kind": "List", "items": common})
    private(folder / "uploader.json", uploader)
    for name, resources in groups.items():
        private(folder / (name + ".json"), {"apiVersion": "v1", "kind": "List", "items": resources})
    return folder


class Cluster:
    def __init__(self, args):
        self.args = args
        self.base = ["kubectl", "--kubeconfig", str(args.kubeconfig)]
        self.state = args.state_dir.resolve()

    def k(self, *args, **kwargs):
        return subprocess.run(self.base + ["-n", NAMESPACE, *args], check=True, **kwargs)

    def guard(self):
        if not self.args.kubeconfig.is_file():
            raise SystemExit("Local kubeconfig is not readable: " + str(self.args.kubeconfig))
        result = self.k("get", "nodes", "-o", "json", capture_output=True, text=True)
        names = [node["metadata"]["name"] for node in json.loads(result.stdout)["items"]]
        if names != [EXPECTED_NODE]:
            raise SystemExit("Refusing a cluster other than the dedicated single-node local kind cluster.")

    def apply(self, file):
        self.k("apply", "-f", str(file))

    def ready(self, name):
        self.k("rollout", "status", "deployment/" + name, "--timeout=600s")

    def stop_forwards(self, service=None):
        file = self.state / "kubernetes/forwards.json"
        if not file.exists():
            return
        retained = []
        stopped = []
        stopped_ports = []
        for entry in json.loads(file.read_text()):
            if service and entry["service"] != service:
                retained.append(entry)
                continue
            try:
                cmd = Path(f"/proc/{entry['pid']}/cmdline").read_bytes().decode().replace("\0", " ")
                if "kubectl" in cmd and "port-forward" in cmd and NAMESPACE in cmd:
                    os.kill(entry["pid"], signal.SIGTERM)
                    stopped.append(entry["pid"])
                    stopped_ports.append(FORWARDS[entry["service"]][0])
            except (FileNotFoundError, ProcessLookupError):
                pass
        deadline = time.monotonic() + 5
        while stopped and time.monotonic() < deadline:
            pending = []
            for pid in stopped:
                try:
                    if Path(f"/proc/{pid}/cmdline").read_bytes():
                        pending.append(pid)
                except FileNotFoundError:
                    pass
            stopped = pending
            if stopped:
                time.sleep(0.05)
        for port in stopped_ports:
            while True:
                with socket.socket() as sock:
                    try:
                        sock.bind(("127.0.0.1", port))
                        break
                    except OSError:
                        if time.monotonic() >= deadline:
                            raise SystemExit(f"Recorded forward did not release local port {port} within 5 seconds.")
                time.sleep(0.05)
        if retained:
            private(file, retained)
        else:
            file.unlink()

    def forwards(self, only=None):
        self.stop_forwards(only)
        selected = {name: ports for name, ports in FORWARDS.items() if not only or name == only}
        for local, _ in selected.values():
            with socket.socket() as sock:
                try:
                    sock.bind(("127.0.0.1", local))
                except OSError:
                    raise SystemExit(f"Local port {local} is occupied; no existing process will be stopped.")
        saved = self.state / "kubernetes/forwards.json"
        entries = json.loads(saved.read_text()) if saved.exists() else []
        for service, (local, target) in selected.items():
            log = self.state / "kubernetes" / (service + "-forward.log")
            with log.open("ab") as output:
                process = subprocess.Popen(self.base + ["-n", NAMESPACE, "port-forward", "--address", "127.0.0.1",
                    "service/" + service, f"{local}:{target}"], stdout=output, stderr=subprocess.STDOUT,
                    start_new_session=True)
            entries.append({"service": service, "pid": process.pid})
        private(self.state / "kubernetes/forwards.json", entries)
        # Early services are ready; later services can acquire their pods during startup.
        time.sleep(2)

    def up(self):
        folder = manifests(self.state)
        for jar in APPS.values():
            if not (self.state / "jars" / jar).is_file():
                raise SystemExit("Build JARs first with run.sh build --jars-only; missing " + jar)
        self.stop_forwards()
        for local, _ in FORWARDS.values():
            with socket.socket() as sock:
                try:
                    sock.bind(("127.0.0.1", local))
                except OSError:
                    raise SystemExit(f"Local port {local} is occupied. No cluster resources were changed.")
        self.apply(folder / "base.json")
        self.k("delete", "pod", "artifact-upload", "--ignore-not-found=true", "--wait=true")
        self.apply(folder / "uploader.json")
        self.k("wait", "--for=condition=Ready", "pod/artifact-upload", "--timeout=300s")
        for jar in APPS.values():
            self.k("cp", str(self.state / "jars" / jar), "artifact-upload:/artifacts/" + jar)
        self.k("delete", "pod", "artifact-upload", "--wait=true")
        for name in INFRA:
            self.apply(folder / (name + ".json"))
        for name in INFRA:
            self.ready(name)
        # Create Services for every app so all localhost-only forwards can be started.
        for name in APPS:
            data = json.loads((folder / (name + ".json")).read_text())
            self.k("apply", "-f", "-", input=json.dumps(data["items"][0]), text=True)
        self.apply(folder / "ia-control-plane.json")
        self.ready("ia-control-plane")
        self.forwards()
        subprocess.run([sys.executable, str(HERE / "acceptance.py"), "--wait-catalogue",
                        "--state-dir", str(self.state)], check=True)
        for name in ("ia-workflow-worker", "orchestrator"):
            self.apply(folder / (name + ".json"))
        for name in ("ia-workflow-worker", "orchestrator"):
            self.ready(name)
        # The app forwards created before pods existed may have exited. Restart all.
        self.forwards()
        print("Isolated local stack is ready. Run run-k8s.sh check to execute acceptance tests.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("render", "up", "connect", "check", "status", "logs", "down", "restart-worker"))
    parser.add_argument("service", nargs="?", choices=(*INFRA, *APPS))
    parser.add_argument("--state-dir", type=Path, default=HERE / ".state")
    parser.add_argument("--runtime-port", type=int, default=28095,
                        help="Loopback port for the durable runtime, separate from existing demos")
    parser.add_argument("--kubeconfig", type=Path,
                        default=Path(os.environ.get("KUBECONFIG", "~/.kube/ec-demo1-local")).expanduser())
    args = parser.parse_args()
    FORWARDS["ia-workflow-worker"] = (args.runtime_port, 8095)
    os.umask(0o077)
    if args.operation == "render":
        print("Private manifests generated in " + str(manifests(args.state_dir.resolve())))
        return
    cluster = Cluster(args)
    cluster.guard()
    if args.operation == "up":
        cluster.up()
    elif args.operation == "connect":
        cluster.forwards()
        print("Local-only port forwards are connected to the existing test stack.")
    elif args.operation == "check":
        cluster.forwards()
        restart = shlex.join([str(HERE / "run-k8s.sh"), "restart-worker", "--state-dir",
                              str(cluster.state), "--kubeconfig", str(args.kubeconfig),
                              "--runtime-port", str(args.runtime_port)])
        subprocess.run([sys.executable, str(HERE / "acceptance.py"), "--state-dir",
                        str(cluster.state), "--runtime-url", f"http://127.0.0.1:{args.runtime_port}",
                        "--restart-command", restart], check=True)
    elif args.operation == "restart-worker":
        cluster.stop_forwards("ia-workflow-worker")
        cluster.k("rollout", "restart", "deployment/ia-workflow-worker")
        cluster.ready("ia-workflow-worker")
        cluster.forwards("ia-workflow-worker")
    elif args.operation == "status":
        cluster.k("get", "deployments,pods,services,pvc")
    elif args.operation == "logs":
        if not args.service:
            parser.error("logs requires a service name")
        cluster.k("logs", "deployment/" + args.service, "--tail=150", "--all-containers=true")
    elif args.operation == "down":
        cluster.stop_forwards()
        cluster.k("scale", "deployment", "--all", "--replicas=0")
        cluster.k("delete", "pod", "artifact-upload", "--ignore-not-found=true")
        print("Local workloads stopped; namespace, Secrets and persistent volumes retained.")


if __name__ == "__main__":
    main()

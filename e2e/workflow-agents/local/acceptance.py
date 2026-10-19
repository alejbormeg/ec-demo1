#!/usr/bin/env python3
"""Exercise the local synthetic stack through its real authenticated HTTP APIs."""
import argparse
import base64
from datetime import datetime, timezone
import json
from pathlib import Path
import shlex
import subprocess
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen
import uuid


def read_env(path):
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, value = line.split("=", 1)
        values[key] = shlex.split(value)[0] if value.strip() else ""
    return values


def http(method, url, body=None, headers=None, timeout=12):
    headers = dict(headers or {})
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    request = Request(url, body, headers, method=method)
    try:
        response = urlopen(request, timeout=timeout)
    except HTTPError as error:
        response = error
    with response:
        data = response.read()
        try:
            content = json.loads(data) if data else None
        except json.JSONDecodeError:
            content = data.decode(errors="replace")[:1000]
        return response.status, content


def wait_until(description, probe, timeout=180, interval=1):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = probe()
            if value:
                return value
        except (URLError, TimeoutError, ConnectionError, OSError) as error:
            last = type(error).__name__
        time.sleep(interval)
    raise AssertionError(f"Timed out waiting for {description}; last network error: {last}")


class Acceptance:
    def __init__(self, args):
        self.args = args
        self.state = args.state_dir.resolve()
        env_file = self.state / ".env"
        self.config = read_env(env_file if env_file.exists() else self.state.parent / ".env")
        self.tokens = {}

    def token(self, operator=False):
        key = "operator" if operator else "service"
        if key in self.tokens and self.tokens[key][1] > time.monotonic():
            return self.tokens[key][0]
        form = ({"grant_type": "password", "client_id": self.config["KEYCLOAK_OPERATOR_CLIENT_ID"],
                 "username": self.config["KEYCLOAK_OPERATOR_USERNAME"],
                 "password": self.config["KEYCLOAK_OPERATOR_PASSWORD"]} if operator else
                {"grant_type": "client_credentials", "client_id": "workflow-agent-executor",
                 "client_secret": self.config["WORKFLOW_AGENT_CLIENT_SECRET"]})
        status, body = http("POST", self.args.identity_url + "/realms/workflow-agents/protocol/openid-connect/token",
                            urlencode(form).encode(), {"Content-Type": "application/x-www-form-urlencoded"})
        if status != 200:
            return None
        self.tokens[key] = (body["access_token"], time.monotonic() + max(1, body["expires_in"] - 20))
        return body["access_token"]

    def authorized(self, method, url, body=None, operator=True):
        token = self.token(operator)
        if not token:
            raise ConnectionError("Local identity is not ready")
        return http(method, url, body, {"Authorization": "Bearer " + token})

    def catalogue(self):
        token = self.token()
        if not token:
            return None
        status, body = http("GET", self.args.catalogue_url + "/internal/workflow-agents",
                            headers={"Authorization": "Bearer " + token})
        if status == 200 and {"workflow-case-assessor", "workflow-evidence-agent"} <= {a["id"] for a in body}:
            return body
        return None

    def wait_catalogue(self):
        agents = wait_until("GitOps publication of the two synthetic agents", self.catalogue)
        print("Published synthetic workflow agents: " + ", ".join(a["id"] for a in agents), flush=True)

    def invocation(self, location):
        status, body = self.engine_http("GET", self.args.engine_url + location, headers={"Prefer": "wait=1"})
        assert status in (200, 202), f"Invocation failed: HTTP {status}, {body}"
        return body

    def engine_http(self, method, url, body=None, headers=None):
        headers = dict(headers or {})
        credentials = "local-operator:" + self.config["ENGINE_HTTP_PASSWORD"]
        headers["Authorization"] = "Basic " + base64.b64encode(credentials.encode()).decode()
        return http(method, url, body, headers)

    def start(self):
        key = "synthetic-" + uuid.uuid4().hex
        body = {"businessKey": key, "variables": {"caseId": "SYN-001", "caseData": "synthetic evidence present",
                "notForAgent": "SYNTHETIC_CONTEXT_NOT_SELECTED"}}
        url = self.args.engine_url + "/workflow/api/definitions/assess-synthetic-case/invocations"
        headers = {"Idempotency-Key": key, "Prefer": "wait=1", "Accept": "application/json"}
        def invoke():
            status, result = self.engine_http("POST", url, body, headers)
            if status == 404:
                return None  # Native directory import may still be finishing.
            assert status in (200, 202), f"Cannot start synthetic workflow: HTTP {status}, {result}"
            return result
        result = wait_until("the imported synthetic workflow", invoke, timeout=90)
        return result, (url, body, headers)

    def completed(self, location):
        def check():
            body = self.invocation(location)
            if body.get("processStatus") in ("FAILED", "CANCELLED", "ERROR"):
                raise AssertionError(f"Synthetic workflow failed: {body}")
            return body if body.get("outcome") == "REPLIED" and body.get("processStatus") == "COMPLETED" else None
        result = wait_until("the completed workflow reply", check, timeout=300)
        assert result["compensation"] == "NONE", result
        assert result["reply"]["decision"] == "accepted", result
        return result

    def summaries(self):
        status, rows = self.authorized("GET", self.args.runtime_url + "/internal/workflow-agent-executions")
        assert status == 200, f"Cannot inspect durable runtime: HTTP {status}"
        return rows

    def detail(self, invocation_id):
        status, body = self.authorized("GET", self.args.runtime_url + "/internal/workflow-agent-executions/" + invocation_id)
        assert status == 200, f"Cannot inspect invocation {invocation_id}: HTTP {status}"
        return body

    def root(self, process_id):
        for row in self.summaries():
            if row["id"] == row["rootId"]:
                detail = self.detail(row["id"])
                if detail["execution"]["request"]["processId"] == process_id:
                    return detail
        return None

    def effects(self):
        status, body = http("GET", self.args.fixture_url + "/fixture/stats")
        assert status == 200
        return body["writes"]

    def mode(self, uncertain):
        status, _ = http("POST", self.args.fixture_url + "/fixture/mode", {"uncertainWrite": uncertain})
        assert status == 200

    def evidence(self, result):
        detail = wait_until("the durable root execution", lambda: self.root(result["processId"]))
        root = detail["execution"]
        chain = detail["chain"]
        assert len(chain) == 2, f"Expected root and one genuine A2A child, got {len(chain)}"
        assert all(e["status"] == "COMPLETED" for e in chain)
        assert all(t["status"] == "CONFIRMED" for e in chain for t in e["tools"])
        assert {"mcp", "rag", "a2a"} <= {t["kind"] for t in root["tools"]}
        assert root["snapshotDigest"] and root["catalogueRevision"]
        assert set(root["request"]["input"]) == {"caseId", "case"}, "Agent received unselected workflow variables"
        assert "SYNTHETIC_CONTEXT_NOT_SELECTED" not in json.dumps(root["messages"])
        for entry in chain:
            expected_rounds = 4 if entry["request"]["parentInvocationId"] is None else 2
            assert len(entry["rounds"]) == expected_rounds
            assert sum(r["inputTokens"] for r in entry["rounds"]) == expected_rounds * 12
            assert sum(r["outputTokens"] for r in entry["rounds"]) == expected_rounds * 8
            for tool in entry["tools"]:
                tool_result = json.loads(tool["result"])
                if tool["kind"] == "rag":
                    assert tool_result["ragId"] == "workflow-handbook" and tool_result["count"] > 0
                    assert any(p["source"] == "synthetic-handbook" and
                               "accept a case only when its evidence is present and verified" in p["text"]
                               for p in tool_result["passages"]), "RAG did not return the seeded rule"
                elif tool["kind"] == "a2a":
                    assert tool_result["verified"] is True and tool_result["explanation"], "Peer did not verify evidence"
                elif tool["kind"] == "mcp" and tool["name"].endswith("_read_case"):
                    case = json.loads(tool_result["content"][0]["text"])
                    assert case["caseId"] == "SYN-001" and case["evidence"] == "present" and case["synthetic"] is True
                elif tool["kind"] == "mcp":
                    assert tool["reviewer"] and tool["evidence"], "Uncertain write lacks operator reconciliation"
                    assert tool_result["caseId"] == "SYN-001" and tool_result["written"] is True
        return {"processId": result["processId"], "invocationId": result["invocationId"],
                "rootId": root["request"]["invocationId"], "snapshotDigest": root["snapshotDigest"],
                "catalogueRevision": root["catalogueRevision"], "validatedToolContents": True, "explicitInputOnly": True,
                "agents": [{"id": e["request"]["agentRef"], "status": e["status"], "rounds": len(e["rounds"]),
                            "toolKinds": [t["kind"] for t in e["tools"]]} for e in chain]}

    def run(self):
        self.wait_catalogue()
        wait_until("the operator identity", lambda: self.token(True))
        self.mode(False)
        baseline = self.effects()
        print("Running native AGENT → MCP → RAG → HTTP A2A → JSON reply", flush=True)
        started, request = self.start()
        completed = self.completed(started["location"])
        happy = self.evidence(completed)
        status, replay = self.engine_http("POST", request[0], request[1], request[2])
        assert status == 200 and replay["processId"] == completed["processId"], "Idempotent replay created a different process"
        assert self.effects() == baseline, "Read-only happy path unexpectedly wrote an effect"

        self.mode(True)
        print("Running uncertain-write review and worker-restart recovery", flush=True)
        started, _ = self.start()
        def paused():
            detail = self.root(started["processId"])
            return detail if detail and detail["execution"]["status"] == "REVIEW_REQUIRED" else None
        held = wait_until("review of the committed but unacknowledged root write", paused, timeout=120)
        held_root = held["execution"]
        assert self.effects() == baseline + 1
        view = wait_until("the engine review hold", lambda: (v if (v := self.invocation(started["location"])).get("processStatus") == "PAUSED" else None))
        assert "reply" not in view, "An uncertain effect exposed business output"
        pending = next(t for t in held_root["tools"] if t["status"] == "REVIEW_REQUIRED")
        subprocess.run(shlex.split(self.args.restart_command), cwd=Path(__file__).parent, check=True, timeout=240)
        recovered = wait_until("the same review after worker restart", paused, timeout=120)["execution"]
        assert recovered["snapshotDigest"] == held_root["snapshotDigest"]
        assert len(recovered["rounds"]) == len(held_root["rounds"])
        assert self.effects() == baseline + 1, "Worker restart repeated the uncertain write"
        assert next(t for t in recovered["tools"] if t["id"] == pending["id"])["digest"] == pending["digest"]

        reviewed = set()
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            for row in self.summaries():
                if row["rootId"] != happy.get("rootId") and row["rootId"] == recovered["request"]["invocationId"]:
                    for tool in row.get("unknownCalls", []):
                        review_key = (row["id"], tool["toolCallId"])
                        if review_key in reviewed:
                            continue
                        payload = {"requestDigest": row["requestDigest"], "toolCallId": tool["toolCallId"],
                                   "toolDigest": tool["toolDigest"],
                                   "confirmedResult": json.dumps({"caseId": "SYN-001", "synthetic": True, "written": True}),
                                   "evidence": f"Synthetic SQLite ledger confirms committed write; count={self.effects()}"}
                        status, reply = self.authorized("POST", self.args.runtime_url + "/internal/workflow-agent-executions/" + row["id"] + "/review", payload)
                        assert status == 200, f"Reconciliation rejected: HTTP {status}, {reply}"
                        reviewed.add(review_key)
            result = self.invocation(started["location"])
            if result.get("processStatus") == "COMPLETED":
                break
            assert result.get("processStatus") not in ("ERROR", "FAILED", "CANCELLED"), result
            time.sleep(1)
        else:
            raise AssertionError("Review did not complete the synthetic process")
        completed = self.completed(started["location"])
        assert len(reviewed) == 2, f"Expected root and child write reconciliations, got {len(reviewed)}"
        assert self.effects() == baseline + 2, "An effect was lost or replayed"
        uncertain = self.evidence(completed)
        uncertain.update({"reviewedCalls": len(reviewed), "writesAfterRestart": baseline + 1, "finalWrites": self.effects()})
        self.mode(False)
        report = {"checkedAt": datetime.now(timezone.utc).isoformat(), "happy": happy, "uncertainWrite": uncertain,
                  "paidProviders": False, "realIntegrations": False}
        output = self.state / "acceptance-evidence.json"
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"PASS: happy path, idempotent invocation, review, restart without replay, and child reconciliation. Evidence: {output}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=Path(__file__).with_name(".state"))
    parser.add_argument("--wait-catalogue", action="store_true")
    parser.add_argument("--engine-url", default="http://127.0.0.1:18105")
    parser.add_argument("--catalogue-url", default="http://127.0.0.1:18110")
    parser.add_argument("--runtime-url", default="http://127.0.0.1:18095")
    parser.add_argument("--identity-url", default="http://127.0.0.1:18080")
    parser.add_argument("--fixture-url", default="http://127.0.0.1:18090")
    parser.add_argument("--restart-command", default="bash run.sh restart-worker")
    args = parser.parse_args()
    acceptance = Acceptance(args)
    if args.wait_catalogue:
        acceptance.wait_catalogue()
    else:
        acceptance.run()


if __name__ == "__main__":
    main()

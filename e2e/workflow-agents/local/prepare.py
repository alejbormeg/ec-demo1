#!/usr/bin/env python3
"""Prepare disposable, synthetic-only local workflow-agent inputs and credentials."""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tarfile


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]


def git(*args, cwd=ROOT):
    return subprocess.check_output(["git", "-C", str(cwd), *args], text=True).strip()


def repository(name, env, candidates):
    override = os.environ.get(env)
    paths = [Path(override)] if override else candidates
    for path in paths:
        if (path / ".git").exists():
            return path.resolve()
    raise SystemExit(f"Cannot locate {name}; set {env} to its feature-branch checkout.")


def discover():
    common = Path(git("rev-parse", "--git-common-dir"))
    if not common.is_absolute():
        common = ROOT / common
    siblings = common.resolve().parent.parent
    return {
        "demo": ROOT,
        "engine": repository("EventConductor", "ENGINE_REPO", [
            siblings / "eventconductor-workflow-agent-step", siblings / "eventconductor"]),
        "ia": repository("ec-ia-config", "IA_CONFIG_REPO", [siblings / "ec-ia-config"]),
        "definitions": repository("ec-definitions", "DEFINITIONS_REPO", [siblings / "ec-definitions"]),
    }


def write_private(path, content):
    path.write_text(content)
    path.chmod(0o600)


def local_workflow(source):
    text = source.read_text()
    if "syncInvocation:" in text or "  - id: reply\n" in text:
        raise SystemExit("Synthetic workflow changed; inspect its local sync/reply adaptation.")
    text = text.replace("steps:\n", "syncInvocation:\n  enabled: true\n  defaultDeadlineMs: 1000\nsteps:\n", 1)
    reply = ("  - id: reply\n    type: REPLY\n    name: Respond with the assessment\n"
             "    preconditionStepId: assess\n    replyExpression: \"json:parse(assessment)\"\n")
    if "  - id: accepted\n" not in text or text.count("      - stepId: assess\n") != 2:
        raise SystemExit("Synthetic workflow changed; inspect its END guard adaptation.")
    return text.replace("  - id: accepted\n", reply + "  - id: accepted\n", 1).replace(
        "      - stepId: assess\n", "      - stepId: reply\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", type=Path, default=HERE / ".state")
    args = parser.parse_args()
    os.umask(0o077)
    state = args.state_dir.resolve()
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    state.chmod(0o700)
    repos = discover()
    secret_file = state / "credentials.json"
    if secret_file.exists():
        credentials = json.loads(secret_file.read_text())
    else:
        credentials = {key: secrets.token_hex(24) for key in (
            "ENGINE_DB_PASSWORD", "RUNTIME_DB_PASSWORD", "CATALOGUE_DB_PASSWORD",
            "KEYCLOAK_ADMIN_PASSWORD", "OPERATOR_PASSWORD", "WORKFLOW_CLIENT_SECRET",
            "ENGINE_HTTP_PASSWORD")}
        credentials["CP_CRYPTO_KEY"] = base64.b64encode(secrets.token_bytes(32)).decode()
        write_private(secret_file, json.dumps(credentials, indent=2) + "\n")

    required = {
        "agents/workflow-case-assessor.yaml", "agents/workflow-evidence-agent.yaml",
        "llms/workflow-synthetic-model.yaml", "llms/workflow-synthetic-embeddings.yaml",
        "mcp/workflow-case-data.yaml", "rag/workflow-handbook.yaml",
    }
    digests = {}
    with tarfile.open(state / "catalogue.tar.gz", "w:gz") as archive:
        for relative in sorted(required):
            source = repos["ia"] / "ia" / relative
            if not source.is_file():
                raise SystemExit(f"Missing synthetic catalogue file: {source}")
            data = source.read_bytes()
            digests[relative] = hashlib.sha256(data).hexdigest()
            info = tarfile.TarInfo("local-ec-ia-config/ia/" + relative)
            info.size = len(data)
            info.mode = 0o644
            archive.addfile(info, io.BytesIO(data))

    definitions = state / "definitions"
    definitions.mkdir(exist_ok=True)
    definitions.chmod(0o755)  # The unprivileged engine reads this isolated mount.
    for old in definitions.iterdir():
        if old.is_file():
            old.unlink()
    for name in ("run-agent.ectask", "assess-synthetic-case.ec"):
        matches = list((repos["definitions"] / "definitions").rglob(name))
        if len(matches) != 1:
            raise SystemExit(f"Expected exactly one {name} in {repos['definitions']}")
        if name.endswith(".ec"):
            (definitions / name).write_text(local_workflow(matches[0]))
        else:
            shutil.copyfile(matches[0], definitions / name)
        (definitions / name).chmod(0o644)

    realm = {
        "realm": "workflow-agents", "enabled": True, "sslRequired": "none",
        "registrationAllowed": False,
        "roles": {"realm": [{"name": "workflow-agent-executor"}, {"name": "ai-admin"}]},
        "clients": [
            {"clientId": "workflow-agent-executor", "enabled": True,
             "protocol": "openid-connect", "publicClient": False,
             "secret": credentials["WORKFLOW_CLIENT_SECRET"],
             "serviceAccountsEnabled": True, "standardFlowEnabled": False,
             "directAccessGrantsEnabled": False,
             "defaultClientScopes": ["basic", "roles"],
             "protocolMappers": [{"name": "workflow-executor-audience",
                 "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                 "config": {"included.client.audience": "workflow-agent-executor",
                            "access.token.claim": "true", "id.token.claim": "false"}}]},
            {"clientId": "workflow-local-operator", "enabled": True,
             "protocol": "openid-connect", "publicClient": True,
             "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
             "defaultClientScopes": ["basic", "roles"],
             "protocolMappers": [{"name": "operator-account-audience",
                 "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                 "config": {"included.client.audience": "account",
                            "access.token.claim": "true", "id.token.claim": "false"}}]},
        ],
        "users": [
            {"username": "service-account-workflow-agent-executor", "enabled": True,
             "serviceAccountClientId": "workflow-agent-executor",
             "realmRoles": ["workflow-agent-executor"]},
            {"username": "local-operator", "enabled": True,
             "firstName": "Synthetic", "lastName": "Operator",
             "email": "local-operator@example.invalid", "emailVerified": True,
             "realmRoles": ["ai-admin"],
             "credentials": [{"type": "password", "temporary": False,
                              "value": credentials["OPERATOR_PASSWORD"]}]},
        ],
    }
    imports = state / "keycloak-import"
    imports.mkdir(exist_ok=True)
    imports.chmod(0o755)
    realm_file = imports / "workflow-agents-realm.json"
    realm_file.write_text(json.dumps(realm, indent=2) + "\n")
    realm_file.chmod(0o644)  # Parent state is private; Keycloak's own user needs read access.
    env = {**credentials, "COMPOSE_PROJECT_NAME": "ec-workflow-agent-local",
           "LOCAL_STATE_DIR": str(state), "DEMO_REPO": str(ROOT),
           "ENGINE_REPO": str(repos["engine"]), "IA_CONFIG_REPO": str(repos["ia"]),
           "DEFINITIONS_REPO": str(repos["definitions"]),
           "KEYCLOAK_OPERATOR_USERNAME": "local-operator",
           "KEYCLOAK_OPERATOR_PASSWORD": credentials["OPERATOR_PASSWORD"],
           "KEYCLOAK_OPERATOR_CLIENT_ID": "workflow-local-operator",
           "WORKFLOW_AGENT_CLIENT_SECRET": credentials["WORKFLOW_CLIENT_SECRET"]}
    if any("\n" in value or "'" in value for value in env.values()):
        raise SystemExit("Repository paths may not contain newlines or apostrophes.")
    write_private(HERE / ".env", "".join(f"{key}='{value}'\n" for key, value in env.items()))
    private_environments = {
        "engine-db": {"POSTGRES_PASSWORD": credentials["ENGINE_DB_PASSWORD"],
                      "RUNTIME_DB_PASSWORD": credentials["RUNTIME_DB_PASSWORD"]},
        "catalogue-db": {"POSTGRES_PASSWORD": credentials["CATALOGUE_DB_PASSWORD"]},
        "keycloak-bootstrap": {"KC_BOOTSTRAP_ADMIN_USERNAME": "admin",
                               "KC_BOOTSTRAP_ADMIN_PASSWORD": credentials["KEYCLOAK_ADMIN_PASSWORD"]},
    }
    for name, values in private_environments.items():
        write_private(state / f"{name}.env",
                      "".join(f"{key}='{value}'\n" for key, value in values.items()))
    evidence = {name: {"path": str(path), "commit": git("rev-parse", "HEAD", cwd=path),
                       "branch": git("branch", "--show-current", cwd=path)}
                for name, path in repos.items()}
    evidence["catalogueSha256"] = digests
    write_private(state / "inputs.json", json.dumps(evidence, indent=2) + "\n")
    print(f"Prepared synthetic local state in {state}; credentials are private and were not printed.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Talks to the IA control plane's agents over A2A — as one more client of the plane: the agent runs
with its own LLM, MCP servers, RAG, peers, budgets and audit, for the demo user whose token goes along.

  a2a.py card mapping-agent                       # the agent's A2A card (name, description, skills)
  a2a.py ask mapping-agent "¿Qué códigos pendientes tiene MRU01?"
  a2a.py ask console-agent "..." --context <id>   # the same conversation as a previous answer

`/a2a/**` is not exposed by the gateway (pod to pod only): the script reaches ia-agent through a
`kubectl port-forward` it opens itself (A2A_URL to use another address). The token is the demo user's,
granted by Keycloak (KEYCLOAK_URL, DEMO_USER, DEMO_PASSWORD; the password is read from
deploy/.secrets/credentials.env when not in the environment, never printed). Defaults are the local
kind cluster's (deploy/local): KUBECONFIG=~/.local/share/ec-demo1-local/kubeconfig.

The supported subset is ia-agent's: text in, text out, blocking (message/send).
"""
import json
import os
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

sys.dont_write_bytecode = True

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
NS = "ec-demo1"
LOCAL_PORT = int(os.environ.get("A2A_LOCAL_PORT", "18095"))
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://auth.localhost:8800")
REALM = os.environ.get("KEYCLOAK_REALM", "ec-demo1")
USER = os.environ.get("DEMO_USER", "demo")
KUBECONFIG = os.environ.get("KUBECONFIG", os.path.expanduser("~/.local/share/ec-demo1-local/kubeconfig"))


def password():
    if os.environ.get("DEMO_PASSWORD"):
        return os.environ["DEMO_PASSWORD"]
    with open(os.path.join(REPO, "deploy/.secrets/credentials.env")) as f:
        for line in f:
            if line.startswith("DEMO_PASSWORD="):
                return line.split("=", 1)[1].strip().strip("'\"")
    sys.exit("DEMO_PASSWORD is not set (see deploy/.secrets/credentials.env)")


def token():
    body = urllib.parse.urlencode({"grant_type": "password", "client_id": "demo", "username": USER,
                                   "password": password()}).encode()
    with urllib.request.urlopen(f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token", data=body, timeout=15) as r:
        return json.load(r)["access_token"]


def listening(port):
    with socket.socket() as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1", port)) == 0


def base_url():
    if os.environ.get("A2A_URL"):
        return os.environ["A2A_URL"].rstrip("/")
    if not listening(LOCAL_PORT):
        subprocess.Popen(["kubectl", "-n", NS, "port-forward", "svc/ia-agent", f"{LOCAL_PORT}:8095"],
                         env={**os.environ, "KUBECONFIG": KUBECONFIG},
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
        for _ in range(40):
            if listening(LOCAL_PORT):
                break
            time.sleep(0.25)
        else:
            sys.exit(f"ia-agent is not reachable: kubectl port-forward on :{LOCAL_PORT} did not come up")
    return f"http://localhost:{LOCAL_PORT}"


def card(agent):
    with urllib.request.urlopen(f"{base_url()}/a2a/{agent}/.well-known/agent-card.json", timeout=15) as r:
        return json.load(r)


def ask(agent, text, context_id=None, timeout=600):
    message = {"role": "user", "kind": "message", "messageId": str(uuid.uuid4()),
               "parts": [{"kind": "text", "text": text}]}
    if context_id:
        message["contextId"] = context_id
    rpc = {"jsonrpc": "2.0", "id": str(uuid.uuid4()), "method": "message/send", "params": {"message": message}}
    req = urllib.request.Request(f"{base_url()}/a2a/{agent}", data=json.dumps(rpc).encode(), method="POST",
                                 headers={"Content-Type": "application/json", "Authorization": "Bearer " + token()})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f"HTTP {e.code} from {agent}: {e.read().decode()[:500]}")


def text_of(result):
    """The answer's text: a Message's parts, or a Task's status message / artifacts."""
    parts = []
    if result.get("kind") == "task" or "status" in result:
        msg = (result.get("status") or {}).get("message") or {}
        parts += msg.get("parts", [])
        for artifact in result.get("artifacts", []) or []:
            parts += artifact.get("parts", [])
    else:
        parts += result.get("parts", [])
    return "\n".join(p.get("text", "") for p in parts if p.get("kind", "text") == "text")


def main(argv):
    if len(argv) >= 2 and argv[0] == "card":
        print(json.dumps(card(argv[1]), indent=2, ensure_ascii=False))
        return 0
    if len(argv) >= 3 and argv[0] == "ask":
        context = argv[argv.index("--context") + 1] if "--context" in argv else None
        response = ask(argv[1], argv[2], context)
        if "error" in response:
            print(f"A2A error {response['error'].get('code')}: {response['error'].get('message')}", file=sys.stderr)
            return 1
        result = response.get("result", {})
        print(text_of(result))
        ctx = result.get("contextId")
        if ctx:
            print(f"\n[contextId {ctx}]", file=sys.stderr)
        return 0
    print(__doc__)
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

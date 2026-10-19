"""Protocol checks for the deterministic fixture; sockets remain on localhost."""
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
import tarfile
import unittest
from http.client import RemoteDisconnected
from urllib.request import Request, urlopen

spec = importlib.util.spec_from_file_location("fixture_server", Path(__file__).with_name("fixture_server.py"))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class FixtureProtocol(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.server = fixture.ThreadingHTTPServer(("127.0.0.1", 0), fixture.Handler)
        self.server.ledger = str(Path(self.directory.name) / "effects.sqlite")
        self.server.uncertain = False
        with sqlite3.connect(self.server.ledger) as db:
            db.execute("CREATE TABLE effects(id INTEGER PRIMARY KEY, created REAL NOT NULL)")
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        self.server.server_close()
        self.directory.cleanup()

    def post(self, path, body):
        with urlopen(Request(self.base + path, json.dumps(body).encode(), {"Content-Type": "application/json"}), timeout=5) as response:
            return json.load(response)

    def test_mcp_initialization_and_discovered_readonly_tool(self):
        reply = self.post("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-03-26"}})
        self.assertEqual("2025-03-26", reply["result"]["protocolVersion"])
        tools = self.post("/mcp", {"jsonrpc": "2.0", "id": 2, "method": "tools/list"})["result"]["tools"]
        self.assertEqual([True, False], [t["annotations"]["readOnlyHint"] for t in tools])

    def test_uncertain_write_commits_then_loses_reply(self):
        self.server.uncertain = True
        with self.assertRaises(RemoteDisconnected):
            self.post("/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "write_case", "arguments": {"caseId": "SYN-001"}}})
        with urlopen(self.base + "/fixture/stats", timeout=5) as response:
            self.assertEqual(1, json.load(response)["writes"])

    def test_model_requests_each_capability_then_returns_valid_json(self):
        names = ["workflow_case_data_read_case", "search_workflow_handbook", "ask_workflow_evidence_agent"]
        body = {"model": "fixture", "messages": [], "tools": [{"type": "function", "function": {"name": n}} for n in names]}
        for expected in names:
            message = self.post("/v1/chat/completions", body)["choices"][0]["message"]
            self.assertEqual(expected, message["tool_calls"][0]["function"]["name"])
            body["messages"].append(message)
        content = self.post("/v1/chat/completions", body)["choices"][0]["message"]["content"]
        self.assertEqual("accepted", json.loads(content)["decision"])

    def test_embedding_batch_matches_seed_dimensions(self):
        response = self.post("/v1/embeddings", {"input": ["rule", "case"]})
        self.assertEqual([0, 1], [d["index"] for d in response["data"]])
        self.assertTrue(all(d["embedding"] == [1.0, 0.0, 0.0] for d in response["data"]))

    def test_mode_changes_without_resetting_the_effect_ledger(self):
        self.post("/fixture/mode", {"uncertainWrite": True})
        with self.assertRaises(RemoteDisconnected):
            self.post("/mcp", {"jsonrpc": "2.0", "id": 3, "method": "tools/call", "params": {"name": "write_case", "arguments": {"caseId": "SYN-001"}}})
        self.post("/fixture/mode", {"uncertainWrite": False})
        with urlopen(self.base + "/fixture/stats", timeout=5) as response:
            self.assertEqual(1, json.load(response)["writes"])
        self.assertFalse(self.server.uncertain)

    def test_gitops_endpoint_serves_the_exact_local_archive(self):
        archive = Path(self.directory.name) / "catalogue.tar.gz"
        with tarfile.open(archive, "w:gz") as tar:
            content = b"kind: agent\nid: synthetic\n"
            entry = tarfile.TarInfo("local/ia/agents/synthetic.yaml")
            entry.size = len(content)
            tar.addfile(entry, io.BytesIO(content))
        self.server.catalogue_archive = archive
        with urlopen(self.base + "/repos/local/ec-ia-config/tarball/feat%2Fworkflow-agent-step", timeout=5) as response:
            self.assertEqual("application/gzip", response.headers["Content-Type"])
            self.assertEqual(archive.read_bytes(), response.read())


if __name__ == "__main__":
    unittest.main()

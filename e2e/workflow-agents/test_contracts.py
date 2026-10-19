"""Offline checks of native AGENT integration with the existing contract preflight."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("contract_check", ROOT / "deploy/demo/check_contracts.py")
contract_check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(contract_check)


class AgentTaskContracts(unittest.TestCase):
    def check_agent(self, workers, contracts=None):
        with patch.object(contract_check, "served", return_value=workers):
            return contract_check.check(
                {"run-agent@1": "agent-calls"} if contracts is None else contracts,
                {"synthetic": [{"id": "assess", "type": "AGENT"}]}, "synthetic")

    def test_native_agent_dispatch_requires_its_registered_worker(self):
        rows, gaps, _ = self.check_agent({("run-agent@1", "agent-calls"): ["ia-workflow-worker"]})
        self.assertEqual(gaps, [])
        self.assertEqual(rows[0][2:4], ("run-agent@1", "agent-calls"))

    def test_worker_on_wrong_topic_is_reported(self):
        _, gaps, _ = self.check_agent({("run-agent@1", "wrong-topic"): ["wrong-worker"]})
        self.assertTrue(any("no worker serves run-agent@1 on agent-calls" in gap for gap in gaps))

    def test_missing_agent_contract_is_reported(self):
        _, gaps, _ = self.check_agent({}, contracts={})
        self.assertTrue(any("task run-agent@1 has no contract" in gap for gap in gaps))

    def test_existing_action_contract_still_works(self):
        with patch.object(contract_check, "served", return_value={("existing@1", "work"): ["worker"]}):
            _, gaps, _ = contract_check.check({"existing@1": "work"}, {
                "legacy": [{"id": "action", "type": "ACTION", "task": "existing"}]}, "synthetic")
        self.assertEqual(gaps, [])


if __name__ == "__main__":
    unittest.main()

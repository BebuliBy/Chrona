import contextlib
import importlib.util
import io
import json
import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


def load(name):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


xray = load("xray_config")
ops = load("ops")
probe = load("probe")
remote = load("remote_baseline")
token_installer = load("install_bot_token")
deployment = load("deployment")
serve = load("serve")
with patch("sys.path", [str(ROOT / "scripts"), *sys.path]):
    bot_config = load("configure_bot")


def sample():
    # Synthetic ONLY; intentionally no real address/client identifier.
    return {"protocol": "vless", "settings": {"vnext": []},
            "streamSettings": {"network": "tcp", "security": "reality", "realitySettings": {"publicKey": "fixture"}}}


class GeneratorTests(unittest.TestCase):
    def test_isolation(self):
        cfg = xray.isolated_config({"outbounds": [sample(), {"protocol": "freedom"}], "inbounds": ["laptop"]})
        self.assertEqual(len(cfg["outbounds"]), 1)
        self.assertEqual(cfg["inbounds"][0]["port"], 10808)
        self.assertFalse(cfg["inbounds"][0]["settings"]["udp"])
        self.assertEqual(cfg["log"]["loglevel"], "none")
        self.assertNotIn("freedom", json.dumps(cfg))

    def test_server_key_rejected(self):
        cfg = sample()
        cfg["streamSettings"]["realitySettings"]["privateKey"] = "fixture"
        with self.assertRaises(ValueError):
            xray.isolated_config(cfg)

    def test_host_sockopt_rejected(self):
        cfg = sample()
        cfg["streamSettings"]["sockopt"] = {"mark": 1}
        with self.assertRaises(ValueError):
            xray.isolated_config(cfg)

    def test_dependency_rejected(self):
        cfg = sample()
        cfg["proxySettings"] = {"tag": "other"}
        with self.assertRaises(ValueError):
            xray.isolated_config(cfg)

    def test_placeholder_rejected(self):
        with self.assertRaises(ValueError):
            xray.isolated_config(json.loads((ROOT / "templates/outbound.json.example").read_text()))

    def test_private_mode_and_no_overwrite(self):
        with tempfile.TemporaryDirectory(dir="/tmp/opencode") as tmp:
            folder = Path(tmp)
            folder.chmod(0o700)
            src, dst = folder / "source", folder / "output"
            src.write_text(json.dumps(sample()))
            src.chmod(0o600)
            cfg = xray.isolated_config(xray.read_private(src))
            xray.write_new_private(dst, cfg)
            self.assertEqual(stat.S_IMODE(dst.stat().st_mode), 0o600)
            with self.assertRaises(FileExistsError):
                xray.write_new_private(dst, cfg)
            src.chmod(0o644)
            with self.assertRaises(ValueError):
                xray.read_private(src)

    def test_symlink_and_traversal(self):
        with tempfile.TemporaryDirectory(dir="/tmp/opencode") as tmp:
            link = Path(tmp) / "link"
            link.symlink_to("/not-a-real-secret")
            with self.assertRaises(ValueError):
                xray.guarded_path(link)
        with self.assertRaises(ValueError):
            xray.guarded_path("/etc/chrona/../other")


class SafetyTests(unittest.TestCase):
    def test_serve_default_no_private_state_or_cli(self):
        with patch("sys.argv", ["serve", "apply"]), patch.object(serve, "config", side_effect=AssertionError("read")), patch.object(serve, "call", side_effect=AssertionError("CLI")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(serve.main(), 0)

    def test_enabled_auth_smoke_never_begins_login(self):
        from types import SimpleNamespace
        replies = [(200, b'{"status":"ready"}'), (401, b'{"error":{"code":"UNAUTHENTICATED"}}'), (401, b'{"error":{"code":"UNAUTHENTICATED"}}')]
        with patch.object(serve, "private", return_value=SimpleNamespace(read_text=lambda: "TELEGRAM_AUTH_ENABLED=true\n")), patch.object(serve, "db_counts", return_value=b"0\n0\n0\n"), patch.object(serve, "https", side_effect=replies) as http:
            self.assertTrue(serve.smoke("redacted:443"))
            self.assertEqual(http.call_count, 3)
            self.assertTrue(all("/auth/" not in call.args[1] for call in http.call_args_list))

    def test_serve_only_own_path_diff(self):
        import copy
        before = {"TCP": {"443": {"HTTPS": True}}, "Web": {"redacted:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1:9999"}}}}}
        self.assertEqual(serve.select_endpoint(before), "redacted:443")
        after = copy.deepcopy(before)
        after["Web"]["redacted:443"]["Handlers"]["/chrona"] = {"Proxy": serve.UPSTREAM}
        self.assertTrue(serve.unchanged_except_own(before, after, "redacted:443"))
        after["Web"]["redacted:443"]["Handlers"]["/"] = {"Proxy": "changed"}
        self.assertFalse(serve.unchanged_except_own(before, after, "redacted:443"))
        with self.assertRaises(ValueError):
            serve.select_endpoint(after)

    def test_serve_funnel_or_credential_config_refused(self):
        cfg = {"TCP": {"443": {"HTTPS": True}}, "Web": {"redacted:443": {"Handlers": {"/": {"Proxy": "http://127.0.0.1"}}}}, "AllowFunnel": {"redacted:443": True}}
        with self.assertRaises(ValueError):
            serve.select_endpoint(cfg)
        with self.assertRaises(ValueError):
            serve.no_auth_secrets({"Proxy": "http://FAKE_TEST:FAKE_TEST@redacted"})

    def test_serve_false_funnel_normalization_only(self):
        import copy
        before = {"Web": {"redacted:443": {"Handlers": {"/": {"Proxy": "existing"}}}}}
        after = copy.deepcopy(before)
        after["Web"]["redacted:443"]["Handlers"]["/chrona/"] = {"Proxy": serve.UPSTREAM}
        after["AllowFunnel"] = {"redacted:443": False}
        self.assertTrue(serve.unchanged_except_own(before, after, "redacted:443"))
        after["AllowFunnel"]["redacted:443"] = True
        self.assertFalse(serve.unchanged_except_own(before, after, "redacted:443"))
    def test_bot_config_default_no_secret_read_no_network(self):
        with patch("sys.argv", ["configure"]), patch.object(bot_config, "token_path", side_effect=AssertionError("secret")), patch.object(bot_config, "docker", side_effect=AssertionError("docker")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(bot_config.main(), 0)

    def test_bot_config_never_calls_poll_or_send(self):
        with self.assertRaises(ValueError):
            bot_config.telegram_info("127.0.0.1", "FAKE_TEST", "getUpdates")
        with self.assertRaises(ValueError):
            bot_config.telegram_info("127.0.0.1", "FAKE_TEST", "sendMessage")

    def test_bot_config_webhook_or_pending_fail_closed(self):
        from types import SimpleNamespace
        fake_secret = SimpleNamespace(read_text=lambda: "00000:" + "FAKE_TEST_" * 3)
        for webhook in ({"url": "https://redacted.invalid", "pending_update_count": 0}, {"url": "", "pending_update_count": 1}):
            with patch.object(bot_config, "token_path", return_value=fake_secret), patch.object(bot_config, "telegram_info", side_effect=[{"is_bot": True, "id": 1, "username": "FAKE_TEST_BOT"}, webhook]):
                with self.assertRaisesRegex(ValueError, "never delete/drop"):
                    bot_config.identify("127.0.0.1")
    def test_deployment_phase_syntax(self):
        import ast
        for name in ("LAYOUT", "UPLOAD", "SYNC", "IMAGES", "VPN", "START", "STATUS", "DIAGNOSE", "EGRESS", "BACKUP", "VERIFY", "REFRESH_API", "API_CHECK", "FINAL_PREFLIGHT", "DATA_PERMISSIONS", "DOCS", "BOT_BASELINE", "BOT_CONFIGURE", "BOT_ENABLE", "BOT_OBSERVE"):
            ast.parse(deployment.PREAMBLE + getattr(deployment, name))

    def test_deployment_default_no_private_read_or_ssh(self):
        with patch("sys.argv", ["deployment", "vpn"]), patch.object(deployment, "bundle", side_effect=AssertionError("read")), patch.object(deployment.subprocess, "run", side_effect=AssertionError("SSH")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(deployment.main(), 0)

    def test_remote_baseline_syntax_and_no_env_dump(self):
        import ast
        ast.parse(remote.BASELINE)
        self.assertNotIn(".Config.Env", remote.BASELINE)
        self.assertNotIn(".Config.Cmd", remote.BASELINE)
        self.assertNotIn(".State.Health.Log", remote.BASELINE)

    def test_remote_default_never_authenticates(self):
        with patch("sys.argv", ["remote"]), patch.object(remote, "credential", side_effect=AssertionError("credential")), patch.object(remote.subprocess, "run", side_effect=AssertionError("SSH")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(remote.main(), 0)

    def test_token_installer_default_never_prompts(self):
        with patch("sys.argv", ["token"]), patch.object(token_installer.getpass, "getpass", side_effect=AssertionError("prompt")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(token_installer.main(), 0)

    def test_metadata_placeholders_blocked(self):
        with self.assertRaises(ValueError):
            ops.parse_metadata((ROOT / "runtime.env.example").read_text())

    def test_metadata_injection_blocked(self):
        for text in ("XRAY_IMAGE=$(id)", "DOCKER_HOST=tcp://elsewhere", "XRAY_IMAGE=x\nXRAY_IMAGE=y"):
            with self.assertRaises(ValueError):
                ops.parse_metadata(text)

    def test_proxy_independent_of_backend_image(self):
        import hashlib
        digest = hashlib.sha256(b"synthetic test fixture NOT a real image digest").hexdigest()
        data = "XRAY_IMAGE=ghcr.io/xtls/xray-core:26.3.27@sha256:" + digest + "\nCPU_SMOKE=approved-baseline-amd64\n"
        self.assertIn("XRAY_IMAGE", ops.parse_metadata(data, ("XRAY_IMAGE",)))

    def test_project_allowlist(self):
        with self.assertRaises(ValueError):
            ops.compose("nextcloud", "down")
        cmd = ops.compose("chrona", "stop")
        self.assertIn("unix:///var/run/docker.sock", cmd)
        self.assertNotIn("sudo", cmd)

    def test_all_dryruns_no_daemon_no_private_read(self):
        for action in ops.ACTIONS:
            with patch("sys.argv", ["ops", action]), patch.object(ops, "execute", side_effect=AssertionError("execute called")), contextlib.redirect_stdout(io.StringIO()):
                self.assertEqual(ops.main(), 0)

    def test_generator_dryrun_no_read(self):
        with patch("sys.argv", ["xray", "--input", "/does/not/exist", "--output", "/also/missing"]), patch.object(xray, "read_private", side_effect=AssertionError("read")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(xray.main(), 0)

    def test_probe_dryrun_no_network(self):
        with patch("sys.argv", ["probe"]), patch.object(probe, "socks_https", side_effect=AssertionError("network")), contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(probe.main(), 0)

    def test_execution_requires_approval(self):
        with patch("sys.argv", ["ops", "deploy-app", "--execute"]), contextlib.redirect_stderr(io.StringIO()):
            with self.assertRaises(SystemExit):
                ops.main()


class ComposeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        try:
            import yaml
        except ImportError:
            raise unittest.SkipTest("PyYAML not installed; no install attempted")
        cls.docs = {n: yaml.safe_load((ROOT / ("compose." + n + ".yml")).read_text()) for n in ("proxy", "app", "restore")}

    def test_no_host_exposure(self):
        for doc in self.docs.values():
            for service in doc["services"].values():
                for port in service.get("ports", []):
                    self.assertTrue(port.startswith("127.0.0.1:"))
                self.assertNotIn("network_mode", service)
                self.assertNotIn("privileged", service)
                self.assertTrue(service["read_only"])
                self.assertEqual(service["cap_drop"], ["ALL"])
                self.assertNotEqual(service["user"].split(":")[0], "0")

    def test_worker_only_internal_networks(self):
        app = self.docs["app"]
        self.assertEqual(set(app["services"]["bot"]["networks"]), {"app", "proxy-clients"})
        self.assertTrue(app["networks"]["app"]["internal"])
        self.assertTrue(self.docs["proxy"]["networks"]["clients"]["internal"])
        self.assertTrue(app["networks"]["proxy-clients"]["external"])
        self.assertNotIn("api-ingress", app["services"]["bot"]["networks"])
        self.assertIn("api-ingress", app["services"]["api"]["networks"])

    def test_restore_isolated(self):
        restore = self.docs["restore"]
        self.assertNotIn("chrona-db-data", json.dumps(restore))
        self.assertTrue(restore["networks"]["restore"]["internal"])
        self.assertEqual(restore["services"]["db"]["restart"], "no")

    def test_secrets_are_mounts_not_values(self):
        for doc in self.docs.values():
            for service in doc["services"].values():
                for key, value in service.get("environment", {}).items():
                    if "PASSWORD" in key or "TOKEN" in key:
                        self.assertTrue(key.endswith("_FILE"))
                        self.assertTrue(value.startswith("/run/private/") or value.startswith("/run/secrets/chrona/"))
                for mount in service.get("volumes", []):
                    if isinstance(mount, dict) and mount.get("type") == "bind":
                        self.assertTrue(mount["read_only"])
                        self.assertFalse(mount["bind"]["create_host_path"])
                    elif isinstance(mount, dict) and mount.get("type") == "volume":
                        self.assertTrue(mount["volume"]["nocopy"])

    def test_exact_backend_contract(self):
        services = self.docs["app"]["services"]
        self.assertEqual(services["bot"]["profiles"], ["bot"])
        self.assertEqual(services["bot"]["environment"]["PORT"], "8081")
        self.assertEqual(services["api"]["environment"]["PORT"], "8080")
        self.assertEqual(services["bot"]["environment"]["TELEGRAM_PROXY"], "socks5h://xray-proxy:10808")
        self.assertEqual(services["api"]["environment"]["SECRETS_PATH"], "/run/secrets/chrona")
        self.assertNotIn("telegram-token", json.dumps(services["api"]))
        self.assertEqual(services["migrate"]["command"], ["migrate"])
        self.assertEqual(services["api"]["environment"]["TELEGRAM_AUTH_ENABLED"], "${TELEGRAM_AUTH_ENABLED:-false}")
        self.assertNotIn("BOT_USERNAME", services["api"]["environment"])
        self.assertNotIn("BOT_USERNAME", services["migrate"]["environment"])
        self.assertFalse(services["api"]["env_file"][0]["required"])
        for doc in self.docs.values():
            for service in doc["services"].values():
                self.assertTrue(all(mount.startswith("/") for mount in service.get("tmpfs", [])))


if __name__ == "__main__":
    unittest.main()

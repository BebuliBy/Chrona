#!/usr/bin/env python3
"""Approved Tailscale Serve /chrona ONLY; never reset/replace entire Serve or enable Funnel.

Default dry-run. Private HTTPS hostname/config never printed. No certificate keys read.
"""
import argparse
import base64
import copy
import json
import os
from pathlib import Path
import re
import secrets
import stat
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = Path("/etc/chrona")
BACKUPS = Path("/var/backups/chrona")
STATE = BASE / "serve-change.json"
ORIGIN = BASE / "client-origin"
UPSTREAM = "http://127.0.0.1:18080"
TS = "/usr/bin/tailscale"


def private(path):
    for item in [path, *path.parents]:
        if item.is_symlink() or item.stat().st_uid != 0 or item.stat().st_mode & 0o022:
            raise ValueError("unsafe owner/path")
    return path


def write_new(path, data):
    private(path.parent)
    if stat.S_IMODE(path.parent.stat().st_mode) != 0o700:
        raise ValueError("private parent0700 required")
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as out:
        out.write(data)
        out.flush()
        os.fsync(out.fileno())


def call(*args):
    return subprocess.check_output([TS, *args], stderr=subprocess.DEVNULL, timeout=45)


def config():
    return json.loads(call("serve", "status", "--json"))


def own_path(path):
    return path.rstrip("/") == "/chrona" or path.startswith("/chrona/")


def select_endpoint(cfg):
    if not cfg.get("TCP", {}).get("443", {}).get("HTTPS"):
        raise ValueError("existing HTTPS443 required")
    choices = [(host, web) for host, web in cfg.get("Web", {}).items() if host.endswith(":443") and "/" in web.get("Handlers", {})]
    if len(choices) != 1:
        raise ValueError("unique existing root HTTPS endpoint required")
    endpoint, web = choices[0]
    if any(own_path(path) for path in web["Handlers"]):
        raise ValueError("existing chrona route conflict")
    if (cfg.get("AllowFunnel") or {}).get(endpoint):
        raise ValueError("existing Funnel would expose new route publicly")
    return endpoint


def no_auth_secrets(value):
    if isinstance(value, dict):
        for key, item in value.items():
            if re.search(r"(?i)password|authorization|private.?key|auth.?key|secret|token", key):
                raise ValueError("unexpected auth-bearing config key")
            no_auth_secrets(item)
    elif isinstance(value, list):
        for item in value:
            no_auth_secrets(item)
    elif isinstance(value, str) and "://" in value:
        parsed = urllib.parse.urlsplit(value)
        if parsed.username or parsed.password or parsed.query or parsed.fragment:
            raise ValueError("auth/query-bearing existing target forbidden")


def unchanged_except_own(before, after, endpoint):
    candidate = copy.deepcopy(after)
    handlers = candidate.get("Web", {}).get(endpoint, {}).get("Handlers", {})
    added = [path for path in handlers if own_path(path)]
    if len(added) != 1 or added[0] not in ("/chrona", "/chrona/"):
        return False
    handler = handlers.pop(added[0])
    if handler.get("Proxy", "").rstrip("/") != UPSTREAM:
        return False
    # A false/missing AllowFunnel entry is the same tailnet-only policy; some
    # CLI versions serialize an explicit false during an otherwise narrow edit.
    original = copy.deepcopy(before)
    for cfg in (original, candidate):
        enabled = {key: value for key, value in (cfg.get("AllowFunnel") or {}).items() if value}
        cfg.pop("AllowFunnel", None)
        if enabled:
            cfg["AllowFunnel"] = enabled
    return candidate == original


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def https(endpoint, path, body=None, method=None):
    host = endpoint.removesuffix(":443")
    if not re.fullmatch(r"[a-zA-Z0-9.-]+", host):
        raise ValueError("invalid private endpoint")
    # Default CA+hostname validation, no verify=false or redirect following.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    headers = {} if body is None else {"Content-Type": "application/json"}
    if method == "PUT":
        headers["Idempotency-Key"] = str(uuid.uuid4())
    request = urllib.request.Request("https://" + host + path, data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
    try:
        with opener.open(request, timeout=20) as response:
            return response.status, response.read(65536)
    except urllib.error.HTTPError as error:
        return error.code, error.read(65536)


def nextcloud(endpoint):
    status, body = https(endpoint, "/status.php")
    data = json.loads(body)
    if status != 200 or data.get("installed") is not True or data.get("maintenance") is not False or data.get("needsDbUpgrade") is not False:
        raise ValueError("existing Nextcloud TLS root status failed")
    return {key: data.get(key) for key in ("installed", "maintenance", "needsDbUpgrade", "version")}


def db_counts():
    return subprocess.check_output(["/usr/bin/docker", "--host", "unix:///var/run/docker.sock", "exec", "chrona-db-1", "psql", "-U", "chrona", "-d", "chrona", "-Atc", "SELECT count(*) FROM accounts; SELECT count(*) FROM login_challenges; SELECT count(*) FROM device_sessions;"], stderr=subprocess.DEVNULL, timeout=20)


def smoke(endpoint):
    counts = db_counts()
    status, body = https(endpoint, "/chrona/health/ready")
    if status != 200 or json.loads(body).get("status") != "ready":
        raise ValueError("HTTPS prefix rewrite/readiness failed")
    status, body = https(endpoint, "/chrona/v1/me")
    if status != 401 or json.loads(body).get("error", {}).get("code") != "UNAUTHENTICATED":
        raise ValueError("HTTPS auth boundary failed")
    status, body = https(endpoint, "/chrona/v1/me/discoverability", {"enabled": True}, method="PUT")
    if status != 401 or json.loads(body).get("error", {}).get("code") != "UNAUTHENTICATED":
        raise ValueError("HTTPS discoverability write auth boundary failed")
    synthetic = {"deviceId": str(uuid.uuid4()), "deviceLabel": "Infrastructure HTTPS acceptance synthetic", "codeChallenge": base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="), "codeChallengeMethod": "S256"}
    status, body = https(endpoint, "/chrona/v1/auth/telegram/challenges", synthetic)
    if status != 503 or json.loads(body).get("error", {}).get("code") != "TELEGRAM_NOT_CONFIGURED" or any(word in body for word in (b"t.me", b"telegramStartUrl", b"accessToken", b"refreshToken")):
        raise ValueError("HTTPS disabled auth/no URL boundary failed")
    if counts != db_counts():
        raise ValueError("HTTPS smoke created identity state")


def read_state():
    private(STATE)
    if stat.S_IMODE(STATE.stat().st_mode) != 0o600:
        raise ValueError("private state0600 required")
    state = json.loads(STATE.read_text())
    backup = Path(state["backup"])
    private(backup)
    if backup.parent != BACKUPS or not backup.name.startswith("serve-before-") or stat.S_IMODE(backup.stat().st_mode) != 0o600:
        raise ValueError("own baseline path invalid")
    return state, json.loads(backup.read_text())


def rollback(state, before):
    current = config()
    if current == before:
        print("SERVE_ALREADY_AT_BASELINE")
        return
    if not unchanged_except_own(before, current, state["endpoint"]):
        raise ValueError("concurrent mapping change: do not alter unknown Serve state")
    call("serve", "--bg", "--https=443", "--set-path=/chrona", "off")
    restored = config()
    # Verify real mapping restoration, allowing only false/null Funnel cosmetic
    # normalization. Never use full-config reset or set-config for rollback.
    for cfg in (restored, before):
        enabled = {key: value for key, value in (cfg.get("AllowFunnel") or {}).items() if value}
        cfg.pop("AllowFunnel", None)
        if enabled:
            cfg["AllowFunnel"] = enabled
    if restored != before:
        raise ValueError("path-only rollback differs from baseline")
    if ORIGIN.exists() and private(ORIGIN).read_text().strip() == "https://" + state["endpoint"].removesuffix(":443") + "/chrona/":
        ORIGIN.unlink()
    print("SERVE_OWN_PATH_ONLY_ROLLBACK_VERIFIED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("baseline", "apply", "check", "rollback"))
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: Serve " + args.action + "; no config/private reads/network or writes")
        return 0
    if os.geteuid() != 0:
        parser.error("approved root operator required")
    applied = False
    state = before = None
    try:
        if args.action == "baseline":
            private(BASE)
            private(BACKUPS)
            if STATE.exists() or ORIGIN.exists():
                raise ValueError("existing own state/origin: review first")
            help_result = subprocess.run([TS, "serve", "--help"], stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30)
            help_text = help_result.stdout + help_result.stderr
            if b"--set-path" not in help_text or b"--https" not in help_text:
                raise ValueError("standard CLI lacks narrow HTTPS path support")
            raw = call("serve", "status", "--json")
            before = json.loads(raw)
            no_auth_secrets(before)
            endpoint = select_endpoint(before)
            root_status = nextcloud(endpoint)
            backup = BACKUPS / ("serve-before-" + str(time.time_ns()) + ".json")
            write_new(backup, raw)
            state = {"backup": str(backup), "endpoint": endpoint, "nextcloud": root_status}
            write_new(STATE, json.dumps(state).encode())
            summary = {"https_port": 443, "private_endpoint_count": len(before.get("Web", {})), "existing_paths": sorted(before["Web"][endpoint]["Handlers"]), "funnel_for_endpoint": False, "nextcloud_trusted_tls_status": "PASS", "own_path_conflict": False, "backup_mode": "0600"}
            print("SANITIZED_SERVE_BASELINE=" + json.dumps(summary, sort_keys=True))
            return 0
        state, before = read_state()
        if args.action == "rollback":
            rollback(state, before)
            return 0
        if args.action == "apply":
            if config() != before:
                raise ValueError("Serve changed since baseline")
            call("serve", "--bg", "--https=443", "--set-path=/chrona", UPSTREAM)
            applied = True
        current = config()
        if not unchanged_except_own(before, current, state["endpoint"]):
            raise ValueError("existing mappings changed")
        smoke(state["endpoint"])
        if nextcloud(state["endpoint"]) != state["nextcloud"]:
            raise ValueError("Nextcloud root changed")
        if args.action == "apply":
            write_new(ORIGIN, ("https://" + state["endpoint"].removesuffix(":443") + "/chrona/\n").encode())
        private(ORIGIN)
        if stat.S_IMODE(ORIGIN.stat().st_mode) != 0o600 or ORIGIN.read_text().strip() != "https://" + state["endpoint"].removesuffix(":443") + "/chrona/":
            raise ValueError("private origin file permissions/content mismatch")
        print("HTTPS_CHRONA_PREFIX_CA_READY200_ME401_DISCOVERABILITY_PUT401_AUTH503_NO_IDENTITY_PASS")
        print("NEXTCLOUD_ROOT_TRUSTED_TLS_UNCHANGED_ALL_PREEXISTING_MAPPINGS_PRESERVED")
        print("TAILNET_ONLY_NO_FUNNEL_PRIVATE_ORIGIN_FILE0600")
        return 0
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        print("SERVE_OPERATION_FAILED (private URLs/details suppressed)")
        if type(error) is ValueError:
            print("SERVE_GUARD=" + str(error))
        if applied:
            try:
                rollback(state, before)
            except (OSError, ValueError, KeyError, subprocess.SubprocessError):
                print("SERVE_OWN_PATH_ROLLBACK_NEEDS_REVIEW_NO_RESET_ATTEMPTED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

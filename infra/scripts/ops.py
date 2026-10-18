#!/usr/bin/env python3
"""Local-on-target operations, NEVER SSH/sudo. Dry-run is default; no daemon in dry-run."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys

INSTALL = Path("/opt/chrona/infra")
ENV = Path("/etc/chrona/runtime.env")
BACKUPS = Path("/var/backups/chrona")
DOCKER = ["/usr/bin/docker", "--host", "unix:///var/run/docker.sock"]
SAFE_ENV = {"PATH": "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "HOME": "/root", "LANG": "C.UTF-8"}
FILES = {"chrona": "compose.app.yml", "chrona-proxy": "compose.proxy.yml", "chrona-restore": "compose.restore.yml"}
KEYS = {"XRAY_IMAGE", "POSTGRES_IMAGE", "BACKEND_IMAGE", "BACKEND_CONTRACT", "CPU_SMOKE", "TELEGRAM_AUTH_ENABLED", "API_HOST_PORT"}
ACTIONS = ("init-data", "preflight", "xray-test", "deploy-proxy", "deploy-db", "migrate", "deploy-app", "deploy-bot", "enable-telegram", "disable-telegram", "stop-app", "stop-proxy", "backup", "restore", "stop-restore")


def path_guard(path, private=False, uid=0, directory=False):
    p = Path(path)
    if not p.is_absolute() or ".." in p.parts:
        raise ValueError("normalized absolute path required")
    for item in [p, *p.parents]:
        if item.is_symlink():
            raise ValueError("symlink forbidden")
        s = item.stat()
        if s.st_uid != uid or s.st_mode & 0o022:
            raise ValueError("unsafe owner or writable ancestor")
    s = p.stat()
    if directory and not stat.S_ISDIR(s.st_mode):
        raise ValueError("directory required")
    if not directory and (not stat.S_ISREG(s.st_mode) or s.st_nlink != 1):
        raise ValueError("single-link regular file required")
    if private and stat.S_IMODE(s.st_mode) != 0o600:
        raise ValueError("private metadata must be 0600")
    return p


def parse_metadata(text, required=("XRAY_IMAGE", "POSTGRES_IMAGE", "BACKEND_IMAGE")):
    result = {}
    for line in text.splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, sep, value = line.partition("=")
        if not sep or key not in KEYS or key in result or not re.fullmatch(r"[A-Za-z0-9_./:@+-]+", value):
            raise ValueError("invalid metadata format/key")
        result[key] = value
    for key in required:
        value = result.get(key, "")
        local_image_id = key == "BACKEND_IMAGE" and re.fullmatch(r"sha256:[a-f0-9]{64}", value)
        if not local_image_id and not re.fullmatch(r"[a-z0-9][a-z0-9./:_-]*@sha256:[a-f0-9]{64}", value):
            raise ValueError("verified pinned image refs required")
        if len(set(value.rsplit(":", 1)[1])) < 4:
            raise ValueError("obvious dummy digest forbidden")
    if "XRAY_IMAGE" in required and not result["XRAY_IMAGE"].startswith("ghcr.io/xtls/xray-core:26.3.27@"):
        raise ValueError("unreviewed Xray source/version")
    if "POSTGRES_IMAGE" in required and not result["POSTGRES_IMAGE"].startswith("postgres:17-alpine@"):
        raise ValueError("unreviewed postgres source/major")
    if result.get("CPU_SMOKE") != "approved-baseline-amd64":
        raise ValueError("target CPU image smoke gate not passed")
    if result.get("TELEGRAM_AUTH_ENABLED", "false") not in ("true", "false"):
        raise ValueError("explicit boolean auth flag required")
    port = result.get("API_HOST_PORT", "18080")
    if not port.isdigit() or not 1024 <= int(port) <= 65535:
        raise ValueError("invalid loopback API port")
    return result


def compose(project, *args):
    if project not in FILES:
        raise ValueError("project outside allowlist")
    return DOCKER + ["compose", "--project-name", project, "--env-file", str(ENV), "-f", str(INSTALL / FILES[project]), *args]


def run(cmd, **kwargs):
    # Do not inherit remote Docker contexts/COMPOSE overrides or secret env.
    subprocess.run(cmd, check=True, env=SAFE_ENV, stderr=subprocess.DEVNULL, **kwargs)


def secret_guard(name, uid):
    base = Path("/etc/chrona/secrets")
    # Parent stays root-owned0700; bind-mounted individual file readable by UID.
    path_guard(base, directory=True)
    if stat.S_IMODE(base.stat().st_mode) != 0o700:
        raise ValueError("secrets parent must be0700")
    p = base / name
    if p.is_symlink():
        raise ValueError("secret symlink")
    s = p.stat()
    if not stat.S_ISREG(s.st_mode) or stat.S_IMODE(s.st_mode) != 0o600 or s.st_uid != uid or s.st_nlink != 1 or not s.st_size:
        raise ValueError("secret owner/mode/type/size invalid")


def preflight(action):
    path_guard(INSTALL, directory=True)
    for filename in FILES.values():
        path_guard(INSTALL / filename)
    required = ("XRAY_IMAGE",) if action in ("xray-test", "deploy-proxy", "stop-proxy") else ("POSTGRES_IMAGE",) if action in ("deploy-db", "backup", "restore", "stop-restore") else ("XRAY_IMAGE", "POSTGRES_IMAGE", "BACKEND_IMAGE")
    metadata = parse_metadata(path_guard(ENV, private=True).read_text(), required)
    if action in ("deploy-app", "deploy-bot", "enable-telegram", "migrate", "preflight") and metadata.get("BACKEND_CONTRACT") != "approved-v1":
        raise ValueError("developer runtime contract not approved")
    if action in ("xray-test", "deploy-proxy", "preflight"):
        secret_guard("xray.json", 65532)
    if action in ("deploy-db", "deploy-app", "deploy-bot", "enable-telegram", "migrate", "restore", "preflight"):
        secret_guard("db-password", 70)
    if action in ("deploy-bot", "enable-telegram"):
        secret_guard("telegram-token", 10001)
    if action in ("deploy-bot", "enable-telegram") or metadata.get("TELEGRAM_AUTH_ENABLED") == "true":
        text = path_guard(Path("/etc/chrona/telegram.env"), private=True).read_text()
        if not re.fullmatch(r"BOT_USERNAME=[A-Za-z0-9_]{5,32}\n?", text):
            raise ValueError("verified private bot identity metadata required")
    if action in ("deploy-app", "deploy-bot", "enable-telegram", "migrate", "preflight"):
        secret_guard("app-db-password", 10001)
        # Same password, distinct0600 ownership for separate nonroot UIDs.
        a = Path("/etc/chrona/secrets/db-password").read_bytes()
        b = Path("/etc/chrona/secrets/app-db-password").read_bytes()
        if a != b:
            raise ValueError("DB password copies mismatch")
        data = Path("/var/lib/chrona/postgres")
        path_guard(data.parent, directory=True)
        if data.is_symlink() or not data.is_dir() or data.stat().st_uid != 70 or stat.S_IMODE(data.stat().st_mode) != 0o700:
            raise ValueError("preinitialized own data directory uid70/0700 required")
    return metadata


def init_data():
    # Only exact new directories. Never chmod/chown existing user data.
    path_guard(Path("/var/lib"), directory=True)
    base = Path("/var/lib/chrona")
    if base.exists() or base.is_symlink():
        raise ValueError("data base exists: inspect rather than modifying blindly")
    base.mkdir(mode=0o700)
    data = base / "postgres"
    data.mkdir(mode=0o700)
    os.chown(data, 70, 70)


def resource_guard():
    # No daemon access until explicit execution. Existing same-name objects must
    # be Compose-owned by exactly our project, never silently adopted.
    for kind, name, project in (
        ("network", "chrona-app", "chrona"),
        ("network", "chrona-api-ingress", "chrona"),
        ("network", "chrona-proxy-clients", "chrona-proxy"),
        ("network", "chrona-proxy-egress", "chrona-proxy"),
        ("network", "chrona-restore", "chrona-restore"),
        ("volume", "chrona-db-data", "chrona"),
        ("volume", "chrona-restore-data", "chrona-restore"),
    ):
        # Listing must succeed; cannot treat daemon failure as absent resource.
        listing = subprocess.run(DOCKER + [kind, "ls", "--format", "{{.Name}}"], check=True,
                                 stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=SAFE_ENV)
        if name not in listing.stdout.splitlines():
            continue
        found = subprocess.run(DOCKER + [kind, "inspect", "--format", '{{json .Labels}}', name],
                               check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=SAFE_ENV)
        labels = json.loads(found.stdout) or {}
        if labels.get("com.docker.compose.project") != project:
            raise ValueError("same-name resource not owned by approved project")
        if kind == "volume":
            found = subprocess.run(DOCKER + [kind, "inspect", "--format", '{{json .Options}}', name],
                                   check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=SAFE_ENV)
            device = "/var/lib/chrona/postgres" if name == "chrona-db-data" else "/var/lib/chrona/restore-postgres"
            if json.loads(found.stdout) != {"type": "none", "o": "bind", "device": device}:
                raise ValueError("own volume backing path differs from reviewed layout")
        elif name not in ("chrona-proxy-egress", "chrona-api-ingress"):
            found = subprocess.run(DOCKER + [kind, "inspect", "--format", '{{json .Internal}}', name],
                                   check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True, env=SAFE_ENV)
            if json.loads(found.stdout) is not True:
                raise ValueError("existing own network is not internal")


def backup():
    path_guard(BACKUPS, directory=True)
    if stat.S_IMODE(BACKUPS.stat().st_mode) != 0o700:
        raise ValueError("backup dir must be0700")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
    partial = BACKUPS / (stamp + ".dump.partial")
    final = BACKUPS / (stamp + ".dump")
    fd = os.open(partial, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "wb") as out:
        run(compose("chrona", "exec", "-T", "db", "pg_dump", "-U", "chrona", "-d", "chrona", "--format=custom", "--no-owner", "--no-acl"), stdout=out)
        out.flush()
        os.fsync(out.fileno())
    # Archive listing is a structural check, NOT a restore proof; never print it.
    with partial.open("rb") as inp:
        run(compose("chrona", "exec", "-T", "db", "pg_restore", "--list"), stdin=inp, stdout=subprocess.DEVNULL)
    os.link(partial, final)  # no overwrite; .partial retained on failed command
    partial.unlink()
    with final.open("rb") as inp:
        digest = hashlib.file_digest(inp, "sha256").hexdigest()
    fd = os.open(str(final) + ".sha256", os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write(digest + "\n")
    print("Created consistent own-DB backup + SHA256; no automatic retention deletion")


def restore(archive):
    p = Path(archive)
    if p.parent != BACKUPS or not re.fullmatch(r"[0-9TZ.]+\.dump", p.name):
        raise ValueError("backup filename/path outside allowlist")
    path_guard(p, private=True)
    digest_file = path_guard(Path(str(p) + ".sha256"), private=True)
    with p.open("rb") as inp:
        digest = hashlib.file_digest(inp, "sha256").hexdigest()
    if digest_file.read_text().strip() != digest:
        raise ValueError("backup checksum mismatch")
    # Refuse any pre-existing restore data, including previous tests. No --clean.
    result = subprocess.run(DOCKER + ["volume", "inspect", "chrona-restore-data"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, env=SAFE_ENV)
    if result.returncode == 0:
        raise ValueError("restore volume already exists; preserve and review manually")
    # Distinguish unavailable daemon from absent volume before starting anything.
    run(DOCKER + ["info", "--format", "{{.ServerVersion}}"], stdout=subprocess.DEVNULL)
    base = path_guard(Path("/var/lib/chrona"), directory=True)
    data = base / "restore-postgres"
    if data.exists() or data.is_symlink():
        raise ValueError("restore data already exists: preserve and review manually")
    data.mkdir(mode=0o700)
    os.chown(data, 70, 70)
    run(compose("chrona-restore", "up", "-d", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "120", "db"), stdout=subprocess.DEVNULL)
    try:
        with p.open("rb") as inp:
            run(compose("chrona-restore", "exec", "-T", "db", "pg_restore", "-U", "chrona", "-d", "chrona", "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl"), stdin=inp, stdout=subprocess.DEVNULL)
        run(compose("chrona-restore", "exec", "-T", "db", "psql", "-U", "chrona", "-d", "chrona", "-v", "ON_ERROR_STOP=1", "-c", "SELECT 1"), stdout=subprocess.DEVNULL)
        print("Isolated SQL restore passed; application invariants still require developer tests")
    finally:
        run(compose("chrona-restore", "stop", "db"), stdout=subprocess.DEVNULL)
        # Deliberately retain all volumes and backup for inspection/recovery.


def execute(action, archive):
    if action == "init-data":
        init_data()
        return
    preflight(action)
    resource_guard()
    if action == "preflight":
        for project in FILES:
            run(compose(project, "config", "--quiet"), stdout=subprocess.DEVNULL)
    elif action == "xray-test":
        run(compose("chrona-proxy", "run", "--rm", "--no-deps", "--pull", "never", "xray-proxy", "run", "-test", "-c", "/run/private/xray.json"), stdout=subprocess.DEVNULL)
    elif action in ("deploy-proxy", "deploy-db", "deploy-app", "deploy-bot"):
        project = "chrona-proxy" if action == "deploy-proxy" else "chrona"
        run(compose(project, "config", "--quiet"), stdout=subprocess.DEVNULL)
        profiles = ["--profile", "api"] if action == "deploy-app" else ["--profile", "bot"] if action == "deploy-bot" else []
        services = ["db"] if action == "deploy-db" else ["api"] if action == "deploy-app" else ["bot"] if action == "deploy-bot" else ["xray-proxy"]
        run(compose(project, *profiles, "up", "-d", "--no-build", "--pull", "never", *services), stdout=subprocess.DEVNULL)
        print("Start requested; NOT a readiness/acceptance claim")
    elif action == "migrate":
        run(compose("chrona", "--profile", "maintenance", "run", "-T", "--rm", "--no-deps", "--pull", "never", "migrate"), stdout=subprocess.DEVNULL)
    elif action == "enable-telegram":
        path_guard(BACKUPS, directory=True)
        marker = json.loads(path_guard(Path("/etc/chrona/bot-identity-verified.json"), private=True).read_text())
        import time
        if not marker.get("get_me") or not marker.get("webhook_empty") or marker.get("pending_updates") != 0 or not 0 <= time.time() - marker.get("checked_unix", 0) < 600:
            raise ValueError("fresh getMe/webhook/no-pending verification required")
        workers = subprocess.run(DOCKER + ["ps", "--filter", "label=com.docker.compose.project=chrona", "--filter", "label=com.docker.compose.service=bot", "--format", "{{.Names}}"], check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=SAFE_ENV)
        if workers.stdout.strip():
            raise ValueError("existing worker: review rather than starting a second poller")
        query = "SELECT (lease_holder IS NULL OR lease_until_ms < extract(epoch from clock_timestamp())*1000) AND NOT EXISTS(SELECT 1 FROM bot_inbox WHERE processed=FALSE) AND NOT EXISTS(SELECT 1 FROM bot_outbox WHERE delivered=FALSE AND discarded=FALSE) FROM bot_state WHERE id=1;"
        state = subprocess.run(compose("chrona", "exec", "-T", "db", "psql", "-U", "chrona", "-d", "chrona", "-Atc", query), check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, env=SAFE_ENV)
        if state.stdout.strip() != b"t":
            raise ValueError("active lease/inbox/outbox requires review; no queued messages sent automatically")
        original = path_guard(ENV, private=True).read_bytes()
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        backup_path = BACKUPS / ("runtime-pre-telegram-" + stamp + ".env")
        fd = os.open(backup_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as out:
            out.write(original)
        values = parse_metadata(original.decode())
        values["TELEGRAM_AUTH_ENABLED"] = "true"
        def replace_metadata(data):
            temporary = ENV.with_name("runtime.env.stage-" + stamp)
            fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with os.fdopen(fd, "wb") as out:
                out.write(data)
                out.flush()
                os.fsync(out.fileno())
            os.replace(temporary, ENV)
        replace_metadata("".join(k + "=" + v + "\n" for k, v in values.items()).encode())
        try:
            # Existing API stays auth-disabled until real worker polling is ready.
            run(compose("chrona", "--profile", "bot", "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "bot"), stdout=subprocess.DEVNULL)
            run(compose("chrona", "--profile", "api", "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "api"), stdout=subprocess.DEVNULL)
        except subprocess.SubprocessError:
            replace_metadata(original)
            run(compose("chrona", "--profile", "bot", "stop", "bot"), stdout=subprocess.DEVNULL)
            # Restore running API's previous auth-disabled environment too.
            run(compose("chrona", "--profile", "api", "up", "-d", "--no-deps", "--no-build", "--pull", "never", "api"), stdout=subprocess.DEVNULL)
            raise
        print("Worker readiness and API enabled; owner confirmation/login E2E still required")
    elif action == "disable-telegram":
        # Fail closed without deleting pending inbox/outbox/offset or webhook.
        path_guard(BACKUPS, directory=True)
        current = path_guard(ENV, private=True).read_bytes()
        values = parse_metadata(current.decode())
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S.%fZ")
        fd = os.open(BACKUPS / ("runtime-pre-disable-telegram-" + stamp + ".env"), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "wb") as out:
            out.write(current)
        run(compose("chrona", "--profile", "bot", "stop", "bot"), stdout=subprocess.DEVNULL)
        values["TELEGRAM_AUTH_ENABLED"] = "false"
        temporary = ENV.with_name("runtime.env.disable-" + stamp)
        fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as out:
            out.write("".join(k + "=" + v + "\n" for k, v in values.items()))
            out.flush()
            os.fsync(out.fileno())
        os.replace(temporary, ENV)
        run(compose("chrona", "--profile", "api", "up", "-d", "--no-deps", "--no-build", "--pull", "never", "--wait", "--wait-timeout", "180", "api"), stdout=subprocess.DEVNULL)
        print("Own worker stopped/API auth disabled; SQL offset/inbox/outbox retained")
    elif action in ("stop-app", "stop-restore"):
        run(compose("chrona" if action == "stop-app" else "chrona-restore", "--profile", "*", "stop"), stdout=subprocess.DEVNULL)
    elif action == "stop-proxy":
        # Manual consumer review gate, never stop reusable proxy blindly.
        raise ValueError("proxy stop blocked: explicit current consumer review required; use runbook")
    elif action == "backup":
        backup()
    elif action == "restore":
        restore(archive)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=ACTIONS)
    parser.add_argument("--archive", default="", help="restore only: own backup absolute path")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--approved", action="store_true", help="NOT permission itself: record approval before using")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: " + args.action + "; allowlisted own projects only; no private reads, daemon calls or writes")
        return 0
    if not args.approved:
        parser.error("--execute requires prior explicit scope approval and --approved")
    if os.geteuid() != 0:
        parser.error("execution requires approved privileged operator; script never invokes sudo")
    try:
        execute(args.action, args.archive)
    except (OSError, ValueError, subprocess.SubprocessError):
        print("FAILED: guarded operation failed; sensitive subprocess details suppressed", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""After owner installs NEW token: verify getMe through private SOCKS, store real username privately.

Default dry-run; never prints token/Telegram response/username. Does not enable auth or start bot.
"""
import argparse
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import stat
import struct
import subprocess

from probe import exact

BASE = Path("/etc/chrona")
DOCKER = ["/usr/bin/docker", "--host", "unix:///var/run/docker.sock"]


def token_path():
    file = BASE / "secrets/telegram-token"
    for parent in (BASE, BASE / "secrets"):
        if parent.is_symlink() or parent.stat().st_uid != 0 or stat.S_IMODE(parent.stat().st_mode) != 0o700:
            raise ValueError("unsafe private parent")
    if file.is_symlink():
        raise ValueError("secret symlink")
    meta = file.stat()
    if not stat.S_ISREG(meta.st_mode) or meta.st_nlink != 1 or meta.st_uid != 10001 or stat.S_IMODE(meta.st_mode) != 0o600 or not 1 <= meta.st_size <= 4096:
        raise ValueError("token owner/type/mode invalid")
    return file


def identify(proxy_ip):
    if not ipaddress.ip_address(proxy_ip).is_private:
        raise ValueError("private proxy metadata IP required")
    token = token_path().read_text().strip()
    if not re.fullmatch(r"[0-9]{5,20}:[A-Za-z0-9_-]{20,100}", token):
        raise ValueError("token format invalid")
    domain = b"api.telegram.org"
    with socket.create_connection((proxy_ip, 10808), timeout=10) as sock:
        sock.sendall(b"\x05\x01\x00")
        if exact(sock, 2) != b"\x05\x00":
            raise ValueError("SOCKS authentication failed")
        sock.sendall(b"\x05\x01\x00\x03" + bytes([len(domain)]) + domain + struct.pack("!H", 443))
        head = exact(sock, 4)
        if head[:3] != b"\x05\x00\x00":
            raise ValueError("SOCKS failed")
        length = 4 if head[3] == 1 else 16 if head[3] == 4 else exact(sock, 1)[0] if head[3] == 3 else 0
        if not length:
            raise ValueError("SOCKS address type invalid")
        exact(sock, length + 2)
        with ssl.create_default_context().wrap_socket(sock, server_hostname=domain.decode()) as tls:
            tls.sendall(("GET /bot" + token + "/getMe HTTP/1.1\r\nHost: api.telegram.org\r\nConnection: close\r\n\r\n").encode())
            response = http.client.HTTPResponse(tls)
            response.begin()
            if response.status != 200:
                raise ValueError("getMe failed")
            body = response.read(65537)
    del token
    if len(body) > 65536:
        raise ValueError("response bound exceeded")
    data = json.loads(body)
    user = data.get("result", {})
    username = user.get("username", "")
    if data.get("ok") is not True or user.get("is_bot") is not True or not isinstance(user.get("id"), int) or not re.fullmatch(r"[A-Za-z0-9_]{5,32}", username):
        raise ValueError("invalid verified bot identity")
    file = BASE / "telegram.env"
    if file.exists() or file.is_symlink():
        raise ValueError("existing identity metadata: backup/rotation review required")
    fd = os.open(file, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as out:
        out.write("BOT_USERNAME=" + username + "\n")
        out.flush()
        os.fsync(out.fileno())


def docker(*args):
    return subprocess.check_output(DOCKER + list(args), stderr=subprocess.DEVNULL)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--inside-net", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--proxy-ip", help=argparse.SUPPRESS)
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: after owner token install, verify private getMe then create private identity metadata; no secret reads/network")
        return 0
    if os.geteuid() != 0:
        parser.error("approved root operator required")
    name = "chrona-bot-identity-check"
    created = False
    try:
        token_path()  # Fail before any container/network action if token absent.
        if args.inside_net:
            identify(args.proxy_ip)
            print("BOT_IDENTITY_VERIFIED_PRIVATE_METADATA_CREATED")
            return 0
        if name in docker("ps", "-a", "--format", "{{.Names}}").decode().splitlines():
            raise ValueError("own check consumer already exists")
        pins = json.loads(Path("/opt/chrona/infra/verified-images-2026-10-18.json").read_text())
        image = "eclipse-temurin:17-jre-jammy@" + pins["jre"]["amd64_digest"]
        docker("run", "-d", "--pull", "never", "--name", name, "--label", "chrona.identity-check=true", "--network", "chrona-proxy-clients", "--read-only", "--user", "10001:10001", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "--memory", "64m", "--cpus", "0.25", "--entrypoint", "/bin/sleep", image, "120")
        created = True
        pid = docker("inspect", "--format", "{{.State.Pid}}", name).decode().strip()
        proxy = docker("inspect", "--format", '{{(index .NetworkSettings.Networks "chrona-proxy-clients").IPAddress}}', "chrona-proxy-xray-proxy-1").decode().strip()
        subprocess.run(["/usr/bin/nsenter", "--target", pid, "--net", "--", "/usr/bin/python3", "/opt/chrona/infra/scripts/configure_bot.py", "--execute", "--inside-net", "--proxy-ip", proxy], check=True, stderr=subprocess.DEVNULL, timeout=45)
        return 0
    except (OSError, ValueError, TypeError, http.client.HTTPException, subprocess.SubprocessError):
        print("BOT_IDENTITY_NOT_CONFIGURED (details suppressed; no auth enabled/worker started)")
        return 1
    finally:
        if created:
            try:
                if docker("inspect", "--format", '{{index .Config.Labels "chrona.identity-check"}}', name).decode().strip() == "true":
                    docker("rm", "-f", name)
            except subprocess.SubprocessError:
                print("OWN_IDENTITY_CHECK_CONTAINER_CLEANUP_PENDING")


if __name__ == "__main__":
    raise SystemExit(main())

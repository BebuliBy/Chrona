#!/usr/bin/env python3
"""Private file -> isolated Xray config. No secret values on stdout/stderr."""
import argparse
import copy
import json
import os
from pathlib import Path
import stat


def guarded_path(path):
    p = Path(path)
    if not p.is_absolute() or ".." in p.parts:
        raise ValueError("absolute normalized path required")
    for part in [p, *p.parents]:
        if part.is_symlink():
            raise ValueError("symlink forbidden")
    return p


def read_private(path):
    p = guarded_path(path)
    fd = os.open(p, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "r") as stream:
        s = os.fstat(stream.fileno())
        if not stat.S_ISREG(s.st_mode) or stat.S_IMODE(s.st_mode) != 0o600:
            raise ValueError("private input must be regular 0600")
        if s.st_uid != os.geteuid() or s.st_size > 65536 or s.st_nlink != 1:
            raise ValueError("private input ownership/size/link count invalid")
        parent = p.parent.stat()
        if stat.S_IMODE(parent.st_mode) != 0o700 or parent.st_uid != os.geteuid():
            raise ValueError("input parent must be owner-only 0700")
        return json.load(stream)


def isolated_config(source):
    # Accept one outbound, or a full config with exactly-selected outbounds[0].
    if not isinstance(source, dict):
        raise ValueError("JSON object required")
    outbound = source["outbounds"][0] if "outbounds" in source else source
    if not isinstance(outbound, dict):
        raise ValueError("outbound object required")
    if outbound.get("protocol") != "vless":
        raise ValueError("only VLESS client supported")
    transport = outbound.get("streamSettings", {})
    if not isinstance(transport, dict):
        raise ValueError("transport object required")
    if transport.get("network") not in ("tcp", "raw") or transport.get("security") != "reality":
        raise ValueError("only TCP/REALITY supported")
    reality = transport.get("realitySettings", {})
    if not isinstance(reality, dict):
        raise ValueError("REALITY object required")
    if "privateKey" in reality:
        raise ValueError("server private key forbidden")
    if any(k in outbound for k in ("proxySettings", "sendThrough", "mux")):
        raise ValueError("dependent outbound or mux requires explicit review")
    if "sockopt" in transport:
        raise ValueError("host socket routing settings require explicit review")
    if "REPLACE_PRIVATELY" in json.dumps(outbound):
        raise ValueError("placeholder input forbidden")
    # Keep only client protocol fields, NOT laptop routing/DNS/inbounds/logging.
    selected = {k: copy.deepcopy(outbound[k]) for k in ("protocol", "settings", "streamSettings")}
    selected["tag"] = "vpn"
    return {
        "log": {"loglevel": "none", "access": "none", "dnsLog": False},
        "inbounds": [{"tag": "consumers", "listen": "0.0.0.0", "port": 10808,
                      "protocol": "socks", "settings": {"auth": "noauth", "udp": False},
                      "sniffing": {"enabled": False}}],
        "outbounds": [selected],
        "routing": {"rules": [{"type": "field", "inboundTag": ["consumers"], "outboundTag": "vpn"}]},
    }


def write_new_private(path, config):
    p = guarded_path(path)
    parent = p.parent.stat()
    if stat.S_IMODE(parent.st_mode) != 0o700 or parent.st_uid != os.geteuid():
        raise ValueError("output parent must be owner-only 0700")
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w") as stream:
        json.dump(config, stream, separators=(",", ":"))
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, help="private 0600 JSON; not read in dry-run")
    parser.add_argument("--output", required=True, help="new private output, never overwritten")
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--approved", action="store_true", help="separate layout/secret action approval obtained")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: would read private outbound and create isolated 0600 config; no files read/written")
        return 0
    if not args.approved:
        parser.error("execution needs explicit prior approval and --approved")
    try:
        write_new_private(args.output, isolated_config(read_private(args.input)))
    except (OSError, ValueError, KeyError, IndexError, TypeError):
        print("FAILED: private config validation or exclusive write failed (details suppressed)")
        return 1
    print("Created private isolated config; contents suppressed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

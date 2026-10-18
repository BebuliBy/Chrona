#!/usr/bin/env python3
"""Owner-only target-local token entry via hidden terminal input. Never sends to Telegram."""
import argparse
import getpass
import os
from pathlib import Path
import re
import stat


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: owner hidden prompt -> new private telegram-token file; no read/write")
        return 0
    if os.geteuid() != 0 or not os.isatty(0):
        parser.error("approved root operator and interactive terminal required")
    base = Path("/etc/chrona/secrets")
    for path in (base, *base.parents):
        if path.is_symlink() or path.stat().st_uid != 0 or path.stat().st_mode & 0o022:
            parser.error("unsafe secret path")
    if stat.S_IMODE(base.stat().st_mode) != 0o700:
        parser.error("secrets parent must be0700")
    # /dev/tty-based getpass prevents stdin/terminal echo fallback.
    token = getpass.getpass("New BotFather token (hidden, never paste into chat): ")
    if not re.fullmatch(r"[0-9]{5,20}:[A-Za-z0-9_-]{20,100}", token):
        parser.error("token format invalid (value suppressed)")
    target = base / "telegram-token"
    try:
        fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        with os.fdopen(fd, "w") as out:
            os.fchown(out.fileno(), 10001, 10001)
            out.write(token + "\n")
            out.flush()
            os.fsync(out.fileno())
    except OSError:
        print("FAILED: exclusive token install; existing token never overwritten")
        return 1
    del token
    print("Token installed privately. Bot remains off until explicit verified username/profile startup.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

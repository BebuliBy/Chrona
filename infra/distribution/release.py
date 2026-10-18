#!/usr/bin/env python3
"""Future approved release operator; dry-run default. No signing/key reads.

Verify frozen APK using existing SDK public-metadata tools, then authenticated
stdin transport. Actual origin/hostname never required or printed.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import stat
import subprocess
import sys
import tempfile


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--execute", action="store_true")
    p.add_argument("--approved", action="store_true")
    p.add_argument("--password-stdin", action="store_true")
    p.add_argument("--ssh-alias")
    p.add_argument("--apk", type=Path)
    p.add_argument("--aapt", type=Path)
    p.add_argument("--apksigner", type=Path)
    p.add_argument("--expected-package")
    p.add_argument("--expected-cert-sha256")
    p.add_argument("--expected-sha256")
    p.add_argument("--expected-version-code", type=int)
    p.add_argument("--expected-version-name")
    p.add_argument("--expected-min-sdk", type=int)
    args = p.parse_args()
    if not args.execute:
        print("DRY-RUN release: no SDK/artifact/auth reads, SSH or publication")
        return 0
    if not args.approved or not args.password_stdin or not args.ssh_alias or not re.fullmatch(r"[A-Za-z0-9_-]+", args.ssh_alias):
        p.error("explicit scope approval, confirmed SSH alias and private stdin required")
    if not args.expected_cert_sha256 or not re.fullmatch(r"[a-f0-9]{64}", args.expected_cert_sha256) or not args.expected_package:
        p.error("approved public package/certificate pin required; no signing key")
    if not args.expected_sha256 or not re.fullmatch(r"[a-f0-9]{64}", args.expected_sha256) or not args.expected_version_code:
        p.error("approved immutable artifact SHA256/version pin required")
    try:
        if any(value is None or not value.is_file() or value.is_symlink() for value in (args.apk, args.aapt, args.apksigner)):
            raise ValueError("PUBLIC_ARTIFACT_OR_EXISTING_SDK_TOOL_INVALID")
        # Frozen public artifact, not a keystore; never include it in repository.
        with tempfile.TemporaryDirectory(prefix="chrona-apk-release-", dir="/tmp/opencode") as folder:
            frozen = Path(folder) / "approved.apk"
            fd = os.open(frozen, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            source_fd = os.open(args.apk, os.O_RDONLY | os.O_NOFOLLOW)
            with os.fdopen(source_fd, "rb") as source, os.fdopen(fd, "wb") as out:
                info = os.fstat(source.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or not 0 < info.st_size <= 268435456:
                    raise ValueError("APK_SIZE_OR_FILE_TYPE_BOUND")
                copied = 0
                while True:
                    block = source.read(1048576)
                    if not block:
                        break
                    copied += len(block)
                    if copied > 268435456:
                        raise ValueError("APK_GREW_BEYOND_LIMIT")
                    out.write(block)
                out.flush()
                os.fsync(out.fileno())
                os.fchmod(out.fileno(), 0o444)
            size = frozen.stat().st_size
            if not 0 < size <= 268435456:
                raise ValueError("APK_SIZE_BOUND")
            cert = subprocess.check_output([str(args.apksigner), "verify", "--print-certs", str(frozen)], stderr=subprocess.DEVNULL, timeout=60).decode()
            digests = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", cert)
            if len(digests) != 1 or digests[0].lower() != args.expected_cert_sha256:
                raise ValueError("PUBLIC_SIGNER_IDENTITY_MISMATCH")
            badging = subprocess.check_output([str(args.aapt), "dump", "badging", str(frozen)], stderr=subprocess.DEVNULL, timeout=30).decode()
            info = re.search(r"^package: name='([^']+)' versionCode='([1-9][0-9]*)' versionName='([^']*)'", badging, re.M)
            sdk = re.search(r"^sdkVersion:'([1-9][0-9]*)'", badging, re.M)
            if not info or not sdk or info[1] != args.expected_package:
                raise ValueError("PUBLIC_APK_PACKAGE_METADATA_MISMATCH")
            with frozen.open("rb") as source:
                sha = hashlib.file_digest(source, "sha256").hexdigest()
            if sha != args.expected_sha256 or int(info[2]) != args.expected_version_code or args.expected_version_name is not None and info[3] != args.expected_version_name or args.expected_min_sdk is not None and int(sdk[1]) != args.expected_min_sdk:
                raise ValueError("APPROVED_ARTIFACT_VERSION_IDENTITY_MISMATCH")
            meta = {"schemaVersion": 1, "versionCode": int(info[2]), "versionName": info[3], "apkPath": "releases/Chrona-" + info[2] + ".apk", "sha256": sha, "sizeBytes": size, "packageName": info[1], "minSdk": int(sdk[1]), "signingCertificateSha256": digests[0].lower(), "mandatory": True}
            auth = sys.stdin.buffer.readline(1025).rstrip(b"\r\n")
            if not auth or len(auth) > 1024:
                raise ValueError("PRIVATE_AUTH_INPUT_UNAVAILABLE")
            # Framing safely discards optional unconsumed sudo input before metadata.
            remote = "import sys,json;sys.path.insert(0,'/opt/chrona-distribution');from publish_store import publish; s=sys.stdin.buffer; marker=b'CH_APK_RELEASE_V1\\n'; found=False\nfor _ in range(3):\n line=s.readline(2048)\n if line==marker: found=True;break\nif not found:raise SystemExit('RELEASE_FRAME_REJECTED')\nmeta=json.loads(s.readline(16385));publish(s,meta)"
            # Suppress private exception/tool details on target.
            wrapped = "try:\n" + "\n".join(" " + line for line in remote.splitlines()) + "\nexcept BaseException:\n print('RELEASE_NOT_PUBLISHED_DETAILS_SUPPRESSED');raise SystemExit(1)\n"
            ssh = ["/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "ConnectTimeout=7", args.ssh_alias]
            payload = auth + b"\nCH_APK_RELEASE_V1\n" + json.dumps(meta, separators=(",", ":")).encode() + b"\n" + frozen.read_bytes()
            del auth
            result = subprocess.run(ssh + ["sudo -S -p '' -- python3 -c " + shlex.quote(wrapped)], input=payload, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=600)
            del payload
            sys.stdout.buffer.write(result.stdout)
            return result.returncode
    except (OSError, ValueError, subprocess.SubprocessError):
        print("RELEASE_NOT_PUBLISHED_VERIFICATION_OR_TRANSPORT_FAILED_DETAILS_SUPPRESSED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

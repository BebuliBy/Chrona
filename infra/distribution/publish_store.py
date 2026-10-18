#!/usr/bin/env python3
"""Root-only publication store; no HTTP uploads, SDK/keys, SSH or app SQL imports.

Called only by the approved SSH release operator after local SDK verification of
the frozen APK. Hash/size and server-pinned public identity bind that attestation.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile

BASE = Path("/srv/chrona-distribution")
DATA = BASE / "published"
POLICY = Path("/etc/chrona-distribution/policy.json")
BACKUPS = Path("/var/backups/chrona-distribution")
KEYS = {"schemaVersion", "versionCode", "versionName", "apkPath", "sha256", "sizeBytes", "packageName", "minSdk", "signingCertificateSha256", "mandatory"}


def guarded(path, mode=None):
    path = Path(path)
    for item in [path, *path.parents]:
        info = item.stat()
        # This host's /srv belongs to the already-authorized sudo administrator.
        # Exception ONLY for this ancestor, never component dirs/files or /etc.
        trusted_srv = item == Path("/srv") and str(info.st_uid) == os.environ.get("SUDO_UID")
        if item.is_symlink() or info.st_uid != 0 and not trusted_srv or info.st_mode & 0o022:
            raise ValueError("UNSAFE_PATH_OWNER_OR_LINK")
    s = path.stat()
    if mode is not None and stat.S_IMODE(s.st_mode) != mode:
        raise ValueError("UNSAFE_PRIVATE_MODE")
    if path.is_file() and s.st_nlink != 1:
        raise ValueError("CONFIG_HARDLINK_REJECTED")
    return path


def fsync_dir(path):
    fd = os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def validate(m, policy):
    if not isinstance(m, dict) or set(m) != KEYS or type(m["schemaVersion"]) is not int or m["schemaVersion"] != 1 or m["mandatory"] is not True:
        raise ValueError("MANIFEST_SCHEMA_OR_REQUIRED_FLAG")
    for key in ("versionCode", "minSdk", "sizeBytes"):
        if type(m[key]) is not int or m[key] <= 0:
            raise ValueError("INVALID_PUBLIC_METADATA_NUMBER")
    if m["versionCode"] > 2147483647 or m["sizeBytes"] > policy["maximumSizeBytes"] or m["versionCode"] < policy["minimumVersionCode"]:
        raise ValueError("RELEASE_POLICY_BOUND")
    if m["apkPath"] != "releases/Chrona-" + str(m["versionCode"]) + ".apk":
        raise ValueError("IMMUTABLE_RELATIVE_APK_PATH_REQUIRED")
    if not isinstance(m["versionName"], str) or not 1 <= len(m["versionName"]) <= 128 or any(ord(c) < 32 for c in m["versionName"]):
        raise ValueError("INVALID_VERSION_NAME")
    if not re.fullmatch(r"[a-f0-9]{64}", m["sha256"]):
        raise ValueError("INVALID_APK_DIGEST")
    if not re.fullmatch(r"[a-f0-9]{64}", m["signingCertificateSha256"]) or m["signingCertificateSha256"] != policy["signingCertificateSha256"] or m["packageName"] != policy["packageName"]:
        raise ValueError("PACKAGE_OR_SIGNER_DIFFERS_FROM_PINNED_PUBLIC_IDENTITY")


def immutable_metadata(path, data):
    if path.exists() or path.is_symlink():
        if guarded(path).read_bytes() != data:
            raise ValueError("IMMUTABLE_RELEASE_METADATA_COLLISION")
        return
    fd, name = tempfile.mkstemp(prefix="release-meta-", dir=BASE / "staging")
    temp = Path(name)
    try:
        with os.fdopen(fd, "wb") as out:
            out.write(data)
            out.flush()
            os.fsync(out.fileno())
            os.fchmod(out.fileno(), 0o444)
        os.link(temp, path, follow_symlinks=False)  # exclusive, never overwrite
        temp.unlink()
        fsync_dir(path.parent)
    finally:
        if temp.exists():
            temp.unlink()


def publish(stream, manifest):
    if os.geteuid() != 0:
        raise ValueError("ROOT_RELEASE_OPERATOR_REQUIRED")
    guarded(BASE, 0o700)
    guarded(BASE / "staging", 0o700)
    guarded(DATA, 0o755)
    guarded(DATA / "releases", 0o755)
    guarded(BACKUPS, 0o700)
    policy = json.loads(guarded(POLICY, 0o600).read_text())
    validate(manifest, policy)
    fd = os.open(BASE / ".publish.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "w"):
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        latest = DATA / "latest.json"
        previous = guarded(latest).read_bytes() if latest.exists() else None
        if previous:
            old = json.loads(previous)
            validate(old, policy)
            if manifest["versionCode"] < old["versionCode"]:
                raise ValueError("PUBLISHED_VERSION_DOWNGRADE_REJECTED")
            if manifest["versionCode"] == old["versionCode"] and manifest != old:
                raise ValueError("PUBLISHED_VERSION_REUSE_DIFFERS")
        stage_fd, name = tempfile.mkstemp(prefix="apk-", dir=BASE / "staging")
        stage = Path(name)
        try:
            digest = hashlib.sha256()
            total = 0
            with os.fdopen(stage_fd, "wb") as out:
                while total < manifest["sizeBytes"]:
                    block = stream.read(min(1048576, manifest["sizeBytes"] - total))
                    if not block:
                        raise ValueError("TRUNCATED_ARTIFACT")
                    total += len(block)
                    digest.update(block)
                    out.write(block)
                if stream.read(1):
                    raise ValueError("EXCESS_ARTIFACT_BYTES")
                out.flush()
                os.fsync(out.fileno())
                os.fchmod(out.fileno(), 0o444)
            if digest.hexdigest() != manifest["sha256"]:
                raise ValueError("TRANSFER_DIGEST_MISMATCH")
            target = DATA / manifest["apkPath"]
            if target.exists() or target.is_symlink():
                with guarded(target).open("rb") as inp:
                    same = target.stat().st_size == total and hashlib.file_digest(inp, "sha256").hexdigest() == manifest["sha256"]
                if not same:
                    raise ValueError("IMMUTABLE_APK_VERSION_DIFFERENT_BYTES")
            else:
                os.link(stage, target, follow_symlinks=False)
                stage.unlink()
                fsync_dir(target.parent)
            public = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode() + b"\n"
            immutable_metadata(target.with_suffix(".json"), public)
            if previous:
                import time
                backup = BACKUPS / ("latest-before-" + str(time.time_ns()) + ".json")
                save = os.open(backup, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                with os.fdopen(save, "wb") as out:
                    out.write(previous)
                    out.flush()
                    os.fsync(out.fileno())
            # LAST: atomic manifest switch; immutable APK and metadata already durable.
            meta_fd, name = tempfile.mkstemp(prefix="latest-", dir=BASE / "staging")
            staged_meta = Path(name)
            with os.fdopen(meta_fd, "wb") as out:
                out.write(public)
                out.flush()
                os.fsync(out.fileno())
                os.fchmod(out.fileno(), 0o444)
            os.replace(staged_meta, latest)
            fsync_dir(DATA)
            print("IMMUTABLE_APK_VERIFIED_MANIFEST_PUBLISHED_LAST_NO_PRIVATE_ORIGIN_OUTPUT")
        finally:
            if stage.exists():
                stage.unlink()

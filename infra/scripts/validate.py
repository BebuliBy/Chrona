#!/usr/bin/env python3
"""Local syntax/security tests only. No install/build/pull/start/SSH or private reads."""
import ast
import os
from pathlib import Path
import shutil
import subprocess
import sys


def main():
    root = Path(__file__).resolve().parents[1]
    if len(sys.argv) > 1:
        print("Usage: python3 -B infra/scripts/validate.py (local tests only, no runtime execution)")
        return 0 if sys.argv[1:] == ["--help"] else 2
    for script in root.rglob("*.py"):
        ast.parse(script.read_text(), filename=str(script))
    env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": os.environ.get("HOME", "/tmp/opencode"), "PYTHONDONTWRITEBYTECODE": "1"}
    tests = subprocess.run([sys.executable, "-B", "-m", "unittest", "discover", "-s", str(root / "tests"), "-v"], env=env)
    if tests.returncode:
        return tests.returncode
    if not shutil.which("docker") or subprocess.run(["docker", "compose", "version"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
        print("SKIP: Docker Compose plugin unavailable; PyYAML security tests are NOT Compose validation")
        return 0
    # Deliberately invalid registry fixtures: never stored as actual deployment pins.
    env.update({"XRAY_IMAGE": "fixture.invalid/xray:syntax-only", "POSTGRES_IMAGE": "fixture.invalid/postgres:syntax-only", "BACKEND_IMAGE": "fixture.invalid/backend:syntax-only"})
    for name in ("proxy", "app", "restore"):
        result = subprocess.run(["docker", "compose", "--env-file", "/dev/null", "-f", str(root / ("compose." + name + ".yml")), "config", "--quiet"], env=env)
        if result.returncode:
            return result.returncode
    print("PASS: Compose syntax only, synthetic env; no daemon/pull/start used")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

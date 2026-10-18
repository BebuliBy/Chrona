#!/usr/bin/env python3
"""Approved selective SSH/sudo baseline. Credential input via hidden owner TTY or external0600 file.

Never accepts password in argv/env, never writes credential locally/remotely.
One-use file is unlinked immediately after secure read, before authentication.
"""
import argparse
import getpass
import os
from pathlib import Path
import shlex
import stat
import subprocess
import sys

BASELINE = r'''
import json,subprocess,os,stat
def get(args):
 return subprocess.check_output(args,stderr=subprocess.DEVNULL,text=True).strip()
print("ENGINE="+get(["docker","version","--format","{{.Server.Version}} "]))
print("COMPOSE="+get(["docker","compose","version"]))
names=get(["docker","container","ls","-a","--format","{{.Names}}"]).splitlines()
for name in names:
 status=get(["docker","inspect","--format",'{{.State.Status}}',name])
 health=get(["docker","inspect","--format",'{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}',name])
 labels={k:get(["docker","inspect","--format",'{{index .Config.Labels "'+k+'"}}',name]) for k in ("com.docker.compose.project","com.docker.compose.project.working_dir","com.docker.compose.project.config_files")}
 mounts=json.loads(get(["docker","inspect","--format",'{{json .Mounts}}',name]))
 print(json.dumps({"container":name,"status":status,"health":health,"image":get(["docker","inspect","--format","{{.Config.Image}}",name]),"restart":get(["docker","inspect","--format","{{.HostConfig.RestartPolicy.Name}}",name]),"mounts":[{"source":m.get("Source"),"target":m.get("Destination"),"rw":m.get("RW")} for m in mounts],"compose_metadata":{k:v for k,v in labels.items() if k in ("com.docker.compose.project","com.docker.compose.project.working_dir","com.docker.compose.project.config_files")}},sort_keys=True))
for name in get(["docker","network","ls","--format","{{.Name}}"]).splitlines():
 print("NETWORK="+get(["docker","network","inspect","--format",'{{.Name}} internal={{.Internal}} {{range .IPAM.Config}}{{.Subnet}} {{end}}',name]))
for path in ("/opt/chrona","/opt/chrona-proxy","/etc/chrona","/var/lib/chrona","/var/backups/chrona"):
 print(json.dumps({"path":path,"exists":os.path.lexists(path),"symlink":os.path.islink(path)}))
print("SERVICES="+get(["systemctl","is-active","docker","ssh","tailscaled","smbd","spbpu-calendar"]).replace("\n",","))
print("SOCKETS="+get(["ss","-H","-lnt","sport = :10808 or sport = :18080"]))
'''


def credential(path):
    expected = Path("/tmp/opencode/chrona-auth/sudo-password")
    if Path(path) != expected or expected.parent.is_symlink():
        raise ValueError("external one-use credential path outside allowlist")
    parent = expected.parent.stat()
    if parent.st_uid != os.geteuid() or stat.S_IMODE(parent.st_mode) != 0o700:
        raise ValueError("credential parent must be owner0700")
    fd = os.open(expected, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(fd, "rb") as source:
        meta = os.fstat(source.fileno())
        if not stat.S_ISREG(meta.st_mode) or meta.st_uid != os.geteuid() or stat.S_IMODE(meta.st_mode) != 0o600 or meta.st_nlink != 1 or not 1 <= meta.st_size <= 1024:
            raise ValueError("credential file must be single-link owner0600")
        value = source.read().rstrip(b"\r\n")
    if b"\n" in value or not value:
        raise ValueError("invalid authentication input")
    expected.unlink()
    return value


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--credential-file", default="/tmp/opencode/chrona-auth/sudo-password")
    parser.add_argument("--prompt", action="store_true", help="owner terminal hidden prompt, no credential file")
    parser.add_argument("--password-stdin", action="store_true", help="already-authorized credential via private stdin, no file")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: selective server alias baseline; no credential read/SSH")
        return 0
    try:
        if args.password_stdin:
            secret = sys.stdin.buffer.readline(1025).rstrip(b"\r\n")
            if not secret or len(secret) > 1024 or b"\n" in secret:
                raise ValueError("invalid authentication input")
        elif args.prompt:
            if not os.isatty(0):
                raise ValueError("interactive owner terminal required")
            secret = getpass.getpass("Server sudo authentication (hidden): ").encode()
            if not secret or b"\n" in secret:
                raise ValueError("invalid authentication input")
        else:
            secret = credential(args.credential_file)
        command = "sudo -S -p '' -- python3 -c " + shlex.quote(BASELINE)
        result = subprocess.run(["/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "ConnectTimeout=7", "server", command], input=secret+b"\n", stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=60)
        del secret
        if result.returncode:
            print("AUTH_OR_BASELINE_FAILED (stderr suppressed)")
            return 1
        sys.stdout.buffer.write(result.stdout)
        return 0
    except (OSError, ValueError, subprocess.SubprocessError):
        print("SECURE_AUTH_INPUT_OR_BASELINE_UNAVAILABLE (details suppressed)")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

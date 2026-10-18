#!/usr/bin/env python3
"""Approved help87 worker-only rollout. Dry-run default, auth private stdin only.

No API/DB/proxy/gateway restart, message injection, inbox replay or raw Env/logs.
"""
import argparse
import hashlib
import io
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile

DIST = Path("/home/niki/android-tracker/backend/build/install/chrona-backend")
EXPECTED = "8ebccc3727c422fdff6c1bdcee33e38bab97435e41ae985e218f025d62643fef"

REMOTE = r'''
import base64,hashlib,http.client,io,json,os,pathlib,re,socket,stat,subprocess,sys,tarfile,time,zipfile
P=pathlib.Path
EXPECTED="8ebccc3727c422fdff6c1bdcee33e38bab97435e41ae985e218f025d62643fef"
OLD="sha256:4d820186b4f15068348e8c9a6b4d9c01099e99285fbd47434c41442c0c3b09b6"
ROOT=P("/opt/chrona/infra");ENV=P("/etc/chrona/runtime.env");COMPOSE=ROOT/"compose.app.yml"
def run(args,**kw):
 kw.setdefault("stdin",subprocess.DEVNULL)
 return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw).stdout
def docker(*args):return run(["/usr/bin/docker",*args])
def inspect(name,field):return docker("inspect","--format",field,name).decode().strip()
def compose(*args):return docker("compose","--project-name","chrona","--env-file",str(ENV),"-f",str(COMPOSE),"--profile","bot",*args)
def guard(path):
 path=P(path)
 for p in [path,*path.parents]:
  if p.is_symlink():raise RuntimeError("PATH_SYMLINK")
  if p.exists() and (p.stat().st_uid!=0 or p.stat().st_mode&0o022):raise RuntimeError("UNSAFE_PATH_OWNER")
 return path
def save(path,data,mode=0o600):
 fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
 with os.fdopen(fd,"wb") as out:out.write(data);out.flush();os.fsync(out.fileno())
def replace(path,data):
 guard(path);temp=path.with_name(path.name+".help87-"+str(time.time_ns()));save(temp,data,stat.S_IMODE(path.stat().st_mode));os.replace(temp,path)
def sql(query):return compose("exec","-T","db","psql","-U","chrona","-d","chrona","-At","-v","ON_ERROR_STOP=1","-c",query).decode().strip()
def state():
 rows=sql("SELECT next_offset,(lease_holder IS NULL OR lease_until_ms<extract(epoch from clock_timestamp())*1000),success_ms,(extract(epoch from clock_timestamp())*1000)::bigint FROM bot_state WHERE id=1; SELECT count(*),count(*) FILTER(WHERE processed) FROM bot_inbox; SELECT count(*),count(*) FILTER(WHERE delivered),count(*) FILTER(WHERE NOT delivered AND NOT discarded) FROM bot_outbox;").splitlines()
 first=rows[0].split("|")
 return {"offset":int(first[0]),"lease_free":first[1]=="t","success":int(first[2]),"now":int(first[3]),"inbox":list(map(int,rows[1].split("|"))),"outbox":list(map(int,rows[2].split("|")))}
def profile():
 keys=("SECRETS_PATH","DB_URL","DB_USER","TELEGRAM_AUTH_ENABLED","TELEGRAM_PROXY","BIND_ADDRESS","PORT","JAVA_OPTS")
 template='{{range .Config.Env}}'+''.join('{{if eq (index (split . "=") 0) "'+key+'"}}{{.}}{{println}}{{end}}' for key in keys)+'{{end}}'
 env=inspect("chrona-bot-1",template).splitlines()
 if len({line.partition('=')[0] for line in env})!=len(env):raise RuntimeError("DUPLICATE_PROFILE_ENV_KEYS")
 return {"selected_nonsecret_env":sorted(env),"user":inspect("chrona-bot-1","{{.Config.User}}"),"mounts":sorted(json.loads(inspect("chrona-bot-1","{{json .Mounts}}")),key=lambda m:m["Destination"]),"networks":sorted(json.loads(inspect("chrona-bot-1","{{json .NetworkSettings.Networks}}"))),"ports":inspect("chrona-bot-1","{{json .HostConfig.PortBindings}}"),"limits":{key:inspect("chrona-bot-1","{{json .HostConfig."+key+"}}") for key in ("ReadonlyRootfs","CapDrop","SecurityOpt","Memory","NanoCpus","PidsLimit","RestartPolicy")}}
class Local(http.client.HTTPConnection):
 def connect(self):self.sock=socket.socket(socket.AF_UNIX,socket.SOCK_STREAM);self.sock.settimeout(10);self.sock.connect("/run/tailscale/tailscaled.sock")
def serve_hash():
 c=Local("local-tailscaled.sock");c.request("GET","/localapi/v0/serve-config");r=c.getresponse();assert r.status==200;obj=json.loads(r.read());c.close();return hashlib.sha256(json.dumps(obj,sort_keys=True).encode()).hexdigest()
stage="runtime_preflight"
for path in (ROOT,ENV,COMPOSE,ROOT/"scripts/ops.py",P("/var/backups/chrona")):guard(path)
names=docker("ps","--filter","label=com.docker.compose.project=chrona","--filter","label=com.docker.compose.service=bot","--format","{{.Names}}").decode().splitlines()
if names!=["chrona-bot-1"]:raise RuntimeError("ONE_OLD_WORKER_REQUIRED")
if inspect("chrona-bot-1","{{.Image}}")!=OLD or inspect("chrona-api-1","{{.Image}}")!=OLD:raise RuntimeError("UNEXPECTED_PRIOR_IMAGE")
preserve={name:(inspect(name,"{{.Id}}"),inspect(name,"{{.Image}}")) for name in ("chrona-api-1","chrona-db-1","chrona-proxy-xray-proxy-1","nextcloud-app","nextcloud-db","nextcloud-redis","nextcloud-cron")}
if inspect("chrona-api-1","{{.State.Health.Status}}")!="healthy":raise RuntimeError("API_BASELINE_NOT_READY")
stage="serve_metadata";private_mapping=serve_hash()
stage="worker_profile";before_profile=profile()
stage="queue_metadata";initial=state()
stage="backup_and_release_stage"
stamp=str(time.time_ns());backup=P("/var/backups/chrona")/("worker-help87-"+stamp);backup.mkdir(mode=0o700)
original={"runtime.env":ENV.read_bytes(),"compose.app.yml":COMPOSE.read_bytes(),"ops.py":(ROOT/"scripts/ops.py").read_bytes()}
for name,data in original.items():save(backup/name,data)
save(backup/"image-metadata.json",json.dumps({"prior_worker_image":OLD,"api_image":OLD,"artifact_expected_sha256":EXPECTED,"offset_nonnegative":initial["offset"]>=0,"inbox_counts":initial["inbox"],"outbox_counts":initial["outbox"]}).encode())
with tarfile.open(backup/"previous-source.tar","w") as archive:archive.add(ROOT/"backend",arcname="backend")
os.chmod(backup/"previous-source.tar",0o600)
base=guard("/opt/chrona/worker-releases")
if not base.exists():base.mkdir(mode=0o755)
release=base/("help87-"+stamp);release.mkdir(mode=0o755)
with tarfile.open(fileobj=sys.stdin.buffer,mode="r|*") as archive:
 for member in archive:
  rel=P(member.name)
  if rel.is_absolute() or ".." in rel.parts or not member.isfile() or member.issym() or member.islnk():raise RuntimeError("UNSAFE_RELEASE_MEMBER")
  if not (str(rel) in ("Dockerfile",".dockerignore","distribution/bin/chrona-backend") or len(rel.parts)==3 and rel.parts[:2]==("distribution","lib") and rel.suffix==".jar"):raise RuntimeError("RELEASE_ALLOWLIST")
  target=release/rel;target.parent.mkdir(mode=0o755,parents=True,exist_ok=True);save(target,archive.extractfile(member).read(),0o755 if rel.name=="chrona-backend" else 0o644)
jar=release/"distribution/lib/chrona-backend-0.1.0.jar"
stage="artifact_identity"
if hashlib.sha256(jar.read_bytes()).hexdigest()!=EXPECTED:raise RuntimeError("RELEASE_ARTIFACT_SHA256_MISMATCH")
with zipfile.ZipFile(jar) as z:checksum=base64.urlsafe_b64encode(hashlib.sha256(z.read("db/V001__foundation.sql")).digest()).decode().rstrip("=")
stage="sql_checksum"
if sql("SELECT checksum FROM schema_migrations WHERE version=1")!=checksum:raise RuntimeError("SQL_MIGRATION_CHANGED")
pins=json.loads((ROOT/"verified-images-2026-10-18.json").read_text());jre="eclipse-temurin:17-jre-jammy@"+pins["jre"]["amd64_digest"]
stage="worker_image_build"
docker("build","--pull=false","--network","none","--build-arg","JVM_IMAGE="+jre,"-t","chrona-backend:worker-help87-20261018",str(release))
new=docker("image","inspect","--format","{{.Id}}","chrona-backend:worker-help87-20261018").decode().strip()
save(release/"release-metadata.json",json.dumps({"image":new,"app_jar_sha256":EXPECTED,"prior_worker_image":OLD,"sql_unchanged":True}).encode(),0o644)
print("ARTIFACT_SHA256_VERIFIED87_SQL_UNCHANGED_IMAGE_BUILT",flush=True)
edited=False;stopped=False
try:
 stage="configure_worker_image"
 text=original["compose.app.yml"].decode();needle="  bot:\n    <<: *runtime\n"
 if text.count(needle)!=1 or "BOT_IMAGE" in text:raise RuntimeError("WORKER_CONFIG_REVIEW_REQUIRED")
 text=text.replace(needle,needle+'    image: ${BOT_IMAGE:-${BACKEND_IMAGE:?verified backend image digest required}}\n')
 ops=original["ops.py"].decode();key='"BACKEND_IMAGE", "BACKEND_CONTRACT"';anchor='    if "XRAY_IMAGE" in required'
 if ops.count(key)!=1 or ops.count(anchor)!=1:raise RuntimeError("METADATA_PARSER_REVIEW_REQUIRED")
 ops=ops.replace(key,'"BACKEND_IMAGE", "BOT_IMAGE", "BACKEND_CONTRACT"')
 ops=ops.replace(anchor,'    if "BOT_IMAGE" in result and not re.fullmatch(r"sha256:[a-f0-9]{64}", result["BOT_IMAGE"]):\n        raise ValueError("immutable local worker image ID required")\n'+anchor)
 compile(ops,"ops.py","exec")
 values=dict(line.split("=",1) for line in original["runtime.env"].decode().splitlines() if line)
 if values.get("BACKEND_IMAGE")!=OLD or values.get("TELEGRAM_AUTH_ENABLED")!="true" or "BOT_IMAGE" in values:raise RuntimeError("RUNTIME_STATE_REVIEW_REQUIRED")
 values["BOT_IMAGE"]=new
 edited=True;replace(COMPOSE,text.encode());replace(ROOT/"scripts/ops.py",ops.encode());replace(ENV,''.join(k+'='+v+'\n' for k,v in values.items()).encode())
 compose("config","--quiet")
 print("WORKER_IMAGE_SPLIT_VALIDATED_API_IMAGE_UNCHANGED",flush=True)
 stage="old_worker_stop";stopped=True;compose("stop","--timeout","45","bot")
 if inspect("chrona-bot-1","{{.State.Running}}")!="false":raise RuntimeError("OLD_POLLER_NOT_STOPPED")
 for _ in range(60):
  cut=state()
  if cut["lease_free"]:break
  time.sleep(1)
 else:raise RuntimeError("OLD_LEASE_NOT_RELEASED_OR_EXPIRED")
 if cut["offset"]<initial["offset"]:raise RuntimeError("OFFSET_REGRESSED_BEFORE_SWITCH")
 print("OLD_POLLER_STOPPED_LEASE_RELEASED_OR_EXPIRED_NO_MANUAL_SQL_WRITE",flush=True)
 stage="new_worker_start_and_profile"
 compose("up","-d","--no-deps","--no-build","--pull","never","--wait","--wait-timeout","180","bot")
 if inspect("chrona-bot-1","{{.Image}}")!=new:raise RuntimeError("NEW_WORKER_IMAGE_MISMATCH")
 new_profile=profile()
 if new_profile!=before_profile:
  print("WORKER_PROFILE_DIFFERENT_FIELDS="+','.join(k for k in before_profile if before_profile[k]!=new_profile[k]),flush=True)
  raise RuntimeError("NEW_WORKER_PROFILE_MISMATCH")
 stage="new_worker_poll_observation"
 samples=[]
 for index in range(7):
  current=state()
  if current["lease_free"] or not 0<=current["now"]-current["success"]<20000 or current["offset"]<cut["offset"]:raise RuntimeError("POLL_OFFSET_LEASE_NOT_READY")
  if current["inbox"][0]<cut["inbox"][0] or current["inbox"][1]<cut["inbox"][1] or current["outbox"][0]<cut["outbox"][0]:raise RuntimeError("QUEUE_CONTINUITY_REGRESSED")
  samples.append(current)
  if index<6:time.sleep(6)
 if samples[-1]["success"]<=samples[0]["success"]:raise RuntimeError("NO_LIVE_POLL_PROGRESSION")
 if inspect("chrona-bot-1","{{.State.Health.Status}}")!="healthy" or inspect("chrona-bot-1","{{.RestartCount}}")!="0":raise RuntimeError("NEW_WORKER_NOT_HEALTHY")
 for name,(identifier,image) in preserve.items():
  if inspect(name,"{{.Id}}")!=identifier or inspect(name,"{{.Image}}")!=image:raise RuntimeError("NONWORKER_CONTAINER_CHANGED")
 if inspect("chrona-api-1","{{.State.Health.Status}}")!="healthy" or serve_hash()!=private_mapping:raise RuntimeError("API_OR_TAILSCALE_BASELINE_CHANGED")
 print("WORKER87_HEALTHY_ONE_POLLER_RESTART0_POLL_PROGRESS_36S_OFFSET_QUEUE_CONTINUITY_PASS",flush=True)
 print("QUEUE_COUNTS_UNCHANGED="+str(samples[-1]["inbox"]==cut["inbox"] and samples[-1]["outbox"]==cut["outbox"]),flush=True)
 print("API80_DB_PROXY_NEXTCLOUD_IDS_IMAGES_AND_SERVE443_GATEWAY_METADATA_UNCHANGED",flush=True)
 print("NEW_WORKER_IMAGE="+new,flush=True);print("APP_JAR_SHA256="+EXPECTED,flush=True);print("ROLLBACK_DIRECTORY="+str(backup),flush=True)
except BaseException:
 if edited:
  try:
   if stopped:compose("stop","--timeout","45","bot")
   replace(COMPOSE,original["compose.app.yml"]);replace(ROOT/"scripts/ops.py",original["ops.py"]);replace(ENV,original["runtime.env"])
   if stopped:
    for _ in range(60):
     if state()["lease_free"]:break
     time.sleep(1)
    else:raise RuntimeError("ROLLBACK_LEASE_STILL_ACTIVE")
    compose("up","-d","--no-deps","--no-build","--pull","never","--wait","--wait-timeout","180","bot")
   print("WORKER_ONLY_ROLLBACK_OLD_IMAGE_CONFIG_RESTORED_DATA_OFFSET_RETAINED",flush=True)
  except BaseException:print("WORKER_ROLLBACK_REQUIRES_REVIEW_NO_DATA_REWIND_ATTEMPTED",flush=True)
 raise
'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--password-stdin", action="store_true")
    parser.add_argument("--ssh-alias", help="explicit previously confirmed SSH alias, not a private target in source")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN worker87 rollout: no artifact/auth reads, SSH, writes or messages")
        return 0
    if not args.ssh_alias or not re.fullmatch(r"[A-Za-z0-9_-]+", args.ssh_alias):
        parser.error("explicit confirmed SSH alias required")
    if not args.password_stdin:
        parser.error("private auth stdin required")
    secret = sys.stdin.buffer.readline(1025).rstrip(b"\r\n")
    if not secret or len(secret) > 1024:
        parser.error("invalid auth input")
    jar = DIST / "lib/chrona-backend-0.1.0.jar"
    if jar.is_symlink() or hashlib.sha256(jar.read_bytes()).hexdigest() != EXPECTED:
        print("LOCAL_ROOT87_ARTIFACT_MISMATCH_NO_SERVER_CONNECTION")
        return 1
    buffer = io.BytesIO()
    with tarfile.open(fileobj=buffer, mode="w") as archive:
        for path in sorted(DIST.rglob("*")):
            relative = path.relative_to(DIST)
            if path.is_file() and not path.is_symlink() and (str(relative) == "bin/chrona-backend" or len(relative.parts) == 2 and relative.parts[0] == "lib" and path.suffix == ".jar"):
                archive.add(path, arcname="distribution/" + str(relative), recursive=False)
        context = Path(__file__).resolve().parents[1] / "backend"
        for name in ("Dockerfile", ".dockerignore"):
            archive.add(context / name, arcname=name, recursive=False)
    code = "try:\n" + "\n".join(" " + line for line in REMOTE.splitlines()) + "\nexcept BaseException as error:\n print('WORKER_ROLLOUT_FAILED_STAGE='+globals().get('stage','init')+' CLASS='+type(error).__name__)\n if type(error) is RuntimeError:print('GUARD='+str(error))\n raise SystemExit(1)\n"
    ssh = ["/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "ConnectTimeout=7", args.ssh_alias]
    result = subprocess.run(ssh + ["sudo -S -p '' -- python3 -c " + shlex.quote(code)], input=secret + b"\n" + buffer.getvalue(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=600)
    del secret
    sys.stdout.buffer.write(result.stdout)
    if result.returncode and not result.stdout:
        print("SECURE_SUDO_OR_SSH_UNAVAILABLE_NO_RETRY_OR_BYPASS")
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())

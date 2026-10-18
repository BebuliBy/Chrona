#!/usr/bin/env python3
"""Chrona-specific approved deployment driver. Auth exclusively private stdin; dry-run default.

Never accepts secret values in arguments/environment, never prints subprocess logs.
Only fixed SSH alias and own paths/projects. Called by coordinator with authorized stdin.
"""
import argparse
import io
import json
from pathlib import Path
import shlex
import subprocess
import sys
import tarfile

SSH = ["/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "ConnectTimeout=7", "server"]
ROOT = Path(__file__).resolve().parents[1]
DIST = Path("/home/niki/android-tracker/backend/build/install/chrona-backend")
PREAMBLE = r'''
import os,sys,json,subprocess,pathlib,stat,time,hashlib
P=pathlib.Path
def cmd(args,**kw):
 try:return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,**kw).stdout
 except subprocess.CalledProcessError as e:
  print("COMMAND_FAILED="+" ".join(args[:6])+" EXIT="+str(e.returncode),flush=True)
  raise
def docker(*args): return cmd(["/usr/bin/docker",*args])
def guarded(p):
 p=P(p)
 for x in [p,*p.parents]:
  if x.is_symlink(): raise RuntimeError("SYMLINK")
  if x.exists() and (x.stat().st_uid!=0 or x.stat().st_mode&0o022): raise RuntimeError("UNSAFE_OWNER")
 return p
def compose(project,*args):
 f="compose.proxy.yml" if project=="chrona-proxy" else "compose.restore.yml" if project=="chrona-restore" else "compose.app.yml"
 return docker("compose","--project-name",project,"--env-file","/etc/chrona/runtime.env","-f","/opt/chrona/infra/"+f,*args)
'''
LAYOUT = r'''
for p in ("/opt/chrona","/opt/chrona-proxy","/etc/chrona","/var/lib/chrona","/var/backups/chrona"):
 guarded(p)
 if P(p).exists(): raise RuntimeError("OWN_PATH_ALREADY_EXISTS_REVIEW_REQUIRED")
# Baseline captured before any runtime changes; no Env/Cmd/health output read.
names=docker("ps","-a","--format","{{.Names}}").decode().splitlines()
baseline={"time":time.time(),"containers":[],"services":cmd(["systemctl","is-active","docker","ssh","tailscaled","smbd","spbpu-calendar"]).decode().splitlines(),"networks":docker("network","ls","--format","{{.Name}}").decode().splitlines(),"new_paths_previously_absent":True}
for name in names:
 baseline["containers"].append({"name":name,"id":docker("inspect","--format","{{.Id}}",name).decode().strip(),"image":docker("inspect","--format","{{.Image}}",name).decode().strip(),"status":docker("inspect","--format","{{.State.Status}}",name).decode().strip()})
for name in baseline["networks"]:
 if name.startswith("chrona"): raise RuntimeError("OWN_NETWORK_COLLISION")
for name in docker("volume","ls","--format","{{.Name}}").decode().splitlines():
 if name.startswith("chrona"): raise RuntimeError("OWN_VOLUME_COLLISION")
for p,mode in (("/var/backups/chrona",0o700),("/opt/chrona",0o755),("/opt/chrona/infra",0o755),("/opt/chrona-proxy",0o755),("/etc/chrona",0o700),("/etc/chrona/secrets",0o700),("/var/lib/chrona",0o700),("/var/lib/chrona/postgres",0o700)):
 P(p).mkdir(mode=mode)
os.chown("/var/lib/chrona/postgres",70,70)
fd=os.open("/var/backups/chrona/prestate-20261018.json",os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o600)
with os.fdopen(fd,"w") as f: json.dump(baseline,f);f.flush();os.fsync(f.fileno())
import secrets
password=secrets.token_urlsafe(48)
for name,uid in (("db-password",70),("app-db-password",10001)):
 fd=os.open("/etc/chrona/secrets/"+name,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,"w") as f:
  os.fchown(f.fileno(),uid,uid);f.write(password+"\n");f.flush();os.fsync(f.fileno())
del password
print("LAYOUT_PRESTATE_PRIVATE_DB_SECRETS_OK")
'''
UPLOAD = r'''
import tarfile
base=guarded("/opt/chrona/infra")
with tarfile.open(fileobj=sys.stdin.buffer,mode="r|*") as archive:
 for member in archive:
  path=P(member.name)
  if path.is_absolute() or ".." in path.parts or member.issym() or member.islnk() or not (member.isfile() or member.isdir()): raise RuntimeError("BAD_ARCHIVE")
  destination=base/path
  guarded(destination)
  if destination.exists(): raise RuntimeError("EXCLUSIVE_STAGING_ONLY")
  if member.isdir(): destination.mkdir(mode=0o755,parents=True,exist_ok=False)
  else:
   destination.parent.mkdir(mode=0o755,parents=True,exist_ok=True)
   fd=os.open(destination,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o755 if path.name=="chrona-backend" else 0o644)
   with os.fdopen(fd,"wb") as out: out.write(archive.extractfile(member).read())
print("REVIEWED_INFRA_DISTRIBUTION_STAGED")
'''
SYNC = r'''
import tarfile
base=guarded("/opt/chrona/infra")
stamp=str(time.time_ns())
backup=guarded("/var/backups/chrona")/("infra-preupdate-"+stamp+".tar")
fd=os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,"wb") as out:
 with tarfile.open(fileobj=out,mode="w") as archive: archive.add(base,arcname="infra",recursive=True)
with tarfile.open(fileobj=sys.stdin.buffer,mode="r|*") as archive:
 for member in archive:
  path=P(member.name)
  if path.is_absolute() or ".." in path.parts or not member.isfile() or member.issym() or member.islnk(): raise RuntimeError("BAD_ARCHIVE")
  destination=guarded(base/path)
  destination.parent.mkdir(mode=0o755,parents=True,exist_ok=True)
  temporary=destination.with_name(destination.name+".stage-"+stamp)
  fd=os.open(temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o755 if path.name=="chrona-backend" else 0o644)
  with os.fdopen(fd,"wb") as out: out.write(archive.extractfile(member).read());out.flush();os.fsync(out.fileno())
  os.replace(temporary,destination)
print("OWN_INFRA_BACKUP_ATOMIC_SYNC_OK")
'''
IMAGES = r'''
pins=json.loads(P("/opt/chrona/infra/verified-images-2026-10-18.json").read_text())
refs={"xray":"ghcr.io/xtls/xray-core:26.3.27@"+pins["xray"]["amd64_digest"],"postgres":"postgres:17-alpine@"+pins["postgres"]["amd64_digest"],"jre":"eclipse-temurin:17-jre-jammy@"+pins["jre"]["amd64_digest"]}
for ref in refs.values(): docker("pull","--platform","linux/amd64",ref)
for key,args in (("xray",["version"]),("postgres",["postgres","--version"]),("jre",["java","-version"])):
 result=cmd(["docker","run","--rm","--network","none","--read-only","--cap-drop","ALL","--security-opt","no-new-privileges","--memory","384m","--cpus","0.5",refs[key],*args])
 print("CPU_VERSION_SMOKE_OK="+key)
docker("build","--network","none","--build-arg","JVM_IMAGE="+refs["jre"],"-t","chrona-backend:2026-10-18","/opt/chrona/infra/backend")
image=docker("image","inspect","--format","{{.Id}}","chrona-backend:2026-10-18").decode().strip()
metadata="XRAY_IMAGE="+refs["xray"]+"\nPOSTGRES_IMAGE="+refs["postgres"]+"\nBACKEND_IMAGE="+image+"\nBACKEND_CONTRACT=approved-v1\nCPU_SMOKE=approved-baseline-amd64\nTELEGRAM_AUTH_ENABLED=false\nAPI_HOST_PORT=18080\n"
fd=os.open("/etc/chrona/runtime.env",os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,"w") as f:f.write(metadata);f.flush();os.fsync(f.fileno())
print("PINNED_IMAGES_STAGED_BACKEND_ID="+image)
'''
VPN = r'''
sys.path.insert(0,"/opt/chrona/infra/scripts")
import xray_config
source=json.load(sys.stdin)
config=xray_config.isolated_config(source)
xray_config.write_new_private("/etc/chrona/secrets/xray.json",config)
os.chown("/etc/chrona/secrets/xray.json",65532,65532)
print("PRIVATE_ISOLATED_XRAY_CONFIG_INSTALLED")
'''
START = r'''
for project in ("chrona-proxy","chrona","chrona-restore"):
 compose(project,"config","--quiet"); print("COMPOSE_QUIET_OK="+project,flush=True)
compose("chrona-proxy","run","-T","--rm","--no-deps","--pull","never","xray-proxy","run","-test","-c","/run/private/xray.json")
print("XRAY_SYNTAX_OK",flush=True)
compose("chrona-proxy","up","-d","--no-build","--pull","never","xray-proxy")
print("PROXY_STARTED",flush=True)
compose("chrona","up","-d","--no-build","--pull","never","--wait","--wait-timeout","180","db")
print("OWN_DB_HEALTHY",flush=True)
compose("chrona","--profile","maintenance","run","-T","--rm","--no-deps","--pull","never","migrate")
print("COMPOSE_QUIET_XRAY_SYNTAX_PROXY_DB_MIGRATION_OK")
print("OWN_CONTAINERS="+docker("ps","--filter","label=com.docker.compose.project=chrona","--format","{{.Names}} {{.Status}}").decode().strip())
'''
STATUS = r'''
print(docker("ps","-a","--filter","name=chrona","--format","{{.Names}} | {{.Status}}").decode())
for project in ("chrona-proxy","chrona","chrona-restore"):
 try:compose(project,"config","--quiet");print("CONFIG_OK="+project)
 except subprocess.CalledProcessError: print("CONFIG_FAILED="+project)
'''
DIAGNOSE = r'''
args=["docker","compose","--project-name","chrona","--env-file","/etc/chrona/runtime.env","-f","/opt/chrona/infra/compose.app.yml","--profile","maintenance","run","-T","--rm","--no-deps","--pull","never","--entrypoint","/bin/sh","migrate","-c","test -r /run/secrets/chrona/db-password && echo PRIVATE_MOUNT_READABLE; java -version; /opt/chrona/bin/chrona-backend migrate"]
r=subprocess.run(args,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
text=(r.stdout+r.stderr).decode(errors="replace")
print("MIGRATE_DIAGNOSTIC_EXIT="+str(r.returncode))
import re
safe=re.sub(r"(?:https?|socks5h?)://\S+|/[A-Za-z0-9_./-]+|[0-9a-f]{32,}|\d{5,20}:[A-Za-z0-9_-]{20,}","<redacted>",text)
safe=re.sub(r"(?i)(password|token|secret)\s*[=:]\s*\S+",r"\1=<redacted>",safe)
print("SANITIZED_CONTAINER_DIAGNOSTIC="+safe[:1500].replace("\n"," | "))
for needle in ("No such image","pull access denied","invalid reference","OCI runtime","read-only file system","not found","failed to create","bind source path does not exist","unable to find user","invalid mount config","Cannot start service"):
 if needle.lower() in text.lower(): print("CONTAINER_ERROR_CLASS="+needle)
for label,needle in (("PRIVATE_MOUNT_READABLE","PRIVATE_MOUNT_READABLE"),("BACKEND_ERROR_REDACTED","CHRONA_START_OR_RUNTIME_FAILED"),("UNSUPPORTED_JAVA","UnsupportedClassVersionError"),("PERMISSION_ERROR","Permission denied"),("MISSING_CLASS","ClassNotFoundException"),("MISSING_JAVA","JAVA_HOME is not set")):
 print(label+"="+str(needle in text))
print("PG_TABLE_COUNT="+compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT count(*) FROM information_schema.tables WHERE table_schema='public'").decode().strip())
'''
EGRESS = r'''
pins=json.loads(P("/opt/chrona/infra/verified-images-2026-10-18.json").read_text())
ref="eclipse-temurin:17-jre-jammy@"+pins["jre"]["amd64_digest"]
names=docker("ps","-a","--format","{{.Names}}").decode().splitlines()
name="chrona-proxy-check"
if name in names: raise RuntimeError("TEST_CONSUMER_NAME_COLLISION")
docker("run","-d","--name",name,"--label","chrona.temporary-check=true","--network","chrona-proxy-clients","--read-only","--user","10001:10001","--cap-drop","ALL","--security-opt","no-new-privileges","--memory","64m","--cpus","0.25","--entrypoint","/bin/sleep",ref,"600")
try:
 docker("network","connect","chrona-app",name)
 pid=docker("inspect","--format","{{.State.Pid}}",name).decode().strip()
 ip=docker("inspect","--format",'{{(index .NetworkSettings.Networks "chrona-proxy-clients").IPAddress}}',"chrona-proxy-xray-proxy-1").decode().strip()
 cmd(["nsenter","--target",pid,"--net","--","python3","/opt/chrona/infra/scripts/probe.py","--execute","--proxy-ip",ip])
 print("REAL_SOCKS_REMOTE_DNS_TELEGRAM_TLS_OK",flush=True)
 import socket
 target=socket.getaddrinfo("api.telegram.org",443,type=socket.SOCK_STREAM,family=socket.AF_INET)[0][4][0]
 deny="import socket,sys\ntry:\n s=socket.create_connection((sys.argv[1],443),timeout=4);s.close();sys.exit(9)\nexcept OSError:sys.exit(0)"
 cmd(["nsenter","--target",pid,"--net","--","python3","-c",deny,target])
 print("CONSUMER_DIRECT_EGRESS_DENIED",flush=True)
 # Real failure/recovery affects only our new proxy, never host internet.
 members=set(docker("network","inspect","--format",'{{range .Containers}}{{.Name}}{{println}}{{end}}',"chrona-proxy-clients").decode().splitlines())
 if members!={name,"chrona-proxy-xray-proxy-1"}:raise RuntimeError("ADDITIONAL_PROXY_CONSUMER_REVIEW_REQUIRED")
 compose("chrona-proxy","stop","xray-proxy")
 probe=["nsenter","--target",pid,"--net","--","python3","/opt/chrona/infra/scripts/probe.py","--execute","--proxy-ip",ip]
 result=subprocess.run(probe,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if result.returncode==0: raise RuntimeError("STOPPED_PROXY_STILL_REACHABLE")
 print("OWN_PROXY_OUTAGE_FAIL_CLOSED",flush=True)
 compose("chrona-proxy","start","xray-proxy")
 time.sleep(2)
 cmd(probe)
 print("OWN_PROXY_RESTART_RECONNECT_OK",flush=True)
 if cmd(["ss","-H","-lnt","sport = :10808"]).strip(): raise RuntimeError("HOST_PROXY_LISTENER_UNEXPECTED")
 print("NO_HOST10808_LISTENER",flush=True)
finally:
 # Remove ONLY our uniquely named labelled temporary consumer, not networks/volumes.
 if docker("inspect","--format",'{{index .Config.Labels "chrona.temporary-check"}}',name).decode().strip()=="true":docker("rm","-f",name)
'''
BACKUP = r'''
cmd(["python3","/opt/chrona/infra/scripts/ops.py","backup","--execute","--approved"])
archives=sorted(P("/var/backups/chrona").glob("*.dump"))
if not archives:raise RuntimeError("NO_COMPLETED_BACKUP")
archive=str(archives[-1])
cmd(["python3","/opt/chrona/infra/scripts/ops.py","restore","--archive",archive,"--execute","--approved"])
print("CONSISTENT_DUMP_CHECKSUM_ISOLATED_SQL_RESTORE_OK",flush=True)
# Compare schema migration+essential durable-state structure/counts, no personal payload.
query="SELECT version,checksum FROM schema_migrations ORDER BY version; SELECT count(*),max(next_offset) FROM bot_state; SELECT count(*) FROM accounts; SELECT count(*) FROM information_schema.tables WHERE table_schema='public';"
source=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc",query)
compose("chrona-restore","start","db")
try:
 for i in range(30):
  r=subprocess.run(["docker","compose","--project-name","chrona-restore","--env-file","/etc/chrona/runtime.env","-f","/opt/chrona/infra/compose.restore.yml","exec","-T","db","pg_isready","-U","chrona","-d","chrona"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
  if r.returncode==0:break
  time.sleep(1)
 restored=compose("chrona-restore","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc",query)
 if source!=restored:raise RuntimeError("RESTORE_INVARIANT_MISMATCH")
 print("RESTORE_MIGRATION_BOT_STATE_ACCOUNT_TABLE_INVARIANTS_MATCH",flush=True)
finally:compose("chrona-restore","stop","db")
print("BACKUP_BYTES="+str(P(archive).stat().st_size),flush=True)
'''
VERIFY = r'''
baseline=json.loads(P("/var/backups/chrona/prestate-20261018.json").read_text())
for row in baseline["containers"]:
 name=row["name"]
 for key,template in (("id","{{.Id}}"),("image","{{.Image}}"),("status","{{.State.Status}}")):
  if docker("inspect","--format",template,name).decode().strip()!=row[key]: raise RuntimeError("LEGACY_CONTAINER_CHANGED")
services=cmd(["systemctl","is-active","docker","ssh","tailscaled","smbd","spbpu-calendar"]).decode().splitlines()
if services!=baseline["services"]:raise RuntimeError("LEGACY_SERVICE_CHANGED")
print("ALL_LEGACY_CONTAINER_IDS_IMAGES_STATUS_AND_SERVICES_UNCHANGED",flush=True)
php='$r=json_decode(file_get_contents("http://127.0.0.1/status.php"),true);if(!is_array($r)||!($r["installed"]??false)||($r["maintenance"]??true)||($r["needsDbUpgrade"]??true)){exit(1);}echo "OK";'
cmd(["docker","exec","nextcloud-app","php","-r",php])
print("NEXTCLOUD_READONLY_HTTP_STATUS_OK",flush=True)
print("PG_VERSION="+compose("chrona","exec","-T","db","postgres","--version").decode().strip(),flush=True)
print("MIGRATION_COUNT="+compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT count(*) FROM schema_migrations WHERE version=1").decode().strip(),flush=True)
# Two independent JDBC processes against the own DB exercise migration lock/idempotence.
args=["docker","compose","--project-name","chrona","--env-file","/etc/chrona/runtime.env","-f","/opt/chrona/infra/compose.app.yml","--profile","maintenance","run","-T","--rm","--no-deps","--pull","never","migrate"]
processes=[subprocess.Popen(args,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL) for _ in range(2)]
if any(p.wait(timeout=120)!=0 for p in processes):raise RuntimeError("CONCURRENT_MIGRATION_FAILED")
print("CONCURRENT_JDBC_MIGRATION_IDEMPOTENCE_OK",flush=True)
compose("chrona","restart","db")
for i in range(60):
 ready=subprocess.run(["docker","exec","chrona-db-1","pg_isready","-U","chrona","-d","chrona"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
 if ready.returncode==0:break
 time.sleep(1)
compose("chrona","--profile","maintenance","run","-T","--rm","--no-deps","--pull","never","migrate")
print("OWN_DB_RESTART_JDBC_RECONNECT_MIGRATION_OK",flush=True)
for name in docker("ps","-a","--format","{{.Names}}").decode().splitlines():
 if name.startswith("chrona"):
  print("OWN_STATUS="+name+" "+docker("inspect","--format",'{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}',name).decode().strip())
'''
REFRESH_API = r'''
import socket
if "chrona-api-1" in docker("ps","--format","{{.Names}}").decode().splitlines():
 ports=json.loads(docker("inspect","--format",'{{json .HostConfig.PortBindings}}',"chrona-api-1"))
 if ports!={"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"18080"}]}:raise RuntimeError("EXISTING_API_BIND_CONFLICT")
 print("FRESH_LOOPBACK18080_OWN_API_BIND_CONFIRMED",flush=True)
else:
 listener=socket.socket()
 try:listener.bind(("127.0.0.1",18080))
 finally:listener.close()
 print("FRESH_LOOPBACK18080_FREE",flush=True)
envpath=guarded("/etc/chrona/runtime.env")
old=envpath.read_bytes()
stamp=str(time.time_ns())
fd=os.open("/var/backups/chrona/runtime-preapi-"+stamp+".env",os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,"wb") as f:f.write(old);f.flush();os.fsync(f.fileno())
rows=dict(line.split("=",1) for line in old.decode().splitlines() if line)
rows.pop("BOT_USERNAME",None)
rows["TELEGRAM_AUTH_ENABLED"]="false"
def metadata_write():
 temp=envpath.with_name("runtime.env.stage-"+stamp)
 fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
 with os.fdopen(fd,"w") as f:
  for k,v in rows.items():f.write(k+"="+v+"\n")
  f.flush();os.fsync(f.fileno())
 os.replace(temp,envpath)
metadata_write()
cmd(["python3","/opt/chrona/infra/scripts/ops.py","backup","--execute","--approved"])
print("OWN_RUNTIME_AND_CONSISTENT_DB_PREAPI_BACKUP_OK",flush=True)
schema=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT checksum FROM schema_migrations WHERE version=1").decode().strip()
source=P("/opt/chrona/infra/backend/source/src/main/resources/db/V001__foundation.sql")
import base64
if base64.urlsafe_b64encode(hashlib.sha256(source.read_bytes()).digest()).decode().rstrip("=")!=schema:raise RuntimeError("MIGRATION_CHANGED_REQUIRES_REVIEW")
pins=json.loads(P("/opt/chrona/infra/verified-images-2026-10-18.json").read_text())
jre="eclipse-temurin:17-jre-jammy@"+pins["jre"]["amd64_digest"]
docker("build","--network","none","--build-arg","JVM_IMAGE="+jre,"-t","chrona-backend:2026-10-18-checkpoint80","/opt/chrona/infra/backend")
image=docker("image","inspect","--format","{{.Id}}","chrona-backend:2026-10-18-checkpoint80").decode().strip()
rows["BACKEND_IMAGE"]=image
metadata_write()
compose("chrona","config","--quiet")
compose("chrona","--profile","maintenance","run","-T","--rm","--no-deps","--pull","never","migrate")
compose("chrona","--profile","api","up","-d","--no-deps","--no-build","--pull","never","--wait","--wait-timeout","180","api")
print("NEW_BACKEND_IMMUTABLE_ID="+image,flush=True)
print("API_NATIVE_HEALTHCHECK_AND_JDBC_MIGRATION_OK",flush=True)
'''
API_CHECK = r'''
import urllib.request,urllib.error,uuid,base64,secrets
def http(path,body=None):
 data=None if body is None else json.dumps(body).encode()
 request=urllib.request.Request("http://127.0.0.1:18080"+path,data=data,headers={"Content-Type":"application/json"} if data else {})
 try:
  with urllib.request.urlopen(request,timeout=15) as response:return response.status,response.read(65536)
 except urllib.error.HTTPError as e:return e.code,e.read(65536)
before=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT count(*) FROM accounts; SELECT count(*) FROM login_challenges; SELECT count(*) FROM device_sessions;")
schema=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT version,checksum FROM schema_migrations ORDER BY version")
for path in ("/health/live","/health/ready"):
 status,payload=http(path)
 if status!=200:raise RuntimeError("API_HEALTH_FAILED")
 print("HTTP_200="+path,flush=True)
for path in ("/v1/me","/v1/sync/pull","/v1/friends"):
 status,payload=http(path)
 if status!=401 or json.loads(payload)["error"]["code"]!="UNAUTHENTICATED":raise RuntimeError("AUTHORIZATION_BOUNDARY_FAILED")
 print("HTTP_401="+path,flush=True)
# Valid-format SYNTHETIC challenge only. No identity/login/account is fabricated.
body={"deviceId":str(uuid.uuid4()),"deviceLabel":"Infrastructure acceptance synthetic no account","codeChallenge":base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="),"codeChallengeMethod":"S256"}
status,payload=http("/v1/auth/telegram/challenges",body)
if status!=503 or json.loads(payload)["error"]["code"]!="TELEGRAM_NOT_CONFIGURED":raise RuntimeError("DISABLED_TELEGRAM_AUTH_FAILED")
if any(word in payload for word in (b"telegramStartUrl",b"t.me",b"accessToken",b"refreshToken")):raise RuntimeError("DISABLED_AUTH_LEAK")
after=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT count(*) FROM accounts; SELECT count(*) FROM login_challenges; SELECT count(*) FROM device_sessions;")
if before!=after:raise RuntimeError("DISABLED_AUTH_CREATED_STATE")
print("HTTP_503_TELEGRAM_NOT_CONFIGURED_NO_URL_NO_ACCOUNT_CHALLENGE_SESSION_CREATED",flush=True)
ports=json.loads(docker("inspect","--format",'{{json .HostConfig.PortBindings}}',"chrona-api-1"))
if ports!={"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":"18080"}]}:raise RuntimeError("API_HOST_BIND_NOT_LOOPBACK_ONLY")
cmd(["docker","exec","chrona-api-1","/bin/sh","-c","test ! -e /run/secrets/chrona/telegram-token"])
print("LOOPBACK_ONLY18080_AND_NO_API_TELEGRAM_TOKEN_MOUNT",flush=True)
compose("chrona","--profile","api","restart","api")
for i in range(90):
 try:
  status,payload=http("/health/ready")
  if status==200:break
 except (OSError,ValueError):pass
 time.sleep(1)
else:raise RuntimeError("API_RESTART_RECONNECT_FAILED")
if schema!=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT version,checksum FROM schema_migrations ORDER BY version"):raise RuntimeError("MIGRATION_CHANGED")
if before!=compose("chrona","exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT count(*) FROM accounts; SELECT count(*) FROM login_challenges; SELECT count(*) FROM device_sessions;"):raise RuntimeError("RESTART_CREATED_IDENTITY")
names=docker("ps","-a","--format","{{.Names}}").decode().splitlines()
if "chrona-bot-1" in names:raise RuntimeError("BOT_PROFILE_UNEXPECTEDLY_STARTED")
print("API_RESTART_JDBC_RECONNECT_SAME_MIGRATION_NO_WORKER_OK",flush=True)
'''
FINAL_PREFLIGHT = r'''
sys.path.insert(0,"/opt/chrona/infra/scripts")
import ops
data=P("/var/lib/chrona/postgres").stat()
print("OWN_DATA_PERMISSION_METADATA=uid"+str(data.st_uid)+":mode"+oct(stat.S_IMODE(data.st_mode)),flush=True)
for step,check in (("private_paths_metadata",lambda:ops.preflight("preflight")),("own_resource_ownership",ops.resource_guard)):
 try:check();print("PREFLIGHT_STEP_OK="+step,flush=True)
 except ValueError as e:
  # Own guard errors are fixed code strings, never include config values.
  print("PREFLIGHT_GUARD="+step+":"+str(e),flush=True);raise
for project in ("chrona-proxy","chrona","chrona-restore"):ops.run(ops.compose(project,"config","--quiet"),stdout=subprocess.DEVNULL)
assert not P("/etc/chrona/secrets/telegram-token").exists()
print("FINAL_PERMISSION_RESOURCE_COMPOSE_PREFLIGHT_PASS_TOKEN_ABSENT")
'''
DATA_PERMISSIONS = r'''
before={}
for path in ("/var/lib/chrona/postgres","/var/lib/chrona/restore-postgres"):
 p=P(path)
 guarded(p.parent)
 if p.is_symlink() or not p.is_dir() or p.stat().st_uid!=70 or stat.S_IMODE(p.stat().st_mode) not in (0o700,0o1777):raise RuntimeError("OWN_DATA_PERMISSION_REVIEW_REQUIRED")
 before[path]={"uid":p.stat().st_uid,"gid":p.stat().st_gid,"mode":stat.S_IMODE(p.stat().st_mode)}
fd=os.open("/var/backups/chrona/data-permissions-before-"+str(time.time_ns())+".json",os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,"w") as f:json.dump(before,f);f.flush();os.fsync(f.fileno())
for path in before:os.chmod(path,0o700)
print("OWN_DATA_TOPLEVEL700_PERMISSION_SNAPSHOT_SAVED_NO_RECURSIVE_CHANGES")
'''
DOCS = r'''
import tarfile
base=guarded("/opt/chrona/docs")
if not base.exists():base.mkdir(mode=0o755)
stamp=str(time.time_ns())
backup=P("/var/backups/chrona")/("docs-preupdate-"+stamp+".tar")
fd=os.open(backup,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o600)
with os.fdopen(fd,"wb") as out:
 with tarfile.open(fileobj=out,mode="w") as archive:archive.add(base,arcname="docs")
with tarfile.open(fileobj=sys.stdin.buffer,mode="r|*") as archive:
 for member in archive:
  if member.name not in ("server-plan.md","server-deployment-2026-10-18.md") or not member.isfile() or member.issym() or member.islnk():raise RuntimeError("DOCS_ALLOWLIST")
  target=guarded(base/member.name)
  temp=target.with_name(target.name+".stage-"+stamp)
  fd=os.open(temp,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,0o644)
  with os.fdopen(fd,"wb") as out:out.write(archive.extractfile(member).read());out.flush();os.fsync(out.fileno())
  os.replace(temp,target)
print("OWN_DEPLOYMENT_DOCS_STAGED_WITH_BACKUP")
'''


def bundle():
    stream = io.BytesIO()
    with tarfile.open(fileobj=stream, mode="w") as archive:
        for path in sorted(ROOT.rglob("*")):
            if not path.is_file() or path.is_symlink() or any(p in ("__pycache__", "distribution", "secrets", "private", "runtime", "backups") for p in path.relative_to(ROOT).parts):
                continue
            if path.suffix in (".jar", ".dump", ".env", ".partial"):
                continue
            archive.add(path, arcname=str(path.relative_to(ROOT)), recursive=False)
        for path in sorted(DIST.rglob("*")):
            relative = path.relative_to(DIST)
            if path.is_file() and not path.is_symlink() and (str(relative) == "bin/chrona-backend" or relative.parts[0] == "lib" and path.suffix == ".jar"):
                archive.add(path, arcname="backend/distribution/" + str(relative), recursive=False)
        backend = DIST.parents[2]
        for path in sorted((backend / "src/main").rglob("*")):
            if path.is_file() and not path.is_symlink() and path.suffix in (".kt", ".sql", ".json"):
                archive.add(path, arcname="backend/source/" + str(path.relative_to(backend)), recursive=False)
        for name in ("build.gradle.kts", "settings.gradle.kts", "Dockerfile", "Dockerfile.dockerignore", "runtime.env.example"):
            path = backend / name
            if path.is_file() and not path.is_symlink():
                archive.add(path, arcname="backend/source/" + name, recursive=False)
    return stream.getvalue()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("layout", "upload", "sync", "images", "vpn", "start", "status", "diagnose", "egress", "backup", "verify", "refresh-api", "api-check", "legacy-check", "api-publish", "final-preflight", "data-permissions", "docs", "serve-baseline", "serve-apply", "serve-check"))
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--password-stdin", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN own deployment phase=" + args.phase + "; no auth/SSH/private reads")
        return 0
    if not args.password_stdin:
        parser.error("authorized credential must arrive through private stdin")
    secret = sys.stdin.buffer.readline(1025).rstrip(b"\r\n")
    if not secret or len(secret) > 1024:
        parser.error("invalid private auth input")
    payload = b""
    if args.phase in ("upload", "sync"):
        payload = bundle()
    elif args.phase == "docs":
        stream = io.BytesIO()
        with tarfile.open(fileobj=stream, mode="w") as archive:
            for filename in ("server-plan.md", "server-deployment-2026-10-18.md"):
                archive.add(ROOT.parent / "docs" / filename, arcname=filename, recursive=False)
        payload = stream.getvalue()
    elif args.phase == "vpn":
        # Explicitly authorized minimum extraction; never serialize/log full laptop config.
        with Path("/usr/local/etc/xray/config.json").open() as source:
            payload = json.dumps(json.load(source)["outbounds"][0], separators=(",", ":")).encode()
    if args.phase.startswith("serve-"):
        action = args.phase.removeprefix("serve-")
        code = PREAMBLE + 'r=subprocess.run(["python3","/opt/chrona/infra/scripts/serve.py",' + repr(action) + ',"--execute"],stdout=subprocess.PIPE,stderr=subprocess.DEVNULL)\nsys.stdout.buffer.write(r.stdout);sys.stdout.flush()\nassert r.returncode==0'
    else:
        code = PREAMBLE + {"layout": LAYOUT, "upload": UPLOAD, "sync": SYNC, "images": IMAGES, "vpn": VPN, "start": START, "status": STATUS, "diagnose": DIAGNOSE, "egress": EGRESS, "backup": BACKUP, "verify": VERIFY, "refresh-api": REFRESH_API, "api-check": API_CHECK, "legacy-check": VERIFY.split("# Two independent")[0], "api-publish": 'compose("chrona","config","--quiet")\ncompose("chrona","--profile","api","up","-d","--no-build","--pull","never","--wait","--wait-timeout","180","api")\nprint("API_LOOPBACK_REPUBLISH_OK")', "final-preflight": FINAL_PREFLIGHT, "data-permissions": DATA_PERMISSIONS, "docs": DOCS}[args.phase]
    # Errors may contain private network URLs: suppress raw exception/details.
    wrapped = "try:\n" + "\n".join(" " + line for line in code.splitlines()) + "\nexcept BaseException:\n print('PHASE_FAILED_DETAILS_SUPPRESSED'); sys.exit(1)\n"
    command = "sudo -S -p '' -- python3 -c " + shlex.quote(wrapped)
    try:
        result = subprocess.run(SSH + [command], input=secret+b"\n"+payload, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=1200)
        del secret, payload
        sys.stdout.buffer.write(result.stdout)
        return result.returncode
    except (OSError, subprocess.SubprocessError):
        print("SSH_OR_PHASE_TIMEOUT_DETAILS_SUPPRESSED; inspect current own state before retry")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

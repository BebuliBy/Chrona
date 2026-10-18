#!/usr/bin/env python3
"""Scoped approved APK distributor + separate API rollout driver.

Dry-run default. No app login/TG/sync requests. Local state holds public artifact
metadata only; SSH credential is private stdin and never persisted/printed.
"""
import argparse
import base64
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
import tarfile
import tempfile

SOURCE = Path(__file__).resolve().parent
MAIN = SOURCE.parents[1]
APK_SHA = "87d3c606c565b9e2e09db780806612d7388552a51e8227e47c8eb1709f31250c"
API_SHA = "c1cc832af4693932ddd5dd4b79a86f36ec78d403bf8f4134cf56629e23c04589"
SDK = Path("/home/niki/Android/Sdk/build-tools/36.0.0")

PREAMBLE = r'''
import base64,hashlib,http.client,io,json,os,pathlib,re,socket,stat,subprocess,sys,tarfile,time,zipfile
P=pathlib.Path
raw=sys.stdin.buffer.read();parts=raw.partition(b"DISTRIBUTION_EXEC_V1\n");assert parts[1];payload=json.loads(parts[2]);del raw,parts
def run(args,**kw):
 kw.setdefault("stdin",subprocess.DEVNULL)
 return subprocess.run(args,check=True,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=kw.pop("timeout",180),**kw).stdout
def docker(*args):return run(["/usr/bin/docker",*args])
def guarded(path):
 p=P(path)
 for q in [p,*p.parents]:
  trusted_srv=q==P("/srv") and q.exists() and str(q.stat().st_uid)==os.environ.get("SUDO_UID")
  if q.is_symlink() or q.exists() and (q.stat().st_uid!=0 and not trusted_srv or q.stat().st_mode&0o022):raise RuntimeError("PATH_OWNER_OR_SYMLINK_GUARD")
 return p
def write_new(path,data,mode=0o600):
 guarded(P(path).parent);fd=os.open(path,os.O_WRONLY|os.O_CREAT|os.O_EXCL|os.O_NOFOLLOW,mode)
 with os.fdopen(fd,"wb") as f:f.write(data);f.flush();os.fsync(f.fileno())
def replace(path,data):
 p=guarded(path);t=p.with_name(p.name+".stage-"+str(time.time_ns()));write_new(t,data,stat.S_IMODE(p.stat().st_mode));os.replace(t,p)
def inspect(name,field):return docker("inspect","--format",field,name).decode().strip()
def compose(*args):return docker("compose","--project-name","chrona-distribution","--env-file","/etc/chrona-distribution/runtime.env","-f","/opt/chrona-distribution/compose.yml",*args)
def app(*args):return docker("compose","--project-name","chrona","--env-file","/etc/chrona/runtime.env","-f","/opt/chrona/infra/compose.app.yml",*args)
sys.path.insert(0,"/opt/public-gateway");import gateway as g
'''

INSTALL = r'''
stage="fresh_guards"
for path in ("/opt/chrona-distribution","/etc/chrona-distribution","/srv/chrona-distribution","/var/backups/chrona-distribution"):
 p=guarded(path)
 if p.exists():raise RuntimeError("UNOWNED_DISTRIBUTION_PATH_CONFLICT")
if run(["ss","-H","-lnt","sport = :18081"]).strip():raise RuntimeError("LOOPBACK_PORT18081_CONFLICT")
if docker("ps","-a","--filter","label=com.docker.compose.project=chrona-distribution","--format","{{.Names}}").strip():raise RuntimeError("DISTRIBUTION_PROJECT_CONFLICT")
if "chrona-distribution-ingress" in docker("network","ls","--format","{{.Name}}").decode().splitlines():raise RuntimeError("DISTRIBUTION_NETWORK_CONFLICT")
cached="nextcloud@sha256:6df481c0861b87366086df72a63a8f041e3680c715edb12f8444902223b51c09"
docker("image","inspect","--format","{{.Id}}",cached)
cfg,etag=g.api_get();state=g.state_read();g.adoption_guard(cfg,state)
if g.private_view(cfg)!=state["protected_config"]:raise RuntimeError("PRIVATE_SERVE_DRIFT")
root=g.registry().get("chrona")
if not root or root["prefix"]!="/":raise RuntimeError("ROOT_OWNER_NOT_EXPECTED")
for route in g.registry().values():
 if route["prefix"]!="/" and (route["prefix"].startswith("/updates/chrona/") or "/updates/chrona/".startswith(route["prefix"])):raise RuntimeError("PUBLIC_PREFIX_CONFLICT")
base={"time":time.time(),"components":{}}
for name in ("chrona-api-1","chrona-bot-1","chrona-db-1","chrona-proxy-xray-proxy-1","nextcloud-app","nextcloud-db","nextcloud-redis","nextcloud-cron"):
 base["components"][name]={"id":inspect(name,"{{.Id}}"),"image":inspect(name,"{{.Image}}")}
for path,mode in (("/opt/chrona-distribution",0o755),("/etc/chrona-distribution",0o700),("/srv/chrona-distribution",0o700),("/srv/chrona-distribution/staging",0o700),("/srv/chrona-distribution/published",0o755),("/srv/chrona-distribution/published/releases",0o755),("/var/backups/chrona-distribution",0o700)):
 P(path).mkdir(mode=mode)
write_new("/var/backups/chrona-distribution/prestate.json",json.dumps(base).encode())
g.no_auth_fields(cfg);g.snapshot("apk-prefix-before",cfg)
write_new("/var/backups/chrona-distribution/gateway-policy-before.json",(g.BASE/"policy.json").read_bytes())
write_new("/var/backups/chrona-distribution/gateway-root-consumer-before.json",(g.BASE/"consumers/chrona.json").read_bytes())
write_new("/var/backups/chrona-distribution/gateway-state-before.json",(g.BASE/"state.json").read_bytes())
for name,value in payload["files"].items():
 rel=P(name)
 if rel.is_absolute() or ".." in rel.parts:raise RuntimeError("SOURCE_PATH_GUARD")
 target=P("/opt/chrona-distribution")/rel;target.parent.mkdir(mode=0o755,parents=True,exist_ok=True)
 data=base64.b64decode(value)
 if rel.suffix==".py":compile(data,str(rel),"exec")
 write_new(target,data,0o644)
meta=payload["meta"]
policy={"packageName":meta["packageName"],"signingCertificateSha256":meta["signingCertificateSha256"],"minimumVersionCode":6,"maximumSizeBytes":268435456}
write_new("/etc/chrona-distribution/policy.json",json.dumps(policy).encode())
write_new("/etc/chrona-distribution/runtime.env",("PUBLISHER_BASE="+cached+"\nDISTRIBUTION_IMAGE=NOT_BUILT\nDISTRIBUTION_PORT=18081\n").encode())
stage="php_syntax_and_image_build"
docker("build","--pull=false","--network","none","--build-arg","PUBLISHER_BASE="+cached,"-t","chrona-distribution:2026-10-18","/opt/chrona-distribution")
image=docker("image","inspect","--format","{{.Id}}","chrona-distribution:2026-10-18").decode().strip()
docker("run","--rm","--network","none","--read-only","--user","10002:10002","--cap-drop","ALL","--entrypoint","/usr/local/bin/php",image,"-l","/opt/chrona-distribution/server.php")
replace("/etc/chrona-distribution/runtime.env",("PUBLISHER_BASE="+cached+"\nDISTRIBUTION_IMAGE="+image+"\nDISTRIBUTION_PORT=18081\n").encode())
compose("config","--quiet")
print("DISTRIBUTOR_LAYOUT_BACKUPS_POLICY_SOURCE_PHP_SYNTAX_IMAGE_CONFIG_PASS",flush=True)
print("DISTRIBUTOR_IMAGE="+image,flush=True)
'''

START = r'''
stage="publisher_start"
if not P("/srv/chrona-distribution/published/latest.json").exists():raise RuntimeError("NO_PUBLISHED_MANIFEST")
compose("up","-d","--no-build","--pull","never","--wait","--wait-timeout","120","publisher")
import urllib.request,urllib.error
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def get(path,method="GET"):
 try:
  with opener.open(urllib.request.Request("http://localhost:18081"+path,method=method),timeout=10) as r:return r.status,r.read(20000),dict(r.headers)
 except urllib.error.HTTPError as e:return e.code,e.read(20000),dict(e.headers)
status,body,headers=get("/latest.json")
if status!=200 or json.loads(body)!=payload["meta"]:raise RuntimeError("LOCAL_MANIFEST_MISMATCH")
for path in ("/latest.json","/latest.apk","/"+payload["meta"]["apkPath"]):
 status,body,headers=get(path,"HEAD")
 if status!=200:raise RuntimeError("LOCAL_HEAD_FAILED")
for path in ("/releases/","/server.php","/../policy.json","/%2e%2e/policy.json","/releases/Chrona-0.apk","/releases/Chrona-999999.apk"):
 status,body,headers=get(path)
 print("LOCAL_NEGATIVE_PATH="+path+" STATUS="+str(status),flush=True)
 if status not in (400,404):raise RuntimeError("LOCAL_PATH_ALLOWLIST_FAILED")
request=urllib.request.Request("http://localhost:18081/latest.json",data=b"",method="POST")
try:opener.open(request,timeout=5);raise RuntimeError("HTTP_UPLOAD_NOT_REJECTED")
except urllib.error.HTTPError as e:
 if e.code!=405:raise RuntimeError("METHOD_GUARD_FAILED")
print("PUBLISHER_HEALTHY_LOCAL_MANIFEST_HEAD_PATH_METHOD_ALLOWLIST_PASS",flush=True)
'''

FIX_SOURCE = r'''
stage="own_publisher_source_fix"
if inspect("chrona-distribution-publisher-1",'{{index .Config.Labels "com.docker.compose.project"}}')!="chrona-distribution":raise RuntimeError("UNOWNED_PUBLISHER")
source_backup=P("/var/backups/chrona-distribution")/("source-before-fix-"+str(time.time_ns())+".tar")
with tarfile.open(source_backup,"w") as tar:tar.add("/opt/chrona-distribution",arcname="source")
os.chmod(source_backup,0o600)
for name,value in payload["files"].items():
 rel=P(name)
 if rel.is_absolute() or ".." in rel.parts:raise RuntimeError("SOURCE_PATH")
 target=guarded(P("/opt/chrona-distribution")/rel)
 data=base64.b64decode(value)
 if rel.suffix==".py":compile(data,str(rel),"exec")
 replace(target,data)
old=P("/etc/chrona-distribution/runtime.env").read_bytes()
write_new("/var/backups/chrona-distribution/runtime-before-fix-"+str(time.time_ns())+".env",old)
cached="nextcloud@sha256:6df481c0861b87366086df72a63a8f041e3680c715edb12f8444902223b51c09"
docker("build","--pull=false","--network","none","--build-arg","PUBLISHER_BASE="+cached,"-t","chrona-distribution:2026-10-18","/opt/chrona-distribution")
image=docker("image","inspect","--format","{{.Id}}","chrona-distribution:2026-10-18").decode().strip()
docker("run","--rm","--network","none","--read-only","--user","10002:10002","--cap-drop","ALL","--entrypoint","/usr/local/bin/php",image,"-l","/opt/chrona-distribution/server.php")
replace("/etc/chrona-distribution/runtime.env",("PUBLISHER_BASE="+cached+"\nDISTRIBUTION_IMAGE="+image+"\nDISTRIBUTION_PORT=18081\n").encode())
compose("config","--quiet")
print("OWN_PUBLISHER_SOURCE_RUNTIME_BACKUP_SYNTAX_NEW_IMAGE="+image,flush=True)
'''

STORE_CHECK = r'''
stage="isolated_store_integrity"
import contextlib,tempfile
sys.path.insert(0,"/opt/chrona-distribution");import publish_store as store
meta=payload["meta"];apk=P("/srv/chrona-distribution/published")/meta["apkPath"]
real=apk.read_bytes()
if hashlib.sha256(real).hexdigest()!=meta["sha256"]:raise RuntimeError("REAL_APK_DIGEST_NOT_APPROVED")
with tempfile.TemporaryDirectory(prefix="store-acceptance-",dir="/srv/chrona-distribution") as temp:
 base=P(temp);(base/"staging").mkdir(mode=0o700);(base/"published").mkdir(mode=0o755);(base/"published/releases").mkdir(mode=0o755);(base/"backups").mkdir(mode=0o700)
 policy={"packageName":meta["packageName"],"signingCertificateSha256":meta["signingCertificateSha256"],"minimumVersionCode":1,"maximumSizeBytes":268435456}
 write_new(base/"policy.json",json.dumps(policy).encode())
 store.BASE=base;store.DATA=base/"published";store.POLICY=base/"policy.json";store.BACKUPS=base/"backups"
 with contextlib.redirect_stdout(io.StringIO()):
  store.publish(io.BytesIO(real),meta);store.publish(io.BytesIO(real),meta)
 for altered,content in ((dict(meta,versionCode=5,apkPath="releases/Chrona-5.apk"),real),(dict(meta,versionName="different-same-code"),real),(dict(meta,signingCertificateSha256="0"*64),real),(meta,bytes([real[0]^1])+real[1:])):
  try:
   with contextlib.redirect_stdout(io.StringIO()):store.publish(io.BytesIO(content),altered)
  except ValueError:pass
  else:raise RuntimeError("UNSAFE_STORE_CASE_NOT_REJECTED")
 if json.loads((base/"published/latest.json").read_text())!=meta:raise RuntimeError("STAGING_LATEST_CHANGED_AFTER_REJECTION")
 if hashlib.sha256((base/"published"/meta["apkPath"]).read_bytes()).hexdigest()!=meta["sha256"]:raise RuntimeError("STAGING_IMMUTABLE_CHANGED")
print("ISOLATED_PRIVATE_STORE_IDEMPOTENT_DOWNGRADE_REUSE_SIGNER_CHANGED_BYTES_REJECTION_PASS_PUBLIC_STORE_UNCHANGED")
'''

DELEGATE = r'''
stage="gateway_delegation"
policy=g.policy_read();routes=g.registry();root=routes["chrona"];child=payload["consumer"]
if child["id"] in routes:raise RuntimeError("CONSUMER_ALREADY_REGISTERED_REVIEW_REQUIRED")
new_policy=dict(policy);new_policy["allowed_upstream_ports"]=sorted(set(policy["allowed_upstream_ports"]+[18081]))
new_root=dict(root);new_root["delegates"]=sorted(set(root.get("delegates",[])+["/updates/chrona/"]))
g.validate_routes(dict(routes,**{"chrona":new_root,child["id"]:child}),new_policy)
g.snapshot("policy-before-apk",policy);g.snapshot("registry-before-apk",routes)
g.save_json(g.BASE/"policy.json",new_policy,replace=True)
definition=g.BASE/"definitions/chrona.json"
if definition.exists():
 g.snapshot("root-definition-before-apk",g.read_json(definition));g.save_json(definition,new_root,replace=True)
else:g.save_json(definition,new_root)
g.save_json(g.BASE/"consumers/chrona.json",new_root,replace=True)
try:
 g.apply_one("chrona")
 g.save_json(g.BASE/("definitions/"+child["id"]+".json"),child)
 g.save_json(g.BASE/("consumers/"+child["id"]+".json"),g.definition(child,new_policy))
 g.apply_one(child["id"])
except BaseException:
 # Do not blindly restore full Serve or overwrite concurrent/unowned handlers.
 print("GATEWAY_CHILD_APPLY_FAILED_REVIEW_SAVED_SCOPED_BACKUPS_NO_RESET",flush=True);raise
print("ONLY_UPDATES_PREFIX_DELEGATED_APPLIED_ROOT_API18080_PRESERVED",flush=True)
'''

GATEWAY_CLI = r'''
stage="unchanged_generic_cli_confirmation"
tool=["/usr/bin/python3","/opt/public-gateway/gateway.py"]
for args in (("register","/etc/public-gateway/definitions/chrona.json","--execute"),("register","/etc/public-gateway/definitions/chrona-updates.json","--execute"),("validate","--execute"),("plan","--execute"),("apply","chrona-updates","--execute"),("status","--execute")):
 print(run(tool+list(args)).decode().strip(),flush=True)
print("UNCHANGED_GENERIC_CLI_REGISTER_VALIDATE_PLAN_SINGLE_CHILD_CAS_CONFIRMED")
'''

API = r'''
stage="api_backup_stage"
ROOT=P("/opt/chrona/infra");env=P("/etc/chrona/runtime.env");old=env.read_bytes();values=dict(line.split("=",1) for line in old.decode().splitlines() if line)
if values.get("TELEGRAM_AUTH_ENABLED")!="true" or not values.get("BOT_IMAGE"):raise RuntimeError("AUTH_WORKER_SPLIT_NOT_PRESERVED")
baseline={name:(inspect(name,"{{.Id}}"),inspect(name,"{{.Image}}")) for name in ("chrona-bot-1","chrona-db-1","chrona-proxy-xray-proxy-1","chrona-distribution-publisher-1")}
cfg,_=g.api_get();mapping=json.dumps(cfg,sort_keys=True)
stamp=str(time.time_ns());backup=P("/var/backups/chrona")/("api-namefix-"+stamp);backup.mkdir(mode=0o700)
write_new(backup/"runtime.env",old)
write_new(backup/"image-metadata.json",json.dumps({"prior_api_image":values["BACKEND_IMAGE"],"worker_image":values["BOT_IMAGE"]}).encode())
with tarfile.open(backup/"previous-source.tar","w") as a:a.add(ROOT/"backend",arcname="backend")
os.chmod(backup/"previous-source.tar",0o600)
run(["python3",str(ROOT/"scripts/ops.py"),"backup","--execute","--approved"])
release=P("/opt/chrona")/("api-namefix-"+stamp);release.mkdir(mode=0o755)
with tarfile.open(fileobj=io.BytesIO(base64.b64decode(payload["api_tar"])),mode="r:") as archive:
 for member in archive:
  rel=P(member.name)
  if rel.is_absolute() or ".." in rel.parts or not member.isfile() or member.issym() or member.islnk():raise RuntimeError("API_ARCHIVE_GUARD")
  target=release/rel;target.parent.mkdir(mode=0o755,parents=True,exist_ok=True);write_new(target,archive.extractfile(member).read(),0o755 if rel.name=="chrona-backend" else 0o644)
jar=release/"distribution/lib/chrona-backend-0.1.0.jar"
if hashlib.sha256(jar.read_bytes()).hexdigest()!=payload["api_sha"]:raise RuntimeError("API_JAR_SHA_MISMATCH")
with zipfile.ZipFile(jar) as z:checksum=base64.urlsafe_b64encode(hashlib.sha256(z.read("db/V001__foundation.sql")).digest()).decode().rstrip("=")
if app("exec","-T","db","psql","-U","chrona","-d","chrona","-Atc","SELECT checksum FROM schema_migrations WHERE version=1").decode().strip()!=checksum:raise RuntimeError("API_SQL_CHECKSUM_CHANGED")
pins=json.loads((ROOT/"verified-images-2026-10-18.json").read_text());jre="eclipse-temurin:17-jre-jammy@"+pins["jre"]["amd64_digest"]
docker("image","inspect","--format","{{.Id}}",jre)
docker("build","--pull=false","--network","none","--build-arg","JVM_IMAGE="+jre,"-t","chrona-backend:api-namefix-20261018",str(release))
new=docker("image","inspect","--format","{{.Id}}","chrona-backend:api-namefix-20261018").decode().strip()
values["BACKEND_IMAGE"]=new
try:
 replace(env,''.join(k+'='+v+'\n' for k,v in values.items()).encode())
 app("config","--quiet")
 app("--profile","api","up","-d","--no-deps","--no-build","--pull","never","--wait","--wait-timeout","180","api")
 if inspect("chrona-api-1","{{.State.Health.Status}}")!="healthy":raise RuntimeError("API_NOT_READY")
 for name,(identifier,image) in baseline.items():
  if (inspect(name,"{{.Id}}"),inspect(name,"{{.Image}}"))!=(identifier,image):raise RuntimeError("NON_API_COMPONENT_CHANGED")
 after,_=g.api_get()
 if json.dumps(after,sort_keys=True)!=mapping:raise RuntimeError("API_REFRESH_CHANGED_GATEWAY")
except BaseException:
 replace(env,old);app("--profile","api","up","-d","--no-deps","--no-build","--pull","never","--wait","--wait-timeout","180","api")
 print("API_ONLY_ROLLBACK_PREVIOUS_IMAGE_METADATA_RESTORED",flush=True);raise
print("API_NAMEFIX_HEALTHY_SQL_UNCHANGED_WORKER87_DB_PROXY_PUBLISHER_GATEWAY_UNCHANGED",flush=True)
print("API_IMAGE="+new,flush=True);print("API_ROLLBACK_DIRECTORY="+str(backup),flush=True)
'''

CHECK = r'''
stage="readonly_component_check"
base=json.loads(P("/var/backups/chrona-distribution/prestate.json").read_text())
for name,record in base["components"].items():
 if name=="chrona-api-1":continue
 if (inspect(name,"{{.Id}}"),inspect(name,"{{.Image}}"))!=(record["id"],record["image"]):raise RuntimeError("PRESERVED_COMPONENT_CHANGED")
cfg,_=g.api_get();state=g.state_read();g.adoption_guard(cfg,state)
if g.private_view(cfg)!=state["protected_config"]:raise RuntimeError("PRIVATE443_OR_OTHER_GATEWAY_MAPPING_DRIFT")
for name in ("chrona-api-1","chrona-bot-1","chrona-db-1","chrona-distribution-publisher-1"):
 if inspect(name,"{{.State.Health.Status}}")!="healthy":raise RuntimeError("OWN_COMPONENT_NOT_READY")
for name in ("chrona-api-1","chrona-distribution-publisher-1"):
 if inspect(name,"{{.HostConfig.ReadonlyRootfs}}")!="true" or "ALL" not in json.loads(inspect(name,"{{json .HostConfig.CapDrop}}")):raise RuntimeError("RUNTIME_SECURITY_PROFILE_WEAKENED")
 if "no-new-privileges:true" not in json.loads(inspect(name,"{{json .HostConfig.SecurityOpt}}")):raise RuntimeError("NO_NEW_PRIVILEGES_MISSING")
 if inspect(name,"{{.Config.User}}")!=("10001:10001" if name=="chrona-api-1" else "10002:10002"):raise RuntimeError("NONROOT_IDENTITY_MISMATCH")
 ports=json.loads(inspect(name,"{{json .HostConfig.PortBindings}}"))
 expected="18080" if name=="chrona-api-1" else "18081"
 if ports!={"8080/tcp":[{"HostIp":"127.0.0.1","HostPort":expected}]}:raise RuntimeError("HOST_BIND_NOT_LOOPBACK_ONLY")
run(["docker","exec","chrona-api-1","/bin/sh","-c",'test "$TELEGRAM_AUTH_ENABLED" = true'])
for path,mode in (("/etc/chrona-distribution",0o700),("/srv/chrona-distribution",0o700),("/var/backups/chrona-distribution",0o700)):
 if stat.S_IMODE(guarded(path).stat().st_mode)!=mode:raise RuntimeError("PRIVATE_DIRECTORY_MODE")
sys.path.insert(0,"/opt/chrona/infra/scripts");import serve
private_state,private_before=serve.read_state()
if not serve.unchanged_except_own(private_before,cfg,private_state["endpoint"]):raise RuntimeError("PRIVATE443_MAPPING_CHANGED")
if serve.nextcloud(private_state["endpoint"])!=private_state["nextcloud"]:raise RuntimeError("NEXTCLOUD_PRIVATE_TLS_STATUS_CHANGED")
print("OWN_COMPONENTS_HEALTHY_PRESERVED_IDS_IMAGES_PRIVATE443_ROOT_API_ROUTES_METADATA_PASS")
print("ONE_SCOPED_PRIVATE_NEXTCLOUD_ROOT_TRUSTED_TLS_STATUS_PASS")
'''

RECORD = r'''
stage="source_status_record_only"
backup=P("/var/backups/chrona-distribution")/("status-source-before-"+str(time.time_ns())+".tar")
with tarfile.open(backup,"w") as tar:tar.add("/opt/chrona-distribution/README.md",arcname="README.md")
os.chmod(backup,0o600)
for name,encoded in payload["files"].items():
 if name not in ("README.md","deployment.md"):raise RuntimeError("STATUS_DOCUMENT_ALLOWLIST")
 target=P("/opt/chrona-distribution")/name;data=base64.b64decode(encoded)
 if target.exists():replace(target,data)
 else:write_new(target,data,0o644)
print("OWN_PUBLISHER_STATUS_DOCS_RECORDED_NO_IMAGE_SERVICE_OR_GATEWAY_CHANGE")
'''


def sdk_metadata(apk):
    signer = subprocess.check_output([str(SDK / "apksigner"), "verify", "--print-certs", str(apk)], stderr=subprocess.DEVNULL, timeout=60).decode()
    certs = re.findall(r"certificate SHA-256 digest:\s*([a-fA-F0-9]{64})", signer)
    text = subprocess.check_output([str(SDK / "aapt"), "dump", "badging", str(apk)], stderr=subprocess.DEVNULL, timeout=30).decode()
    package = re.search(r"^package: name='([^']+)' versionCode='([1-9][0-9]*)' versionName='([^']*)'", text, re.M)
    sdk = re.search(r"^sdkVersion:'([1-9][0-9]*)'", text, re.M)
    if len(certs) != 1 or not package or not sdk:
        raise ValueError("PUBLIC_APK_METADATA_INVALID")
    return {"packageName": package[1], "versionCode": int(package[2]), "versionName": package[3], "minSdk": int(sdk[1]), "signingCertificateSha256": certs[0].lower()}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("phase", choices=("prepare", "install", "fix-source", "store-check", "start", "delegate", "gateway-cli", "api", "check", "record"))
    p.add_argument("--execute", action="store_true")
    p.add_argument("--approved", action="store_true")
    p.add_argument("--ssh-alias")
    p.add_argument("--password-stdin", action="store_true")
    p.add_argument("--state", type=Path)
    a = p.parse_args()
    if not a.execute:
        print("DRY-RUN deployment; no artifact/auth/runtime reads or network")
        return 0
    if not a.approved:
        p.error("explicit scope approval required")
    try:
        if a.phase == "prepare":
            folder = Path(tempfile.mkdtemp(prefix="chrona-distribution-code6-", dir="/tmp/opencode"))
            apk = MAIN / "chrona/build/outputs/apk/debug/chrona-debug.apk"
            frozen = folder / "Chrona-6.apk"
            fd = os.open(frozen, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
            with apk.open("rb") as src, os.fdopen(fd, "wb") as out:
                for block in iter(lambda: src.read(1048576), b""):
                    out.write(block)
                out.flush();os.fsync(out.fileno());os.fchmod(out.fileno(), 0o444)
            with frozen.open("rb") as src:
                sha = hashlib.file_digest(src, "sha256").hexdigest()
            if sha != APK_SHA:
                raise ValueError("APPROVED_APK_SHA256_MISMATCH")
            meta = sdk_metadata(frozen)
            if (meta["packageName"], meta["versionCode"], meta["versionName"], meta["minSdk"]) != ("by.bebuliby.chrona.debug", 6, "0.4.0-update", 26):
                raise ValueError("APPROVED_APK_IDENTITY_MISMATCH")
            # Historical project debug APK public signer, not a keystore/phone read.
            historical = MAIN / "app/build/outputs/apk/debug/app-debug.apk"
            if not historical.is_file() or sdk_metadata(historical)["signingCertificateSha256"] != meta["signingCertificateSha256"]:
                raise ValueError("HISTORICAL_PUBLIC_DEBUG_SIGNER_MISMATCH")
            meta.update({"schemaVersion": 1, "apkPath": "releases/Chrona-6.apk", "sha256": sha, "sizeBytes": frozen.stat().st_size, "mandatory": True})
            path = folder / "state.private.json"
            fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w") as out:
                json.dump({"frozen_apk": str(frozen), "meta": meta}, out)
            print("FROZEN_APK6_PUBLIC_METADATA_SHA_SAME_HISTORICAL_DEBUG_CERT_PASS")
            print("LOCAL_STATE=" + str(path))
            print(json.dumps(meta, sort_keys=True))
            return 0
        if not a.state or a.state.is_symlink() or not a.ssh_alias or not re.fullmatch(r"[A-Za-z0-9_-]+", a.ssh_alias) or not a.password_stdin:
            p.error("public-metadata state, explicit confirmed alias/private stdin required")
        state = json.loads(a.state.read_text())
        payload = {"meta": state["meta"]}
        if a.phase in ("install", "fix-source"):
            files = {}
            for name in ("Dockerfile", ".dockerignore", "server.php", "compose.yml", "publish_store.py", "release.py", "README.md", "empty-root/.keep"):
                files[name] = base64.b64encode((SOURCE / name).read_bytes()).decode()
            payload["files"] = files
        elif a.phase == "record":
            payload["files"] = {"README.md":base64.b64encode((SOURCE/"README.md").read_bytes()).decode(),"deployment.md":base64.b64encode((MAIN/"docs/apk-distribution-deployed-2026-10-18.md").read_bytes()).decode()}
        elif a.phase == "delegate":
            payload["consumer"] = json.loads((SOURCE / "consumer.json").read_text())
        elif a.phase == "api":
            dist = MAIN / "backend/build/install/chrona-backend"
            jar = dist / "lib/chrona-backend-0.1.0.jar"
            if hashlib.sha256(jar.read_bytes()).hexdigest() != API_SHA:
                raise ValueError("APPROVED_ROOT_API_JAR_MISMATCH")
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w") as tar:
                for file in sorted(dist.rglob("*")):
                    rel = file.relative_to(dist)
                    if file.is_file() and not file.is_symlink() and (str(rel) == "bin/chrona-backend" or len(rel.parts) == 2 and rel.parts[0] == "lib" and file.suffix == ".jar"):
                        tar.add(file, arcname="distribution/" + str(rel), recursive=False)
                context = SOURCE.parent / "backend"
                for name in ("Dockerfile", ".dockerignore"):
                    tar.add(context / name, arcname=name, recursive=False)
            payload.update({"api_tar": base64.b64encode(buffer.getvalue()).decode(), "api_sha": API_SHA})
        auth = sys.stdin.buffer.readline(1025).rstrip(b"\r\n")
        if not auth:
            raise ValueError("PRIVATE_AUTH_MISSING")
        body = PREAMBLE + {"install": INSTALL, "fix-source": FIX_SOURCE, "store-check": STORE_CHECK, "start": START, "delegate": DELEGATE, "gateway-cli": GATEWAY_CLI, "api": API, "check": CHECK, "record": RECORD}[a.phase]
        wrapped = "try:\n" + "\n".join(" " + line for line in body.splitlines()) + "\nexcept BaseException as error:\n print('DEPLOY_FAILED_STAGE='+globals().get('stage','init')+' CLASS='+type(error).__name__)\n if type(error) is RuntimeError:print('GUARD='+str(error))\n raise SystemExit(1)\n"
        ssh = ["/usr/bin/ssh", "-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=yes", "-o", "UpdateHostKeys=no", "-o", "ConnectTimeout=7", a.ssh_alias]
        result = subprocess.run(ssh + ["sudo -S -p '' -- python3 -c " + shlex.quote(wrapped)], input=auth + b"\nDISTRIBUTION_EXEC_V1\n" + json.dumps(payload).encode(), stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=600)
        del auth
        sys.stdout.buffer.write(result.stdout)
        return result.returncode
    except (OSError, ValueError, subprocess.SubprocessError):
        print("DISTRIBUTION_EXEC_FAILED_DETAILS_SUPPRESSED")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())

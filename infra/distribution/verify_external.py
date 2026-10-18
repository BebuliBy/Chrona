#!/usr/bin/env python3
"""Approved focused external APK GET/HEAD integrity probe; never login/phone/API writes.

HTTPS origin is private stdin, public artifact expected metadata file is explicit.
No literal infrastructure hostname stored in source or output by default.
"""
import argparse
import hashlib
import http.client
import ipaddress
import json
from pathlib import Path
import socket
import ssl
import struct
import sys
import urllib.parse


def exact(sock, n):
    data = b""
    while len(data) < n:
        part = sock.recv(n - len(data))
        if not part:
            raise ValueError("EOF")
        data += part
    return data


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--execute", action="store_true")
    p.add_argument("--state", type=Path)
    p.add_argument("--socks-port", type=int, default=10808)
    p.add_argument("--print-link", action="store_true")
    p.add_argument("--head-only", action="store_true", help="post-API-refresh metadata repeat, no second APK download")
    a = p.parse_args()
    if not a.execute:
        print("DRY-RUN external distribution check; no stdin/files/network")
        return 0
    stage = "origin_dns"
    try:
        meta = json.loads(a.state.read_text())["meta"]
        origin = sys.stdin.buffer.readline(2048).decode().strip()
        uri = urllib.parse.urlsplit(origin)
        if uri.scheme != "https" or uri.port != 8443 or not uri.hostname or not uri.hostname.endswith(".ts.net") or uri.path != "/updates/chrona/" or uri.username or uri.password or uri.query or uri.fragment:
            raise ValueError("origin")
        # Existing outside-tailnet SOCKS + public DoH helper, unchanged generic code.
        sys.path.insert(0,"/home/niki/public-gateway")
        import public_probe
        query = "/dns-query?name=" + urllib.parse.quote(uri.hostname, safe="") + "&type=A"
        status, body = public_probe.request(a.socks_port,"cloudflare-dns.com",443,query,dns_json=True)
        dns = json.loads(body)
        ips = [row["data"] for row in dns.get("Answer",[]) if row.get("type")==1]
        if status!=200 or dns.get("Status")!=0 or not ips or any(not ipaddress.ip_address(ip).is_global for ip in ips):
            raise ValueError("global_dns")
        address = ipaddress.ip_address(ips[0])

        def request(path, method="GET", stream_hash=False):
            with socket.create_connection(("localhost",a.socks_port),timeout=15) as sock:
                sock.settimeout(60)
                sock.sendall(b"\x05\x01\x00")
                if exact(sock,2)!=b"\x05\x00":raise ValueError("socks")
                sock.sendall(b"\x05\x01\x00\x01"+address.packed+struct.pack("!H",8443))
                head=exact(sock,4)
                if head[:3]!=b"\x05\x00\x00":raise ValueError("connect")
                n=4 if head[3]==1 else 16 if head[3]==4 else exact(sock,1)[0]
                exact(sock,n+2)
                with ssl.create_default_context().wrap_socket(sock,server_hostname=uri.hostname) as tls:
                    tls.sendall((method+" "+path+" HTTP/1.1\r\nHost: "+uri.hostname+":8443\r\nConnection: close\r\n\r\n").encode())
                    r=http.client.HTTPResponse(tls);r.begin();headers=dict((k.lower(),v) for k,v in r.getheaders())
                    if stream_hash:
                        digest=hashlib.sha256();size=0
                        while True:
                            chunk=r.read(1048576)
                            if not chunk:break
                            size+=len(chunk)
                            if size>meta["sizeBytes"]:raise ValueError("size_bound")
                            digest.update(chunk)
                        return r.status,(size,digest.hexdigest()),headers
                    return r.status,r.read(65536),headers

        prefix=uri.path
        stage="index_manifest"
        status,body,headers=request(prefix)
        if status!=200 or b"latest.apk" not in body:raise ValueError("index")
        status,body,headers=request(prefix+"latest.json")
        if status!=200 or json.loads(body)!=meta or "no-store" not in headers.get("cache-control",""):raise ValueError("manifest")
        if not meta["apkPath"].startswith("releases/Chrona-") or ".." in meta["apkPath"] or ":" in meta["apkPath"]:raise ValueError("relative_path")
        print("PUBLIC_GLOBAL_RELAY_TRUSTED_TLS_INDEX_MANIFEST_SCHEMA6_PUBLIC_METADATA_PASS")
        stage="head_and_artifact_hash"
        for path in ("latest.apk",meta["apkPath"]):
            status,body,headers=request(prefix+path,"HEAD")
            if status!=200 or int(headers.get("content-length","0"))!=meta["sizeBytes"] or "location" in headers:raise ValueError("head_redirect_size")
            if not a.head_only:
                status,result,headers=request(prefix+path,stream_hash=True)
                if status!=200 or result!=(meta["sizeBytes"],meta["sha256"]):raise ValueError("artifact_integrity")
            if path==meta["apkPath"] and "immutable" not in headers.get("cache-control",""):raise ValueError("immutable_cache")
            print(("PUBLIC_HEAD_NO_REDIRECT_SIZE_METADATA_MATCH=" if a.head_only else "PUBLIC_GET_HEAD_NO_REDIRECT_APK_HASH_SIZE_MATCH=")+path)
        stage="negative_paths"
        for path in ("releases/","server.php","publish_store.py","policy.json","staging/","releases/Chrona-0.apk","releases/Chrona-999999.apk","releases/Chrona-6.json"):
            status,body,headers=request(prefix+path)
            if status not in (400,403,404):raise ValueError("unowned_path")
        for path in ("%2e%2e/policy.json","../policy.json"):
            status,body,headers=request(prefix+path)
            if status not in (301,302,307,308,400,403,404):raise ValueError("traversal")
        status,body,headers=request(prefix+"latest.json","POST")
        if status!=405:raise ValueError("upload_method")
        status,body,headers=request("/health/ready")
        if status!=200 or json.loads(body).get("status")!="ready":raise ValueError("root_api")
        status,body,headers=request("/v1/me")
        if status!=401:raise ValueError("root_auth")
        print("PUBLIC_PATH_NO_DIRECTORY_SOURCE_POLICY_SIDECAR_UPLOAD_ROOT_API200_401_PRESERVED")
        print("EXTERNAL_POST_REFRESH_METADATA_ACCEPTANCE_PASS_PRIOR_APK_HASH_PROOF_REUSED" if a.head_only else "EXTERNAL_APK6_MANIFEST_DOWNLOAD_INTEGRITY_ACCEPTANCE_PASS_NO_LOGIN_REQUEST")
        if a.print_link:print("PUBLIC_STABLE_APK_LINK="+origin+"latest.apk")
        return 0
    except (OSError,ValueError,KeyError,http.client.HTTPException) as error:
        print("EXTERNAL_DISTRIBUTION_FAILED_STAGE="+stage+" CLASS="+type(error).__name__+" DETAILS_SUPPRESSED")
        return 1


if __name__=="__main__":
    raise SystemExit(main())

#!/usr/bin/env python3
"""Resolve official public image manifests without Docker/pull; never print auth tokens."""
import argparse
import hashlib
import json
import re
import urllib.error
import urllib.parse
import urllib.request

ACCEPT = ", ".join(("application/vnd.oci.image.index.v1+json", "application/vnd.docker.distribution.manifest.list.v2+json", "application/vnd.oci.image.manifest.v1+json", "application/vnd.docker.distribution.manifest.v2+json"))


def request(url, token=None, accept=None):
    headers = {"User-Agent": "Chrona-infra-manifest-audit"}
    if token:
        headers["Authorization"] = "Bearer " + token
    if accept:
        headers["Accept"] = accept
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30)


def resolve(registry, repository, tag):
    endpoint = "https://" + registry + "/v2/" + repository + "/manifests/"
    token = None
    try:
        response = request(endpoint + tag, accept=ACCEPT)
    except urllib.error.HTTPError as error:
        if error.code != 401:
            raise
        challenge = error.headers.get("WWW-Authenticate", "")
        attrs = dict(re.findall(r'(\w+)="([^"]+)"', challenge))
        realm = attrs.get("realm", "")
        allowed = {"auth.docker.io", "ghcr.io"}
        parsed = urllib.parse.urlparse(realm)
        if parsed.scheme != "https" or parsed.hostname not in allowed:
            raise ValueError("untrusted registry auth realm")
        query = urllib.parse.urlencode({"service": attrs.get("service", registry), "scope": "repository:" + repository + ":pull"})
        with request(realm + "?" + query) as auth:
            body = json.load(auth)
            token = body.get("token") or body["access_token"]
        response = request(endpoint + tag, token=token, accept=ACCEPT)
    with response:
        payload = response.read()
        digest = "sha256:" + hashlib.sha256(payload).hexdigest()
        if response.headers.get("Docker-Content-Digest") not in (None, digest):
            raise ValueError("manifest content digest mismatch")
        manifest = json.loads(payload)
    candidates = [entry for entry in manifest.get("manifests", []) if entry.get("platform", {}).get("os") == "linux" and entry["platform"].get("architecture") == "amd64" and entry["platform"].get("variant", "") in ("", "v1")]
    if len(candidates) != 1:
        raise ValueError("exact baseline linux/amd64 manifest required")
    selected = candidates[0]["digest"]
    with request(endpoint + selected, token=token, accept=ACCEPT) as response:
        payload = response.read()
        if "sha256:" + hashlib.sha256(payload).hexdigest() != selected:
            raise ValueError("platform manifest digest mismatch")
    return {"index_digest": digest, "amd64_digest": selected, "arch": "linux/amd64", "cpu_runtime": "NOT_TESTED"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="public registry GETs only; no images downloaded")
    args = parser.parse_args()
    if not args.execute:
        print("DRY-RUN: official Xray/Temurin17/Postgres17 manifest metadata; no network")
        return 0
    failed = False
    for registry, repo, tag in (("ghcr.io", "xtls/xray-core", "26.3.27"), ("registry-1.docker.io", "library/eclipse-temurin", "17-jre-jammy"), ("registry-1.docker.io", "library/postgres", "17-alpine")):
        reference = registry + "/" + repo + ":" + tag
        try:
            print(json.dumps({"reference": reference, **resolve(registry, repo, tag)}, sort_keys=True))
        except (OSError, ValueError, KeyError):
            print(json.dumps({"reference": reference, "status": "UNVERIFIED", "details": "suppressed"}))
            failed = True
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())

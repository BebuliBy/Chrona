# First public-gateway consumer: Chrona

Generic gateway source is **not** part of this app. Canonical local
**sourcePath: `/home/niki/public-gateway`** (durable non-Git project), server
`/opt/public-gateway` with protected
registry `/etc/public-gateway`. `public-consumer.json` is the only app registration:
root HTTP route → allowlisted loopback API, no grants for other prefixes.

The earlier `/tmp/opencode/public-gateway` was only the staging source. Do not
copy generic Python source/tests into this app: retain this pointer and consumer
definition only. The durable source copy did not redeploy/restart the server.

Private Serve443 Nextcloudroot+/chrona stays private. Public Funnel8443 contains
ONLY independent gateway handlers. Xray10808 is separate outbound proxy, not ingress.
No backend/API/PG/bot image, data, token or app source is imported by the gateway.

Publication applies explicit registry consumer and external DNS/global-IP/TLS,
health200/protected401, negative Nextcloud paths/private443 checks. No synthetic
auth-begin when Telegram auth enabled. Do not publish management/DB/proxy ports.

After those checks, an explicit `export-origin chrona --ack-origin-change` backs
up `/etc/chrona/client-origin` and writes the public root base URL (no `/chrona/`
on new8443 origin). Port/origin change means new login scope: client must be
reconfigured and login again, **no bearer/session merge** or SQL data changes.
Owner had no confirmed login at publication handoff; do not silently assume that
for later migrations.

Public root API is an intentionally limited pilot, not production assurance:
rate limits behind one proxy IP, registration abuse/storage/GC, real owner login
and cross-owner/privacy/sharing tests, monitoring and offhost backups remain.
Gateway does not disable API auth or start/send Telegram messages.

Old private Serve guard accepts additional public8443 **only** when exact current
port state matches root0600 independent gateway state; it still rejects Funnel443
and unknown8443 handlers. Canonical origin may match either protected private
origin or registered managed public origin. Rollback of private /chrona must not
reset/delete public gateway or Nextcloudroot.

For another consumer, root registry needs explicit upstream allowlist approval
and root-owner delegation of a nonoverlapping prefix; do not add app-specific
logic to generic gateway source. Public hostname/origin stays only in protected
server state and owner-private result, not this repository.

## Actual public pilot — 2026-10-18

Independent source/registry installed, root consumer published only on8443. Initial
native CLI required no extra owner/admin action; actual idempotent scoped LocalAPI
ETag CAS also PASS. No second service was published; two-route/remove proof is
unit/local-fake only. Source14 tests PASS; this app's corrected private guards34
tests PASS. No app/worker/PG/proxy image/container restart or account changes.

Outside-tailnet proof via existing SOCKS+public DoH, then explicit global relay IP
with real SNI/CA: health200, protected401;8443status/login/DAV404, Host header cannot
escape into private443, external443 TLS denied. Old Nextcloudroot trustedTLS and
private /chrona still work. API/worker/PG/proxy healthy; no authbegin/TG messages/
owner login/sync performed. Canonical client-origin updated **after external PASS**
with old-origin backup/explicit scope-change acknowledgement; root public base URL
has **no /chrona prefix**. Owner/client must select new origin and login afresh,
not silently reuse/merge a prior private-origin session.

This supersedes earlier bootstrap instructions that described the canonical client
origin as private443 `/chrona/`: that private route still exists, but the **current
canonical client-origin file now points at public8443 root `/`**. Read the protected
file as-is; do not append the former prefix. Actual hostname is not recorded here.

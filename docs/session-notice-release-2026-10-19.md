# Session/notice release8 — publication checkpoint

Root сообщил владельцу готовность0.4.2, причины source-дефектов и обновление из
Chrona. Native установка8/результат сообщений пока не подтверждены. До сборки
preflight обнаружил отсутствие временного build-origin файла: canonical значение
восстановлено безопасным read-only способом, blank/fake origin не собирался.
После этого единственный assembleDebug45s PASS; дополнительных тестов/lint нет.

2026-10-19, ID chrona-session-notice-release8-20261019, chain archi → sysadmin.
User-approved delivery only through existing in-app updater. Root provided MAIN
build success45s (no tests/lint); agent did not build, install or touch phone.

## Published artifact

Standard `infra/distribution/release.py` froze approved MAIN APK, used existing
SDK aapt/apksigner public verification and unchanged server package/cert policy.
No signing/private key read, new identity, metadata/hash fallback or policy edit.

| Field | Verified |
|---|---|
| versionCode/versionName | 8 / `0.4.2-session` |
| packageName/minSdk | `by.bebuliby.chrona.debug` / 26 |
| sizeBytes | 12830255 |
| APK SHA256 | `47c295edc8c1c026655d8b81ede9559dbeca1eec593733a911b40c2e87a3653b` |
| same public signer SHA256 | `8aba1c7b81060405fa140d4516f93628271af510fbf2e60582993f14b5061532` |
| immutable relative path | `releases/Chrona-8.apk` |
| latest manifest | schema1/version8/mandatorytrue, relative path/no hostname |

Existing store wrote immutable APK+metadata, fsync/exclusive, prior manifest
backup then atomic latest.json LAST. Code6 and7 remain with their original
approved byte hashes (verified on server), not overwritten/deleted.

## Minimal real acceptance

- Locally published metadata matched frozen8 identity/size/signer/hash.
- External existing SOCKS remoteDNS/trusted TLS HTTP2 manifest8GET200,
  0redirects,8.782054s; latest.apk HEAD200/exact size/no redirects,0.627634s.
- **One** immutable APK8 GET: HTTP200/2,0redirects, normal libcurl response
  completion38.505227s, exact12830255/hash above (not readExactly then early close).
- Publisher image unchanged
  `sha256:d5a947b173f56347600481532709ed65460e011b8a79e6b7f6d2c933df1ab24d`.
  No image/pull/rebuild/restart/service/port/consumer/policy changes. API/backend/
  DB/worker/gateway/private443 never changed by this publication; no auth/sync/
  TG/fake user events or peer-data reads. No old security matrix/testsuites repeated.

## User acceptance / limits

Root describes source8 removal of forced network-disconnect/false-offline,
serialized/noncancellable bounded refresh persistence, generation/stale notice
guards and five-second UI cadence. This report verifies publication only, **not
healed live logout/transparent lost-refresh recovery**; server rotation contract
remains separate. Native onStart gate/download/installer/session preservation and
notices require owner's own app actions. Agent did not perform USB/ADB/install or
declare the update installed. User opens Chrona for normal in-app update; no new
manual browser delivery or target-editor workflow is introduced.

Rollback: old manifest backed up under protected
`/var/backups/chrona-distribution/latest-before-*.json`; old immutable releases
retained. No same-code replacement/digest tricks/lower-version manifest to bypass
the valid mandatory8 gate. Bad release requires approved higher code/same signer;
corrupt pointer recovery only to verified same-version data. No rollback needed.
Actual public hostname/link remains runtime-only, not this doc/source. No source,
Git/index/shared README/changelog/model/agent operations beyond this own report.

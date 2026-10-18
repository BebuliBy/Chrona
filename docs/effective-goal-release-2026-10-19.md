# Effective Shorts goal — release7 delivery checkpoint

Root complement: `:chrona:assembleDebug` **BUILD SUCCESSFUL1m13s** и backend
`installDist` **BUILD SUCCESSFUL14s**, tests/lint не запускались. User получил
инструкцию открыть Chrona→mandatory0.4.1→Обновить→Android source permission/install.
USB/ADB не использовались. После этого владелец сообщил: **«Обновление у меня
произошло успешно внутри приложения»**. In-app install принят по его подтверждению;
отображение перенесённого лимита отдельно не проверяли. Native/DB/phone reads не было.

2026-10-19, ID `chrona-effective-goal-release7-20261019`, chain archi → sysadmin.
User approved effective goal semantics and delivery **through the in-app updater**.
Business implementation/build supplied by root; sysadmin did not change business
source, goal values/history, users/grants, UI or phone. No ADB/USB/install/rootbuild/
testsuite/lint/Git/agents/model checks. Publication A completed before API B.

## A. Standard APK7 publication — completed

Used existing `infra/distribution/release.py`, not legacy prepare/install bootstrap.
Approved MAIN APK frozen before SDK public-metadata verification/SSH transfer:

| Field | Verified value |
|---|---|
| versionCode / versionName | 7 / `0.4.1-goals` |
| packageName / minSdk | `by.bebuliby.chrona.debug` / 26 |
| sizeBytes | 12813875 |
| APK SHA256 | `91b4f6a8e1bc85089ddf20c1382336ca221869069d7fd43bc4efd39aff0df31b` |
| public certificate SHA256 | `8aba1c7b81060405fa140d4516f93628271af510fbf2e60582993f14b5061532` |
| immutable relative path | `releases/Chrona-7.apk` |
| manifest | schema1, `mandatory:true`, no hostname/absolute APK URI |

Existing server policy/cert pin unchanged. Keystore/private keys not read or
transferred. Standard root store verified hash/size/identity/version against
unchanged policy, wrote immutable APK/metadata with exclusive/fsync, backed up
old manifest, then switched latest.json **LAST atomically**. No Root CLI/image/
ports/consumer/delegation/policy changes or publisher restart. Code6 file SHA
still equals previous approved87d3…50c; old releases retained.

### Minimal actual acceptance (not whole old matrix)

- Published latest metadata7 exact identity/size/hash/signer/minSdk/mandatory.
- External existing SOCKS remoteDNS/trusted TLS, HTTP2 manifest7 GET200,
  redirects0,0.687112s; latest.apk HEAD200/exact12813875/redirects0,0.604608s.
- **One** immutable APK7 GET completed normally via libcurl HTTP2 (not forced
  readExactly/early close), HTTP200/redirects0,26.633730s, exact size/SHA above.
- No extra fixture/old failure/restore suites, authbegin/login/sync/message tests
  or phone actions. This is publication/integrity evidence, not native update UX.

Code6 should discover newer mandatory7 on its normal onStart according to core
implementation; user will open Chrona and accept Android installer steps. Agent
did **not** trigger in-app fetch/installer or claim installed/native mandatory
success. Stable public download/manifest routes unchanged; actual hostname/link
not saved in this doc/source. Owner already has the same download link.

## B. Separate API-only aligned readmodels — completed

MAIN backend installDist app JAR verified locally and after transfer:
`6aca65f0ba030b112bf305ce4fff99013246ac9875535620767841626f7ac18e`.
Root reported fresh installDist build14s without tests; sysadmin did not rebuild
root/test it. Compiled SQL resource matched live version1/checksum before image.

New API image:
`sha256:c49af50ae0febc8764ce57ef76601f32be61ac72b488cd45a3ce0a283ac3bb01`.
Only BACKEND_IMAGE and API `up --no-deps` changed. Existing cached pinned JRE17
used with --pull=false/networknone; no packages/downloads. Actual auth mode and
worker BOT_IMAGE87 preserved. Worker/DB/proxy/publisher IDs/images and complete
Serve/gateway configuration compared unchanged. No new ports/consumers/policy/
services/Nextcloud/private443 changes. Native API readiness healthy, external
trusted HTTPS root health200/protected401 after refresh, no fake login/user data.

### Minimal operator source guard update

Existing `infra/distribution/execute.py api` now requires explicit approved
`--api-sha256` instead of stale prior-release constant, accepts API-only phase
without a code6 APK state file, and tags prior API for retained rollback. Tag for
new image derives from public JAR digest. Backup also locates actual active API
source context under own release dirs by matching its app-JAR hash, not merely
the original bootstrap context. No new deployment bootstrap, no generic tool
or business source changes. Initial APK publisher bootstrap phases not rerun.

## Backups / rollback / remaining limits

- A: previous latest.json protected backup under
  `/var/backups/chrona-distribution/latest-before-*.json`; code6 immutable bytes
  retained. Do not re-sign/replace code7 bytes, fake a digest, publish a lower
  version or remove old artifacts. A bad release requires a separately approved
  higher version/same signer; pointer recovery only to verified same version.
  Do not silently downgrade the already valid mandatory7 gate.
- B: exact rollback material
  `/var/backups/chrona/api-release-1792361965847354197/` includes prior runtime,
  image metadata, source archive with matched active source and consistent own
  DB backup performed by existing runbook. Prior image
  `sha256:21aea9b80b2547c6c2f0d0ca41c63daef1465e03578c9df07d206a203bc40582`
  retained/tagged `chrona-backend:api-rollback-1792361965847354197`.
  Restore API image/runtime and `up API --no-deps` only; do not recreate worker87,
  publisher/PG/proxy or restore old raw data/schema. Rollback not needed/executed.
- API-only rollback would leave client7 published and may temporarily misalign
  friend readmodel semantics; it is component recovery, not automatic feature
  rollback. Real effective-goal/history/friend UI and in-app mandatory install
  still require owner acceptance; data/ACL/actual user values were not queried.
- No auth/messages/sync/ACL events, keys, private target values or credential
  values were produced/saved. Secrets only authorized stdin. Existing gateway/
  publisher beta/bandwidth/CPU/production limitations unchanged.

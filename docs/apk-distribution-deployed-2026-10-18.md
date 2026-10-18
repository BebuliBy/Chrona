# APK distribution и отдельный API refresh — deployed checkpoint

2026-10-18, ID `chrona-public-apk-exec-20261018`, chain archi → sysadmin.
User подтвердил multi-component plan. Никаких Git/index/shared README/CHANGELOG/
integration edits, phone/agents/model checks, signing key reads/transfers, login/
authbegin/Telegram/sync/ACL событий от агента. Full testsuites не повторялись;
ниже focused deploy acceptance, не Android E2E/security SLA.

## A. APK6 distributor — deployed отдельно

### Frozen public artifact / signer

Source: MAIN `chrona/build/outputs/apk/debug/chrona-debug.apk`, **не code5**.
Замороженные bytes verified existing SDK aapt/apksigner public metadata:

| Поле | Фактическое значение |
|---|---|
| versionCode / versionName | 6 / `0.4.0-update` |
| packageName / minSdk | `by.bebuliby.chrona.debug` / 26 |
| sizeBytes | 12813867 |
| APK SHA256 | `87d3c606c565b9e2e09db780806612d7388552a51e8227e47c8eb1709f31250c` |
| public certificate SHA256 | `8aba1c7b81060405fa140d4516f93628271af510fbf2e60582993f14b5061532` |

Signer matched historical project debug APK public cert. Это не новый signer и
не phone/installed-cert inspection: owner/root ранее подтвердил same debugcert.
Никакой debug.keystore/password/private key не читался/копировался/публиковался.
APK SHA проверен на frozen source, после повторного freeze в release operator,
на server transfer и у обоих реально скачанных public APK endpoints. Package/
version/minSdk/public signer verified до передачи SDK tools; SHA связывает эти
public metadata с одними и теми же bytes. App userdata/session/token не копировались.

### Runtime и owned layout

| Объект | Фактический путь/значение |
|---|---|
| source/Compose | `/opt/chrona-distribution/` |
| policy/runtime | `/etc/chrona-distribution/`, root0700/files0600 |
| artifact base/staging | `/srv/chrona-distribution/`, root0700; staging0700, та же FS |
| public readonly bind | `published/` root0755, public files0444 |
| immutable APK | `published/releases/Chrona-6.apk` + private-HTTP metadata sidecar `.json` |
| manifest | `published/latest.json`, schema1/relative apkPath/mandatorytrue |
| own backups/prestate | `/var/backups/chrona-distribution/`, root0700/files0600 |
| publisher image | `sha256:d5a947b173f56347600481532709ed65460e011b8a79e6b7f6d2c933df1ab24d` |
| service | `chrona-distribution/publisher`, container `chrona-distribution-publisher-1` |
| bind | host127.0.0.1:18081 → container8080 |
| user/security | 10002:10002, readonlyrootfs/rodata/capsALLdrop/no-new-privileges |

Cached official PHP base reference used without pull/packages:
`nextcloud@sha256:6df481c0861b87366086df72a63a8f041e3680c715edb12f8444902223b51c09`.
Только PHP CLI, не Nextcloud entrypoint/Apache/data mounts; inherited html VOLUME
прикрыт tmpfs, empty docroot/router always handles requests, open_basedir и
upload/URL fopen/shell disabled. Parent image крупный, это не minimal-runtime
security certification. Source logging none не сохраняет raw SAPI URI/peer logs.

### Publication / gateway

Перед публикацией fresh ownpath/port/cache/project/network guards, readonly old
component metadata/prestate и private Serve/policy/rootconsumer/state backups.
Policy pin package/public signer/minimumcode6; immutable APK exclusive/fsync,
version metadata/fsync, **latest manifest switched LAST atomically**. Public JSON
содержит только agreed public fields; нет absolute origin/hostname/credentials.
Latest.apk direct200 выбранного approved immutable файла, не redirect/temp copy.

Existing **неизменный** generic gateway `/opt/public-gateway`:
- allowlist дополнили18081, сохранив18080/прочие поля;
- root `chrona` явно делегирует только `/updates/chrona/` (остальные свойства и
  canonical client origin не менялись);
- registered `chrona-updates` prefix `/updates/chrona/` → loopback18081;
- validated/planned/applied scoped CAS; native generic CLI register/validate/
  plan/idempotent **single-child apply/status** реально выполнены, code generic
  project не редактировался. Private443/Nextcloud/прочие mappings сохранены.

В MAIN только consumer root delegation synchronized in `infra/public-consumer.json`.
Updater-specific source находится только `infra/distribution`, не generic tool.
API canonical client-origin не менялся: тот же managed HTTPS root и auth origin.
Actual hostname/download URL возвращён только parent/owner, не в source/doc/run.
Relative stable link: `/updates/chrona/latest.apk`; index `/updates/chrona/`;
manifest `/updates/chrona/latest.json`; immutable relative `releases/Chrona-6.apk`.

### Acceptance A — PASS

- Actual cached-base build, PHP `-l`, Compose config quiet, nonroot native
  publisher ready; local GETmanifest exact metadata + HEADstable/immutable size.
- Local negatives: directory/source/traversal/badcode/unknownrelease404; empty
  POSTlatest.json405 (не пользовательский event/upload).
- Private isolated store fixture **не real public store**: idempotent samebytes
  accepted; lowercode/different samecode metadata/wrong public signer/changed
  bytes rejected, latest/immutable unchanged. Только approved public APK copy,
  никакого user/session/SQL fixture и тестовых сообщений.
- External outside-tailnet SOCKS+public DoH, соединение с GLOBAL relay IP/SNI/
  trustedCA; index200, manifest exact schema+size/pkg/cert/hash/minSdk/relativepath/
  mandatorytrue; GET и HEADlatest.apk/immutable both200 **no redirect**, скачанные
  size/SHA совпали с frozen approved bytes, immutable cacheheader/no-storelatest.
- External disallowedsource/policy/staging/directory/sidecar/version paths denied,
  traversal rejected/canonicalized without following to a file; empty HTTPPOST
  publisher405. RootAPI health200/protected401 сохранились. После component B
  повторены metadata/HEAD/path/auth guards без второго полного APK download;
  прежнее full byte-hash proof переиспользовано честно.

### Runtime failures / исправления A

1. Fresh guard остановился **до server writes**: `/srv` owner — authorized sudo
   administrator, не root. Не меняли чужой owner/perms и не переносили данные.
   Единственное узкое исключение ancestor `/srv`: owner==system SUDO_UID и нет
   group/world write; все component directories/files/`/etc` по-прежнему root-owned.
2. Первый localnegative failed: unknown positive release уходил в manifest helper
   и503. Router минимально исправлен: absent immutable sidecar/file →404 до parse.
   Свои source/runtime backups сохранены, перестроен/пересоздан **только новый
   publisher до public prefix apply**. PHPsyntax и вся localnegative матрица PASS.
   Initial candidate image efcc… сохранён, current image d5a… выше. Не выдавать
   этот staging/runtime failure за failure существующего API/Nextcloud.

## B. Pending-name API-only refresh — deployed отдельно

MAIN backend `build/install/chrona-backend`, app JAR verified before/after transfer:
`c1cc832af4693932ddd5dd4b79a86f36ec78d403bf8f4134cf56629e23c04589`.
Новый **API image** `sha256:21aea9b80b2547c6c2f0d0ca41c63daef1465e03578c9df07d206a203bc40582`.
Old API80 `4d820…` retained; worker87 BOT_IMAGE
`sha256:b47432c6cfb4cf4a1959b18fa2bd97f521b54de035351bf22d525df51a02002f`
и container ID сохранены. Никакого stale87 JAR 8eb… для API.

Перед update root0700 backup runtime/source/image + consistent own DB dump;
SQL V001 checksum matches live version1, pinned existing JRE17 image cache used
--pull=false/networknone. Authmode true и actual BOT_IMAGE preserved, заменён
только BACKEND_IMAGE/API **--no-deps**. Publisher/worker/DB/proxy IDs/images и
entire Serve mapping не менялись. API native healthy, readonly/capsdrop/nonroot/
no-new-privileges/loopback18080 confirmed; root publichealth200/unauth401 PASS.
Account/grants/name values не читались, no authbegin/login/message/sync тесты.
Следовательно, binary deployed/readiness PASS, **actual friend/pending-name UI
E2E не подтверждён**; root сообщил source build installDist success без tests.

## Сохранённое / rollback

Final scoped check: API/worker/PG/publisher healthy, old nonAPI component IDs/
images unchanged, private443 mapping and **один scoped Nextcloudroot trustedTLS
status check PASS**. SSH/calendar/oldTailscale/private443 не перезапускались/
редактировались; gateway code не менялся. Whole test suites/ADB/phone/owner events
не выполнялись.

- A: generic remove **only chrona-updates**, потом stop own publisher если нужно.
  Root API consumer/другие routes/private443 сохранить. Root delegation/allowlist
  убирать только после current-vs-backup compare и отсутствия других users.
  No full Serve reset/stale wholesale restore. Backups prestate/policy/root/state
  + source/runtime до routerfix в `/var/backups/chrona-distribution` и generic
  protected snapshots в `/var/backups/public-gateway`.
- Manifest: no lower version downgrade; corruptpointer → verify SAME version;
  bad approvedrelease → highercode/samesigner. Old immutable APKs сохранить,
  retention/offhost отдельно. Уже cached mandatory UI gate нельзя считать
  откаченным лишь удалением endpoint; нужна client recovery acceptance.
- B rollback directory:
  `/var/backups/chrona/api-namefix-1792358904514357369/` (runtime/source/image+DB
  material references). Вернуть previous API image/runtime и up **API --no-deps**,
  не worker87, publisher, DB или proxy. Schema unchanged; rollback не выполнялся.

Server/source readonly APK metadata не включает runtime userdata/token; signing
private key компонентом не читался/не переносился на сервер. Реальная установка APK/mandatory gate/
unknown-sources consent/retry/minSdk behavior на phone и names UI flow остаются
owner/core/UI приёмкой. Funnel bandwidth/CPU/concurrency/DoS/offhost backup —
known production limits, не security/availability guarantees.

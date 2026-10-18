# APK distribution / mandatory updates: план до EXEC

**STATUS UPDATE 2026-10-18:** root получил явное user approval; согласованные A
publisher/prefix и B отдельный API refresh **выполнены**. Source defects исправлены
только в infra, focused syntax/integrity/runtime/external checks PASS. Ниже
сохранён первоначальный pre-EXEC план, а не актуальный запрет завершённому EXEC.
Фактические hashes/images/failures/rollback/остатки:
[apk-distribution-deployed-2026-10-18.md](apk-distribution-deployed-2026-10-18.md).

2026-10-18, ID `chrona-public-apk-plan-20261018`, chain archi → sysadmin.
**Только read-only audit и SOURCE PREPARATION. Server EXEC ещё НЕ согласован**:
ничего не создано/перестроено/запущено/опубликовано, policy/Serve/registry/secrets/
SQL/phone не менялись. Local tests/lint/compile также **не запускались** по scope.

## Проверенные metadata и выбор компонента

Один bounded SSH/sudo metadata read (secure stdin, no credentials output):
- existing generic gateway owns8443; allowed upstream ports сейчас `[18080]`;
- зарегистрирован root consumer `chrona`, prefix `/`, upstream18080, delegates[];
- TCP18081 сейчас не слушается; это не reservation, перед EXEC проверить снова;
- `/opt/chrona-distribution`, `/etc/chrona-distribution`, `/srv/chrona-distribution`
  сейчас отсутствуют: будущая установка только с absence/owner/symlink guards;
- cached official `nextcloud:33.0.9-apache` reference:
  `nextcloud@sha256:6df481c0861b87366086df72a63a8f041e3680c715edb12f8444902223b51c09`.
  Tag-only Temurin17 lookup отсутствовал; это **не** доказательство отсутствия
  ранее pinned/digest-only JRE image. Дополнительный поиск не выполнялся.

Выбран source-кандидат: **отдельный статический PHP CLI router** на уже cached
официальном image, **не Nextcloud service/entrypoint/Apache**, никаких его mounts.
Это позволяет не скачивать новый base/packages и не компилировать runtime на
слабом сервере. Минус: крупный образ с неиспользуемым parent code. Изоляция:
пустой document root, router never filesystem-fallback, open_basedir, parent html
VOLUME tmpfs, nonroot10002/capsdrop/rofs/read-only PUBLIC data only. Точный PHP
binary/SAPI, router syntax/behavior и собственный image нужно проверить **позже
по approval**; сейчас это source design, не runtime-готовность/security proof.

## A. Own APK distributor (отдельно от backend API)

| Объект | Точный предлагаемый путь / имя |
|---|---|
| Local source | `/home/niki/android-tracker/infra/distribution/` |
| Server reviewed source/Compose | `/opt/chrona-distribution/` |
| Private policy/runtime | `/etc/chrona-distribution/policy.json`, `runtime.env`, dir0700/files0600 |
| Artifact store | `/srv/chrona-distribution/` root0700 |
| Public bind directory | `/srv/chrona-distribution/published` root0755, files0444 |
| Immutable artifacts/metadata | `published/releases/Chrona-<positiveCode>.apk` и `.json` sidecar |
| Private staging | `/srv/chrona-distribution/staging` root0700, та же FS |
| Current manifest | `published/latest.json`, atomically switched LAST |
| Backups | `/var/backups/chrona-distribution` root0700/files0600 |
| Compose/service/container | `chrona-distribution` / `publisher` / `chrona-distribution-publisher-1` |
| Own network | `chrona-distribution-ingress`, loopback binding, не app/PG/proxy networks |
| Upstream | host127.0.0.1:18081 → container8080 |
| Gateway consumer | `chrona-updates`, delegated prefix `/updates/chrona/` |

Host18081 не публикуется на LAN/Internet. Generic Tailscale Funnel8443 уже
существует и **не переписывается целиком**; private443/Nextcloud остаются как есть.
PHP и manifest не знают full public hostname: HTML использует относительную
`latest.apk`, JSON — строго relative `releases/Chrona-<code>.apk`.

### Public endpoints

- `/updates/chrona/` — маленькая HTML download page (optional human entry point).
- `/updates/chrona/latest.apk` — постоянный initial link, direct200 approved file
  из одного прочитанного manifest snapshot; не временная копия/не app redirect.
- `/updates/chrona/latest.json` — agreed schema1 HTTPS manifest.
- `/updates/chrona/releases/Chrona-<code>.apk` — immutable app download, no redirect.

Только GET/HEAD, без upload/dir listing/static fallback. Manifest/latest.apk
no-store; versioned APK cache max-age31536000/immutable, MIME/nosniff/length.
Файлы sidecar/temporary/policy/SDK/source/secrets и произвольные paths не
публикуются. PHP concurrency/resource bounds и Funnel bandwidth limits — не WAF
или DDoS/SLA; два больших download могут занять workers/задержать manifest.

### Agreed manifest и цепочка доверия

Ровно: `schemaVersion:1`, `versionCode`, `versionName`,
`apkPath:"releases/Chrona-<positiveCode>.apk"`, lowercase64 `sha256`, `sizeBytes`,
`packageName`, `minSdk`, lowercase64 `signingCertificateSha256`, `mandatory:true`.
Нет absolute URI, query, userinfo, hostname, userdata/token.

Оператор должен принести **финальный approved APK** и public cert/package pin,
совпадающие с уже установленным debug-вариантом. Не читать/copy/publish keystore,
не переносить signing keys, не генерировать новый signer. Existing SDK aapt +
apksigner **verify --print-certs** проверяют frozen APK public metadata до SSH;
raw tool output не логируется. На сервере отдельная root policy повторно pin
package/cert/minimumcode/maxsize и SHA/size/framing. Это authenticated release
operator attestation на те же frozen bytes, не установка SDK на сервер.

Publication pipeline source: immutable APK snapshot verified → transfer/hash
check → exclusive release APK, fsync → immutable metadata, fsync → old-manifest
backup → **atomic latest.json replace LAST**. Нельзя downgrade current published,
reuse version с другими bytes/metadata, удалить old immutable release или менять
cert/package. Manifest policy ещё НЕ заполнена реальным pin: example placeholders
fail closed. minSdk/source compatibility обязательны до mandatory publication.

Core/UI сами отвечают за mandatory gate (только valid NEWER version), SDK/package/
cert/digest/size validation, download/installer state/consent/retry. Недоступность
канала сама не означает найденную обязательную версию; stale/malformed manifest
не должен выдавать успешное обновление. Android installer/unknown-sources consent
не обходится сервером. APK включает compiled managed endpoint, **не** app private
userdata/session/refresh/bot token; это нужно проверить в release audit позже.

### Какие конкретные server действия требуют следующего EXEC approval

1. **Backups/preflight.** Fresh18081/path/project conflict check; root-only copies
   actual Serve JSON/generic policy/root consumer/state, относящихся runtime/source,
   initial APK public cert/package/version/size checks. Никаких чужих data backups
   или остановок. Находка existing paths/unowned port — stop/review, не clobber.
2. **Создать только own distributor.** Указанные dirs/source/policy/store/backups,
   same-FS staging/publication; build own image на cached pinned PHP **без pull/
   packages**, verify syntax/default entrypoint/uid/ro mounts; publish approved APK
   manifest-last; start только new publisher loopback18081. Container health до
   первого valid manifest not-ready, healthcheck сам не рестартит unhealthy.
3. **Добавить один gateway consumer.** Backup и merge `/etc/public-gateway/policy.json`
   (добавить18081, сохранить18080/прочие allowlists). В root
   `/etc/public-gateway/consumers/chrona.json` явно добавить только
   `/updates/chrona/` в delegates, preserve root API/upstream/origin_file и existing
   delegates. Register/apply root-definition metadata, затем root0600
   `/etc/public-gateway/definitions/chrona-updates.json` → register/validate/plan/
   apply `chrona-updates` через **неизменный generic CLI/CAS**. Source/examples
   поставляются из app; generic `/home/niki/public-gateway`/`/opt/public-gateway`
   **код не меняется**, никакой логики APK в него не добавлять.
4. **Acceptance.** Local and external trusted HTTPS GET/HEAD index/latest/manifest/
   versioned APK, no redirects; совпадают code/pkg/cert/hash/length/minSdk, manifest
   только relative APK path, no directory/traversal/upload/private docs. Wrong
   method405/badpath404; unknown release404, latest snapshotconsistent.
   Root API health/protected401 и Nextcloud private443 сохраняются; на публичном
   prefix нет Nextcloud/status/DAV/secrets/SDK paths. No authbegin/login/sync/
   Telegram messages от distributor/агента. Atomic retry/idempotent same-version/
   downgrade/different-bytes rejection сначала staging, не production fixtures.

Пока НЕ выполнять даже `register`, `apply`, source upload, mkdir на сервере,
image build/pull/start, publication или phone/install test. Root сначала получает
подтверждение multi-component плана и только затем поручает narrow EXEC.

### Rollback / recovery

- До public apply: остановить только own publisher, оставить artifact store и
  backups; old API/worker/PG/proxy/Serve не трогать.
- После consumer apply: generic `remove chrona-updates` убирает только новый
  path; root API18080/прочие consumers остаются. Root delegation/allowlist убрать
  только если они добавлены этим эпизодом и никем больше не используются, после
  сравнения current vs backup. Не full Serve reset/whole stale JSON restore.
- Manifest rollback **не lower version downgrade**. При повреждении pointer
  восстановить last verified SAME version; ошибочный release исправлять higher
  versionCode с тем же signer. Удалённый/недоступный manifest не обещает разблокировать
  уже показанный mandatory gate; подготовить UI recovery flow до publication.
- Old immutable APK/sidecar сохранить для recovery/audit, retention и offhost
  backup согласовать отдельно. Никакой автоматической очистки/prune/down-v.
- Root API canonical client-origin не менять: updates prefix под тем же managed
  HTTPS root, отдельной login-origin migration здесь нет.

## B. Optional pending-name backend fix — НЕ publisher

Отдельное будущeе approval/action scope: API-only image refresh **из актуального
MAIN backend installDist**, с новым предоставленным JAR hash/source test report.
Не брать stale tmp/artifact87 лишь потому, что он последний в этом плане.
Backup API/runtime/source/image и consistent own DB; проверить migration same,
preserve actual auth mode/BOT_IMAGE worker87; build own pinned-JRE image и менять
**только BACKEND_IMAGE/API --no-deps**. Worker87/DB/proxy/gateway/publisher untouched.
Проверки API/SQL/unauth boundaries/readiness без fake login/messages. Rollback
только API на предыдущий image/config (data/migration strategy по actual release).
Эта ветка не нужна для static APK delivery и не запускается release.py. Нельзя
объявлять оба компонента deployed из-за успеха одного.

## Source и непроверенное

Подготовлены `infra/distribution/{Dockerfile,.dockerignore,.gitignore,compose.yml,
runtime.env.example,consumer.json,policy.json.example,server.php,publish_store.py,
release.py,README.md,empty-root/.keep}`. Script dryrun default, EXEC requires
explicit approved flag/confirmed SSH alias/private credential stdin; нет private
hostnames/URLs/signing keys/secrets в source. Public cached image digest — metadata,
не результат запуска publisher. **Не запускались tests/compile/build/SDK/container/
API/file publication/source upload**, source требует review/validation при следующем
согласованном этапе. Никаких Git/shared journal/portrait/агентов/model checks.

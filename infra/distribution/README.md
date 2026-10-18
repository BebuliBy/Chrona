# Chrona APK distributor — deployed APK6, 2026-10-18

User approval получен после multi-component plan. **A: distributor и public
prefix deployed**, code6 `0.4.0-update`, package/minSdk26/public signer/hash
проверены на frozen APK. **B: отдельный API-only namefix deployed**, worker87 не
пересоздавался. APK/global managed root не записаны hostname-литералами в source.
Подробный итог: `docs/apk-distribution-deployed-2026-10-18.md`; исходный план:
`docs/apk-distribution-plan-2026-10-18.md`. Весь test suite не повторяли: выполнены
только focused syntax/store/integrity/runtime/metadata/external acceptance.

Publisher image `sha256:d5a947b173f56347600481532709ed65460e011b8a79e6b7f6d2c933df1ab24d`;
API image `sha256:21aea9b80b2547c6c2f0d0ca41c63daef1465e03578c9df07d206a203bc40582`.
APK SHA256 `87d3c606c565b9e2e09db780806612d7388552a51e8227e47c8eb1709f31250c`,
12813867bytes. Existing debug public signer matched historical project APK;
keystore не читался/не переносился. Manifest mandatorytrue, но Android mandatory
UX/installer/real update и names UI E2E **не проверялись**, phone не трогали.

Ниже эксплуатационный runbook; source-only/not-tested фразы описывали плановую
стадию. PHP path/options/syntax, actual route/immutable hash теперь проверены,
не все production/DoS свойства. Bootstrap execute phases не rerun на существующем
layout без review: initial guards намеренно reject existing component/adoption.

## Отдельный consumer, не изменение generic gateway

- Предлагаемый protected prefix `/updates/chrona/`, consumer `chrona-updates`.
- Own loopback18081 → publisher8080. Порт был свободен в read-only audit, но не
  зарезервирован: перед EXEC снова проверить конфликт. Existing API18080 остаётся.
- Generic source `/home/niki/public-gateway`, server `/opt/public-gateway` не меняются.
  Только runtime policy allowlist+root-owner explicit delegation+новый consumer,
  validation/plan/CAS apply после отдельного approval. Private443/Nextcloud не трогать.
- Publisher Chrona-specific, поэтому source только в app `infra/distribution`.
  Не помещать updater/APK logic в generic gateway или API/backend SQL.

## Файлы и формат

`server.php` — GET/HEAD allowlist: `/`, `/latest.json`, `/latest.apk`,
`/releases/Chrona-<positive versionCode>.apk`; запросы с query/encoded traversal,
другие paths/methods → error, **никогда** filesystem fallback/dir listing/upload.
Версионный APK разрешён только с соответствующим проверенным metadata sidecar;
сам sidecar как отдельный HTTP resource не публикуется. Latest.apk — direct200
того immutable approved файла, который указан в прочитанном atomic manifest,
не redirect и не временная копия. App использует immutable apkPath, не latest.apk.

Manifest ровно agreed schema1: versionCode/versionName, `apkPath` relative
`releases/Chrona-<code>.apk`, lowercase SHA256, sizeBytes/packageName/minSdk/
signingCertificateSha256, mandatory=true. Никакого origin/hostname в JSON/source.

`release.py`: dryrun default. После approval оператор явно передаёт confirmed
SSH alias, SDK aapt/apksigner paths, APK, expected package/public certificate pin.
Фризит public APK в изолированном temp, читает **только public APK metadata**
через существующие SDK tools; не sign/не read/transfer debug keystore. Raw tool
output/DN не печатается. Artifact SHA связывает public metadata с переданными
байтами по authenticated SSH stdin; credential values не argv/env/files/logs.

`publish_store.py` — root-only target store. Server policy0600 независимо pin
package/cert/minimumcode/maxsize, receiver перепроверяет SHA/size/path/policy,
не доверяет незакреплённой новой identity. SSH release operator доверенный;
SDK package/cert verification выполняется на frozen APK **до передачи**, а не
посредством установки SDK на сервер. Никакого HTTP publish endpoint.

Immutable APK exclusive link после fsync, не overwrite version другими байтами;
immutable metadata+fsync; old manifest backup; **latest.json заменяется последним
атомарно**. No downgrade ниже current published; same code требует same metadata/
bytes. Staging и published должны быть на одной FS. Publisher видит только ro
published volume, не staging/policy/backups/keys. Старые releases не удаляются.

## Runtime — фактически проверен в этом EXEC

Read-only cached image metadata подтвердил existing official Nextcloud Apache
image digest в runtime example. Используется **только его PHP CLI**, не Nextcloud
entrypoint/Apache/source/volumes. Это компромисс без новых downloads/packages,
но image тяжелее специализированного PHP runtime. Own Dockerfile bypasses parent
entrypoint; -t empty-root/router, open_basedir, uploads/URL fopen/shell disabled,
nonroot10002, ro rootfs/capsdrop/no-new-privileges, bounded PHP workers/memory.
Parent `/var/www/html` VOLUME прикрыт tmpfs, чужие данные не монтируются.
Docker logging driver none не сохраняет raw PHP SAPI access URI/peer metadata;
мониторить sanitized readiness/status, не извлекать raw server logs.

PHP path/SAPI, build/cache/uid/readiness/router syntax и реальные HTTP metadata/
immutable bytes **проверены после approval**. Конкурентная нагрузка/DoS и Android
mandatory update не проверены. На этом host только ancestor `/srv` допускается
с owner==authorized SUDO_UID и без group/world write; все собственные dirs/files
и `/etc` policy по-прежнему root-owned, никаких чужих permissions не менялось.
Private TCP bind только127.0.0.1:18081; own ingress bridge нужен из-за ранее
наблюдённого отказа host publication на solely-internal bridge. Код не использует
outbound network, но bridge сам по себе не обещает egress firewall. Не менять
host firewall/default routes/VPN. Rate protection только resource/worker bounds
и Funnel service limits, не application WAF/DoS guarantee; concurrent APK
downloads могут задерживать manifest requests.

## Операторская последовательность (первый EXEC уже выполнен, не rerun bootstrap)

1. Approval, backups/conflict checks и public package/cert identity validation.
2. Own layout/source/policy/published dirs; freeze/verify/stream approved artifact,
   publish manifest last; own cached-base image build без pull/packages.
3. Start только own publisher; local readiness; root gateway delegate prefix и
   allow port18081, then register/validate/plan/apply only chrona-updates.
4. External TLS GET/HEAD/no-redirect manifest+APK metadata/digest/cert and negative
   filesystem/private443/rootAPI tests. No owner login/TG/sync tests by distributor.

До появления нового valid newer manifest приложение не должно притворяться,
что обязательное обновление успешно обнаружено/установлено. Mandatory gate,
download retry/integrity, minSdk/cert/package/version checks и Android installer
consent принадлежат core/UI; раздача manifest сама не обеспечивает mandatory UX.
APK может включать compiled managed endpoint, но не userdata/session/token.

Backend pending-name fix выполнен **отдельной approved API-only phase**:
`execute.py api`, не `release.py`. Он не часть distributor image/manifest/consumer;
worker87/BOT_IMAGE/DB/proxy/publisher/gateway/auth mode сохранены. Будущие API
версии требуют своего approved artifact hash и scoped rollout, не старый bootstrap.

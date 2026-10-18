# Chrona0.4.0-update — опубликованный release checkpoint

2026-10-18/19. Прямое поручение владельца и отдельное подтверждение показанного
multi-component плана: постоянная ссылка, mandatory in-app updates, welcome,
sync cadence и UX fixes. Это compiled/deployed checkpoint, не phone E2E.

## Приложение

- Code6/0.4.0-update, прежний `by.bebuliby.chrona.debug`/signer, minSdk26/Room3.
- OnStart проверяет public manifest. Более новая verified версия занимает весь UI,
  нет skip/dismiss/обычной навигации, Back не обходит gate. Home/выход OS разрешены.
  Container/collector/automatic sync от gate не останавливаются. Android installer
  требует обычного разрешения источника/подтверждения установки, не silent install.
- Manifest/streaming APK: managed HTTPS only/no redirect, fixed relative APK path,
  bounded size, SHA256, package/version/minSdk/same-installed-signer checks перед
  installer. Private cache FileProvider открывает только updates dir.
- Verified newer manifest cached AtomicFile удерживает gate offline. При неизвестной
  версии и ошибке сети локальная работа доступна с краткой ошибкой — отсутствующее
  знание о release не выдаётся за проверенную актуальность.
- Telegram/guest entry по разрешённому image reference: голубой hero/Canvas-часы,
  кнопка с paper-plane, guest choice. Fresh/нужный вход/явный login — одна страница;
  действующий login не сбрасывается. Guest persistent, raw/binding не очищаются;
  новые account/raw/social запросы и automatic work в guest не запускаются.
- Foreground entry немедленно будит sender, затем target5s после завершения cycle;
  update gate считается открытым UI. Background single-worker unique one-time
  chain target10min; periodic15min только recovery dispatcher. Producers не
  откладывают задачу бесконечно. Android Doze/OEM/network/force-stop могут задержать
  работу; нового FGS/notification/обхода VPN нет.
- Pending incoming/outgoing используют optional API displayName или «Пользователь»,
  не UUID. Backend включает known name/@username без выдуманного ника. Permissions/
  discoverability/ACL не расширены.
- DatePickerDialog вместо ISO typing в выборе дня/деталях/sharing, UTC date-conversion
  с прежними DayKey.zone и fromInclusive/toExclusive. Friend OpenFriend.period явно
  передаётся и сохраняется, chip/layout/font возвращаются к week/month корректно.
- Обычный notice dismiss через3s поid, не каждый recompose. Mandatory update retry
  остаётся частью gate; скрытие баннера не означает успех операции.

## Сборка и public artifact

Root: `:chrona:assembleDebug` **BUILD SUCCESSFUL55s**, backend `installDist`
**BUILD SUCCESSFUL16s**. Unit/instrumentation/lint не запускались в этом этапе.
APK size12813867, SHA256
`87d3c606c565b9e2e09db780806612d7388552a51e8227e47c8eb1709f31250c`.
Public signer SHA256
`8aba1c7b81060405fa140d4516f93628271af510fbf2e60582993f14b5061532`.
Backend JAR SHA256
`c1cc832af4693932ddd5dd4b79a86f36ec78d403bf8f4134cf56629e23c04589`.
Signing keys/userdata/session/token не переносились в release/server/Git.

## Отдельные server actions — выполнены после approval

A. `chrona-distribution/publisher`, loopback18081, nonroot10002/read-only,
image `sha256:d5a947b173f56347600481532709ed65460e011b8a79e6b7f6d2c933df1ab24d`.
Cached PHP image без pull/packages, не Nextcloud entrypoint/его mounts. Existing
gateway code неизменён: allowlist18081 + явная root delegation + consumer
`chrona-updates`, prefix `/updates/chrona/` на существующем public8443.
Public endpoints: index, latest.apk, latest.json, releases/Chrona-6.apk.
Immutable APK/exclusive/fsync и manifest atomic LAST; downgrade/version bytes
replacement запрещены. Actual download link передан владельцу, не source.

B. API-only names update, image
`sha256:21aea9b80b2547c6c2f0d0ca41c63daef1465e03578c9df07d206a203bc40582`.
SQL checksum прежний, authmode сохранён, worker87/BOT_IMAGE/DB/proxy/publisher/
Nextcloud/Serve IDs или metadata сохранены; только API --no-deps.

Focused deployment acceptance: external global relay/trustedTLS GET/HEAD/index/
manifest/stable и immutable APK digest+length/no redirects PASS; publisher rejects
POST/неразрешённые paths; rootAPI200/401/privateNextcloudTLS сохранены. Это не
phone mandatory/installer/guest/names E2E. Staging guard `/srv` и unknownrelease503
исправлены узко, documented в [deploy report](apk-distribution-deployed-2026-10-18.md).

## Доставка и дальнейшие releases

На момент записи **owner phone ещё не обновлялся**: попросили «разблокировать,
написать готов». Старым0.3.2/0.3.3 без updater нужна однократная установка по
постоянной ссылке. Дальше новая разрешённая сборка с возрастающим versionCode и
тем же signer публикуется штатным `infra/distribution/release.py`; один code
нельзя перепубликовать другими bytes. Ссылка всегда берёт текущий approved manifest.
Source commit сам по себе APK не публикует. Permissions источника/installer
подтверждает пользователь; не clear/uninstall/смена identity.

Rollback publisher — убрать только consumer/остановить own publisher, не reset
Serve; artifacts/backups сохраняются. Уже cached mandatory gate не снимается
одним удалением manifest, исправление плохого релиза — вышеcode/same signer.
API rollback materials `/var/backups/chrona/api-namefix-1792358904514357369/`.
Friend actual delivery, hard conflicts, refresh-lost-response recovery, year/
streak/goal history, release identity/offhost backup и Doze acceptance — отдельно.

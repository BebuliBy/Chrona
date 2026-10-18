# Updater / welcome / sync cadence — Android core

Scope: `chrona-updater-auth-cadence-20261018`, продолжение 2026-10-19.
Статус: **исходники внесены; сборка, тесты и runtime не выполнялись**.
Это core-handoff, не объявление опубликованного/установленного обновления.
UI-составляющая описана отдельно в [UI handoff](updater-welcome-ui-2026-10-18.md).

## Граница поручения

Переданный согласованный scope: обязательный updater при открытии приложения,
единый Telegram/guest entry, foreground target5s и немедленный цикл при входе,
background target10min без нового foreground service/notification. Tracking,
локальное сохранение и разрешённый sync не останавливаются update UI gate.
Существующие account/origin bindings, immutable requests, ACK и raw данные
сохраняются. UI/backend/infra и общие README/requirements/CHANGELOG — другие
ownership; этот отчёт не заменяет их последующее обновление интегратором.

## Внесённые исходники

- `data/update/ApkUpdater.kt`: отдельный публичный HTTPS GET, без SessionClient/
  bearer/verifier. Только управляемый сборкой нормализованный base URL; safe
  prefix сохраняется. Manifest: `<base>/updates/chrona/latest.json`; APK:
  `<base>/updates/chrona/releases/Chrona-<versionCode>.apk`.
- JSON schemaVersion1, mandatory=true, positive versionCode, bounded versionName,
  apkPath строго `releases/Chrona-<code>.apk`, lowercase SHA256/certificate64,
  sizeBytes, packageName, minSdk. Числовые поля должны быть JSON integers.
  Проверяются package/signing identity установленного приложения и SDK устройства.
  Redirects, сторонние/абсолютные APK URL, query/fragment, traversal/percent encoding
  не поддерживаются; API transport `/v1/` guard не ослаблен.
- Manifest до32KiB; APK до256MiB, streaming с точным размером/SHA256, timeouts/
  cancellation checks и общими сроками30s/10min чтения. Это защитные пределы
  реализации, не результаты измерений размера/производительности релиза.
- Загрузка: `cache/updates/download.part` → проверка → same-directory rename в
  `Chrona-<code>.apk`. Перед установщиком повторно проверяются файл, archive
  package/versionCode/versionName/minSdk и exact current signer certificate через
  PackageManager. Поддерживается текущий один signer; ротация signing key и
  multisigner-релизы не введены. Keystore/private signing files не читаются.
- Проверенный более новый manifest сохраняется AtomicFile в private app files,
  вместе с origin; не в Room. Он удерживает gate offline и не заменяется старым
  online manifest. После фактического обновления сравнение installed version
  снимает старый gate. Без проверенного newer manifest ошибка не означает
  «последняя версия»: сообщение + локальная работа.
- Manifest добавляет REQUEST_INSTALL_PACKAGES и non-exported FileProvider
  `${applicationId}.updates`, `res/xml/update_paths.xml` открывает только own
  `cache/updates/`. Host запускает Android unknown-sources settings/установщик,
  не silent install. Возврат с выданным разрешением повторно проверяет APK;
  отказ не вызывает автоматический повторный показ настроек. Отмена установщика
  не снимает обязательное обновление.
- `MainActivity.onStart/onStop` → VM/client foreground state. Каждый onStart
  запрашивает manifest независимо от Compose recomposition. Sync не зависит от
  update state. Host обрабатывает новые эффекты и ActivityResult разрешения.
- `LocalSettings.guestMode` сохраняет локальный выбор. VM показывает entry при
  первом/нужном входе, uncertain refresh или явном OpenAccountEntry, не сбрасывая
  валидный login. ContinueWithoutAccount не создаёт аккаунт, не удаляет session/
  binding/raw history; останавливает новые account/raw/social запросы и pending
  automatic work. Уже отправленный запрос нельзя отозвать задним числом.
- Успех нового обмена Telegram отмечается отдельной loginRevision: последующая
  ошибка `/me` не отменяет состоявшийся login; expired/cancelled challenge над
  старой сессией не считается новым успехом. Изменение guest choice во время
  запроса защищено generation check. Нормальная in-flight rotation не показывает
  welcome; неопределённый исход сохраняет rotating и требует повторного входа,
  без replay потенциально потраченного refresh token.
- AndroidClient foreground loop: немедленное пробуждение при entry, затем target5s
  после завершения цикла, с backoff на ошибках. Producer signals не создают
  отдельные отправители и не обходят этот интервал. В закрытом UI loop ждёт channel;
  collector signals только обеспечивают наличие delayed WorkManager work.
- `AutomaticSyncWorker/Scheduler`: network-constrained unique one-time chain с
  initialDelay10min, successor через APPEND_OR_REPLACE, а не теряющий successor
  KEEP текущей running work. Producers используют KEEP, не откладывают имеющуюся
  задачу. После bounded slice/ошибки следующий tick через10min, ACK остаётся только
  результатом настоящего протокола. Foreground tick не отправляет данные, но
  сохраняет chain, чтобы гонка onStop/completion не потеряла следующий tick.
  Login/guest/toggle запрещают цепочку. Existing periodic обновляется UPDATE в
  dispatcher-only fallback15min: он лишь восстанавливает отсутствующую цепочку,
  не является fake periodic10min и не запускает второй sync sender.
- Optional `OpenFriend.period` передаётся VM → AndroidClient, выбранный period
  сохраняется при authoritative refresh; старые callers могут не задавать его.
- Версия исходников: code6 / `0.4.0-update`; schema3/migrations1→2→3 без изменений.

## Ограничения / передача интегратору

1. **Не выполнялись** build/compile/lint/unit/instrumentation/model checks, Git/
   index, HTTP/server/deploy, ADB/установка/работа с телефоном и новые agent calls.
   Нет нового PASS; старые149unit относятся к прежнему checkpoint.
2. Root должен интегрировать sibling UI/infra, обновить согласованные требования,
   README/roadmap/CHANGELOG в своём ownership и сделать единственную разрешённую
   общую сборку. Наличие source edits не доказывает compile/security/runtime PASS.
3. Публикация latest.json/APK — отдельная работа root/sysadmin. Здесь endpoint
   не читался и не проверялся; APK не создан и не опубликован. Нужны корректные
   managed base URL, identity/signature, size/hash и растущий versionCode.
4. Android Doze/OEM/network/force-stop могут задержать/остановить background work.
   Target10min — задержка после завершения предыдущего tick, не гарантия wall-clock
   доставки; fallback после потери цепочки добавляет свою задержку. Никаких новых
   FGS/уведомлений/обхода VPN, Android protections или force-stop.
5. Неопределённый refresh recovery без повторного входа всё ещё требует отдельного
   server idempotency/recovery contract. Данные/права ради «успешного sync» не чистятся.
6. Отдельная phone/upgrade/installer/guest/Doze/ACL приёмка здесь не проводилась и
   автоматически этим handoff не разрешена.

## Final stitch: chrona-updater-final-stitch-20261018

- Единственная кодовая правка этого follow-up — `AndroidClient.refreshFriends`:
  optional `displayName` → trim/nonempty либо «Пользователь». Отсутствие/null/blank
  совместимы со старым API; accountId/requestId/relation не изменены, username
  не выводится из UUID и не изобретается. Этот parser не использует strict-key
  whitelist; добавленное поле не требует изменений WireCodec/mandatory fields.
- Focused source-сопоставление AccountEntryScreen/MandatoryUpdateScreen/
  DateSelection, gate в ChronaApp и domain/VM: имена actions/полей и callbacks
  совпадают, дополнительных core-правок для стыковки не потребовалось.
  InstallUpdate → permission-required effects/return; guest persisted, valid login
  не сбрасывается; UI gate не меняет lifecycle/data controller, targets5s/10min
  сохранены. Это source-сопоставление, **не compile/runtime PASS**.
- Build/tests/lint/phone/server/Git/agents не запускались. Sourceinfra/backend/
  UI/shared rootdocs не изменялись. Публикация ждёт разрешения. После этого
  handoff записи остановлены для root assembleDebug + backend installDist.

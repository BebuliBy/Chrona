# Chrona — интеграционный checkpoint 2026-10-18

Это отчёт о **написанном/собранном коде и реально развёрнутом server checkpoint**, не заявление о завершении всего продукта или phone/Telegram E2E.

**Последующая активация 2026-10-18:** owner выполнил hidden token install; sysadmin проверил metadata/getMe/getWebhookInfo через SOCKS/TLS и включил ровно один healthy worker, затем API auth. Real successful poll/SQL lease/offset наблюдались, прежние DB/proxy/Nextcloud/HTTPS mappings сохранены. Сообщений и login от агента не было; phone/owner auth/sync/friends E2E ещё ожидаются.

## Исполнители и разделение

- Root archi: контекст, разрешения,4 detached worktrees, передача контракта, интеграция, единственный Git-писатель/full Android build.
- Developer core/client: `ses_eb1f99fd6ffeUS3sgGjbCQ06eZ`, UI: `ses_eb1ee8d0affeYXwvzVm51jiZBE`, backend/social: `ses_eb1ee8cc6ffeYHKjZsrPs9md7M`.
- Sysadmin audit/deploy: `ses_eb1f99fc3ffeINXBoElm9p85wN`; desktop VPN-source: `ses_eb1f99fb8ffeD8h1PpJjsHhbDx`; browser dependency-docs: `ses_eb1f99fa8ffe9dutAifDrM3asf`.
- UI/core/backend/infra писали разные worktrees; общий контракт скопирован до кода. Финальный social flow передан одному integration developer в root после завершения иных writers. Дети Git/shared history/портрет не трогали.
- По прямому приказу4 existing profile variants временно изменены medium→high→medium, без новых ролей/default/permission edits. High parser/runtime проверки пользователь запретил — их не делали; изменение source не выдаётся за independently verified effort metadata.

## Приложение

`chrona` debug package `by.bebuliby.chrona.debug`, versionCode2/versionName0.3.0-local, label Chrona. Старый diagnostic `app` не менялся. Release/signing key не создавался, установленные данные не удалялись.

Реализованы UI-v2,12 палитр/3 стиля,profile/local photo/menu,7 эмоций/animations, numeric overgoal/source guard,week/month/statistics/curves; production state LOCAL без fixtures. Room schema2/DataStore/collector/checkpoint/recovery и отдельный client/session layer. INTERNET/cleartext=false, user-enabled Accessibility; нет broad storage/microphone. Фоновые/usage/coverage обещания ограничены Android и реально наблюдённым, unknown не0.

Client: explicit HTTPS origin,PKCE/private verifier,Keystore AES-GCM/AtomicFile,no backup,refresh/logout/account binding; raw outbox/frozen wire/ACK revisions/cursors/migration/restore/conflicts, no silent account merge. Login не включает upload: первый RequestSync — отдельный consent. Process-alive retry best effort не гарантирует background sender при force-stop. Случайный server Shorts UUID устойчиво отображается на fixed local UUID; manual provisioning/actual override запрещены. Ошибки/старые offline conflicts сохраняются, не success.

Social flow: default search opt-out, явный switch app и `/discover on|off` bot; stable identity Telegram ID, displayName/nick только атрибут. Search/request/accept/reject, read-only authorized snapshots и explicit-window grants. Sharing form — новый абсолютный набор, не fake snapshot текущего grant; пустой набор отзывает права. Consent сбрасывается при смене draft/account/origin/friend. Ничего не отправляется и права не расширяются автоматически.

## Фактические локальные проверки

```sh
env JAVA_HOME=/home/niki/.local/opt/android-studio/jbr ANDROID_HOME=/home/niki/Android/Sdk \
  ./gradlew --no-daemon --console=plain --max-workers=1 \
  :app:testDebugUnitTest :chrona:testDebugUnitTest \
  :chrona:assembleDebug :chrona:assembleDebugAndroidTest :chrona:lintDebug

env JAVA_HOME=/home/niki/.local/opt/android-studio/jbr \
  ./gradlew -p backend --no-daemon test installDist

python3 -B infra/scripts/validate.py
python3 -B chrona/src/test/schema_migration_test.py
```

| Проверка | Результат |
|---|---|
| Android unit | **112 PASS**:104Chrona (55core/client+49UI/preview),8diagnostic |
| Main/test APK | **BUILD SUCCESSFUL**, включая merged MainActivity/UI/client/Room |
| Android lint | **0errors/17warnings**; зафиксированные версии/compatibility/style hints, не zero-warning claim |
| Backend | **80/80 PASS**, root test reports; H2/Postgres-mode/fake Telegram tests, не live authenticated E2E |
| Infra | **33/33 PASS** после activation guards, root повторил; Python syntax/security/dry-run |
| SQLite migration/freeze/rollback | **3/3 PASS**; не Android Room instrumentation |
| Instrumentation | **13 bodies compiled**, **не запускались** |
| Physical phone / visuals / native Keystore/Room / battery | **Не выполнялись** |

Первый combined build остановлен tool timeout120s после unit/compile; повторные full commands с300s успешны. Сохраняются AGP experimental sourceSets/KSP compatibility и JBR25 compiler/SDK XML/native stripping warnings; весь toolchain не обновлялся ради их подавления. VM переведена на Application-only lifetime, не просто lint suppression.

APK: `chrona/build/outputs/apk/debug/chrona-debug.apk`; test APK рядом в `outputs/apk/androidTest/debug/`. Артефакты/Gradle caches не входят в Git. APK **ещё не установлен** и не запускался на телефоне; перед этим нужно явное состояние/подтверждение владельца. Старые limited real-device spike результаты не production collector acceptance.

## Реальный server checkpoint

Разрешение пользователя на server/sudo получено отдельно; учётные данные использовались private stdin, значения/связи credentials не сохранялись в source/docs/Git/портрет. Own paths/manifest/backups ограничены Chrona; старые данные/сервисы не перенастраивались.

- Pinned Xray26.3.27/Temurin17/Postgres17 образы verified/pulled/native-smoked на amd64 безAVX2; Xray config-test и server Compose config PASS.
- Reusable proxy: internal consumer network + egress bridge; remote DNS/TLS к настоящему Telegram PASS. Consumer direct TCP443 DENIED; stop/start только собственного proxy → fail-closed/reconnect PASS. Host10808 не опубликован, TUN/global routes/DNS/Funnel нет.
- PostgreSQL17.11 healthy, real JDBC migrations/checksum/concurrent migrate/reconnect PASS. Consistent own DB dump/validation и isolated restore PASS. Restore содержит initial state без реальной user history, не подтверждает production app-level recovery/off-host DR.
- API image checkpoint80 healthy; HTTPS `/chrona` trusted CA200; protected routes/PUT discovery401 без auth. До activation authbegin503 без создания identity. **Сейчас auth enabled:** synthetic authbegin больше не вызывается smoke helper, чтобы не создавать challenge. Worker healthy,7samples/36s успешного poll progression, SQL offset/одна lease; inbox/outbox/deliveries/identity counts не изменились. Live owner nonempty view/auth/sync не проверялись.
- Сохранены original Nextcloud container IDs/images/status/services, HTTPS root `/status.php` до/после PASS. Добавлен только own Tailscale Serve path `/chrona`; prefix rewrite реально проверен. Serve backup/root-only change metadata сохранены. Ни updates/reboot/usermod/prune/down-v/чужих остановок.
- Текущий образ/versions/pins/точные checkpoints и rollback: [server-deployment](server-deployment-2026-10-18.md), [infra runbook](../infra/README.md). Server secrets и hostname не в Git/APK.

Origin владелец показывает в своём терминале:

```sh
ssh -t server 'sudo -- cat /etc/chrona/client-origin'
```

Его полное значение с `/chrona/` вводится в settings приложения. Телефону нужен разрешённый доступ к tailnet; это **не public API** и не гарантия доступа друзей без соответствующей сети.

## Bot activation выполнена, следующий owner test

Hidden installer успешно выполнен владельцем, token в чат не поступал. Metadata0600/uid10001 проверены без вывода значения. Configure helper реально выполнил getMe/getWebhookInfo через SOCKS remoteDNS/TLS: identity verified, webhook empty/pending0. Server-only username/verification marker сохранены0600, сырых Telegram JSON/chats/tokenURL в отчётах нет.

Enable helper сохранил backup, проверил queues/lease/отсутствие worker, запустил один bot `--no-deps`, дождался реального poll/readiness, затем включил API auth `--no-deps`. DB/proxy/image80/старые службы не пересоздавались. Ошибки/429/crash/reboot в этом activation шаге не провоцировались;5с — long-poll parameter, не жёсткий end-to-end SLA. Не обещать exactly-once внешнего Telegram send.

**Не повторять token install/activation без причины.** Следом — включённый разблокированный phone, APK update, tailnet/baseURL, вход **из Chrona** по сгенерированной ссылке `/start <real requestId>`, подтверждение только владельцем и возврат. Bare `/start` в backend80 noop; UUID не придумывать. Сообщений/подтверждений вместо владельца агенты не отправляли. Нужны настоящие auth/sync/friend/revoke/recovery checks; пока E2E не passed.

## Незавершённое — не скрывать

- Физическая установка/новый collector/Room reopen/migration/native Keystore/UI/rendering/battery/boot/phone-tailnet и полный Telegram E2E.
- Year layout, recurrence/streak/effective goal history/retroactive policy, authoritative day/coverage/multi-device overlap и zero-limit emotion/general-curve coefficient. Сейчас explicit-day known manual работает; auto actual остаётся Partial/Unknown и может блокировать общий complete score/цвет. Не исключать неизвестное из denominator молча.
- Backend current-day manual-write policy может отвергать старые offline записи409; клиент сохраняет queue/error. Explicit conflict-resolution/account rebind UI пока не завершены; underlying history не удаляется.
- Invite links, полный defaults inheritance, полная pagination/recovery/retention и стабильная release identity/signing не завершены.
- Local photo picker/renderer готовы в коде; Telegram remote-image renderer fail-closed/empty allowlist по умолчанию. Реальный безопасный Telegram-avatar acquisition/endpoint и его integration не завершены.
- Off-host backup destination/retention/encryption и полноценный restore реальной user history не выбраны/не проверены. Sync не backup.

Дальнейшая работа продолжается существующими ролями; никаких новых профилей/restart/повторного High-тестирования как обязательного условия продукта.

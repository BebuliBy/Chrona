# Chrona — интеграционный checkpoint 2026-10-18

**Новое подготовленное обновление:** code5/0.3.3-sync assembleDebug PASS, ещё не
installed. Network-session preservation/default-VPN retry, loop10s/signals5s/
Shorts slices5s и refetch friend. Read-only server: OWNERaccepted101, FRIEND0/
нетинтервалов; grants в обе стороны есть на сегодня, revocation FRIEND0. Последние
request timestamps не хранятся. [Факты/ограничения/доставка](vpn-two-account-result-2026-10-18.md).

**Latest UX installed:** code4/0.3.2-ux, auto-sync/default-on, recorded Shorts
counter/emotion, yellow faces, clean UI/time/today marker/managed endpoint.
Final assembleDebug/install-r/own launch PASS, без tests/lint после последней
просьбы владельца. Прежние one-shot-only/partial-blocking разделы ниже заменены
DEC-021, исторические результаты не final-auto acceptance. [Итог](ux-installed-2026-10-18.md).

## Первая owner sync — серверный commit подтверждён

Владелец сам нажал Sync и сообщил pending20+→0. Затем sysadmin выполнил одну
REPEATABLE READ READ ONLY transaction, только counts/type/status, без значений
привычек, IDs, токенов, interval timestamps, JSON/body/history или phone доступа.

- Accounts1/entity owners1; accepted operation receipts **22**, все с revision,
  duplicate/conflict/rejected/unknown0. Сохранённых sync request receipts22,
  nested accepted results22.
- Current entities18: habit1, intervals2, segments10, gaps5; все с revision.
  Committed change_log23: habit1, intervals2, segments10, gaps10.
- Source фиксирует results/entities/changes одной транзакцией: серверное сохранение
  22 accepted операций подтверждено. Один account позволяет ограниченно связать их
  с единственным владельцем, но нет timestamp/request correlation с точным button batch.
- 18 текущих entities не обязаны равняться22 операциям: одна entity может обновляться,
  system habit provision создаёт отдельное состояние. Разница не доказывает потерю.
- Полнота всей локальной истории, правильность содержимого/детектора, restore,
  friends/revoke/ACL и complete two-way E2E ещё не проверены. Bot queues не смешивались
  с Android outbox. Агент ничего не отправлял/не менял; это не новый upload consent.

Остальные разделы ниже — предыдущие checkpoints до этой первой синхронизации.

**Последующее подтверждение владельца:** «Я уже вошёл, всё успешно». Реальный
Telegram-вход на телефоне подтверждён пользователем после worker87; прежний login
blocker закрыт. Новых SQL/ADB/vault проверок не было. Sync/friends/restore и полный
instrumentation PASS не приписываются; upload — отдельное согласие. Нижележащий
latest checkpoint сохраняет историю до этого подтверждения.

**Latest:** standalone Public Gateway (`/home/niki/public-gateway`, `/opt/public-gateway`) публикует только8443, Chrona первый root consumer, private443 сохранён. External matrix/generic14 PASS. Общий HTTPSbaseURL/one-shot consent исправлены, schema3/migration2→3;125Androidunit/SQLite5 PASS,lint0err17warn. APKcode3/0.3.1-fix installed-r/launchPASS. Native smoke **2runs/0PASS/2FAIL** на UI test assertions, оба native DNSglobal/HTTPS401; повтор подтвердил already-saved target/disabled Save/enabled Login. Last test-only substring fix compiled/not-device-rerun. Backend **87/87**, infra **37/37** PASS; **worker87 deployed/API80 unchanged**, один healthy poller36s/offset/queues сохранены. Старый owner challenge expired/unbound/sessions0, новый вход ещё не принят. [Login/rollout](bot-login-recovery-2026-10-18.md), [probe](phone-public-smoke-2026-10-18.md), [gateway](public-gateway-checkpoint-2026-10-18.md). Schema2/unit112/частный origin ниже — история исходного checkpoint.

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
| Physical phone install/cold launch | **PASS после owner confirmation**: SM-A376E/API36, install-r1→2, own resumed/focus; не visual/native/storage/E2E приёмка |

Первый combined build остановлен tool timeout120s после unit/compile; повторные full commands с300s успешны. Сохраняются AGP experimental sourceSets/KSP compatibility и JBR25 compiler/SDK XML/native stripping warnings; весь toolchain не обновлялся ради их подавления. VM переведена на Application-only lifetime, не просто lint suppression.

APK: `chrona/build/outputs/apk/debug/chrona-debug.apk`; test APK рядом в `outputs/apk/androidTest/debug/`. После явного owner «Телефон готов» **install-r Success и cold launch Statusok** на единственном SM-A376E/API36, code1→2 без uninstall/clear/-d. Ownprocess/resumed/focus подтверждены; UI screenshots/деревья/private DB/vault не читались. Это не visual/Room/Keystore/network acceptance,13instrumentation по-прежнему не запускались. Артефакты/serial не входят в Git. [Phone checkpoint](phone-check-2026-10-18.md).

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

**Теперь canonical значение — public8443 root без `/chrona/`**, tailnet на телефоне
не требуется. Прежний private `/chrona/` сохранён отдельно; не дописывать его к
новому адресу. Native phone HTTPS401 подтверждён, login ещё нет.

## Bot activation выполнена, следующий owner test

Hidden installer успешно выполнен владельцем, token в чат не поступал. Metadata0600/uid10001 проверены без вывода значения. Configure helper реально выполнил getMe/getWebhookInfo через SOCKS remoteDNS/TLS: identity verified, webhook empty/pending0. Server-only username/verification marker сохранены0600, сырых Telegram JSON/chats/tokenURL в отчётах нет.

Enable helper сохранил backup, проверил queues/lease/отсутствие worker, запустил один bot `--no-deps`, дождался реального poll/readiness, затем включил API auth `--no-deps`. DB/proxy/image80/старые службы не пересоздавались. Ошибки/429/crash/reboot в этом activation шаге не провоцировались;5с — long-poll parameter, не жёсткий end-to-end SLA. Не обещать exactly-once внешнего Telegram send.

**Не повторять token install/activation без причины.** Code3/config уже на телефоне.
После worker87 bare `/start`/`/help` отвечают инструкцией без входа; старое processed
noop не replay. Владелец проверяет expired pending и начинает **новый вход из Chrona**
по новой generated ссылке, подтверждает свой запрос и возвращается в пределах5мин.
UUID не придумывать, вместо owner не подтверждать. Пока он входит — не phone tests/
reinstall/restart/clear. Настоящие auth/sync/friend/revoke/recovery E2E ещё не passed.

## Незавершённое — не скрывать

- После install/cold launch остаются новый collector/Room reopen/migration/native Keystore/UI/rendering/battery/boot/phone-tailnet и полный Telegram E2E.
- Year layout, recurrence/streak/effective goal history/retroactive policy, authoritative day/coverage/multi-device overlap и zero-limit emotion/general-curve coefficient. Сейчас explicit-day known manual работает; auto actual остаётся Partial/Unknown и может блокировать общий complete score/цвет. Не исключать неизвестное из denominator молча.
- Backend current-day manual-write policy может отвергать старые offline записи409; клиент сохраняет queue/error. Explicit conflict-resolution/account rebind UI пока не завершены; underlying history не удаляется.
- Invite links, полный defaults inheritance, полная pagination/recovery/retention и стабильная release identity/signing не завершены.
- Local photo picker/renderer готовы в коде; Telegram remote-image renderer fail-closed/empty allowlist по умолчанию. Реальный безопасный Telegram-avatar acquisition/endpoint и его integration не завершены.
- Off-host backup destination/retention/encryption и полноценный restore реальной user history не выбраны/не проверены. Sync не backup.

Дальнейшая работа продолжается существующими ролями; никаких новых профилей/restart/повторного High-тестирования как обязательного условия продукта.

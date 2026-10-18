# Один native public-connection smoke — 2026-10-18

**Создан/скомпилирован, на устройстве этим исполнителем НЕ запускался.**

- Class: `by.bebuliby.chrona.ui.PublicConnectionSmokeTest`
- Method: `public401AndFirstNativeServerConfiguration`
- Target: установленный `by.bebuliby.chrona.debug` / MainActivity, не fixtures.
- Обязательный instrumentation argument: **`publicApiBaseUrl`**. Значение передаёт
  desktop/root только runtime, не в source/docs/logs. Поддерживается любой валидный
  shared HTTPS base URL; конкретный deployment не вшит.
- Без аргумента — generic Assume SKIP **до запуска activity rule**. Root запускает
  ровно этот class, не весь suite; serial/unlocked preflight — ответственность root.

## Что делает

1. Проверяет own debug target package, интерактивный/разблокированный экран без
   wake/unlock/изменения OS settings. `createAndroidComposeRule<MainActivity>()`
   использует реальную Activity/VM/Room/Keystore; нет setContent или demo imports.
   После этих проверок временный FLAG_KEEP_SCREEN_ON удерживает **только своё
   foreground test window**, чтобы экран не погас в процессе запроса. Прежний
   флаг восстанавливается в finally; глобальный timeout/VPN/lock не меняются,
   заблокированный или погашенный телефон тест не будит/не разблокирует.
2. Read-only DNS hostname аргумента: **все** результаты должны быть public-unicast.
   Исключены IPv4 private/link-local/CGNAT100.64/10/loopback/multicast/reserved и
   IPv6 ULA/link-local/special/documentation/6to4. Conservative rejection возвращает
   generic TAILNET_ROUTE_NOT_PUBLIC_TEST; VPN не меняется. NAT64/special ranges могут
   потребовать отдельного review, не ослабления guard.
3. Ровно один direct probe HTTP request: реальный `HttpsTransport`, **GET `/v1/me`**,
   без bearer/body/idempotency key, ожидается401. Body не читается тестом/не выводится.
   Нет POST/authbegin/login/sync/accounts/messages от теста.
4. Дожидается constant system Shorts card как UI evidence native repository ready;
   через own `profile_menu` → «Настройки» вводит аргумент в `server_base_url` и
   сохраняет обычным `ConfigureServer` action. Не читает VM/profile/DB/vault/cipher.
5. При visible logout или pending login останавливается с
   EXISTING_LOGIN_REVIEW_REQUIRED, не очищает чужую/существующую сессию. Origin-change
   confirm допускается только для unlinked/non-pending UI и действительно иного
   target. Если такой же target уже сохранён, возвращает
   CONFIG_ALREADY_SAVED_NO_NEW_WRITE_PROBE: не изображает disabled Save как новую
   Keystore-запись и не меняет URL туда-обратно ради теста.
6. Ждёт canonical field/disabled Save, константу «Сервер настроен» и enabled
   `telegram_login`. **Не нажимает login**, refresh, Sync, system dialogs/Telegram.

Изменяет только собственную public connection config при допустимом first setup;
не пишет raw history/планы/consent. Успех подтверждает обычный native configure/save
путь и anonymous TLS/routing401, **не** полный migration/cryptographic reopen/auth E2E.
Как любой запуск реальной Activity, не заменяет preflight существующего состояния
приложения; обычный lifecycle ранее настроенной app не является isolated fixture.

## Privacy/границы вывода

Только stage codes, HTTP status и boolean DNS_GLOBAL. Нет адресов, ответов, имён
аккаунта, screenshots или semantics dumps. Outer rule оборачивает setup/test/teardown
в новый `AssertionError("PUBLIC_CONNECTION_SMOKE_FAILED_STAGE_…_<simpleClass>")`
без cause/original message/suppressed tree. Аргумент отсутствует — generic Assume.

DNS-global +401 — route evidence, **не доказательство выключенности Tailscale**.
Владелец выключает его сам; test не читает VPN capabilities/чужой UI и не требует
ACCESS_NETWORK_STATE. Здесь не было DNS/HTTP/phone execution или ADB/SSH.

## Сборка

Выполнен ровно один короткий build, **BUILD SUCCESSFUL19s**:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
 ./gradlew --no-daemon --console=plain --max-workers=1 :chrona:assembleDebugAndroidTest
```

Test APK: `chrona/build/outputs/apk/androidTest/debug/chrona-debug-androidTest.apk`

Первоначальный test APK SHA256 был `82e9d3bcfd3a4a7796299937d69f087777cbc2d8fadc5ccaf09e73752ab43def`.
После root keep-screen-on/final test build43s актуальный SHA256:
`7fdb1c70d44d54a4e2a149985c318e72558ff89379f0696cbbeff32ae69ca5bd`.

Production source/Gradle/другие tests не менялись. Main APK не пересобирался:
до/после SHA256 `7c7b5f9050d2460a6036eb158e12d16f3a61e59f5cb23c82e9e0b66f588ca4dc`.
Device PASS пока **не заявлен**; следующий один native запуск — у desktop/root.

Первый desktop preflight после установки code3 не подтвердил unlocked/interactive
экран: OWNER_UNLOCK_REQUIRED. Instrumentation не запускалась, test APK не ставился,
выполнено0device tests; network/config-save PASS не приписывается. После owner unlock
запускается только этот один class с runtime URL; остальные13 UI bodies пока не ran.

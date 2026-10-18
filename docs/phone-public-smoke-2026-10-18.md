# Один native public-connection smoke — 2026-10-18

**Актуально: два native запуска, оба JUnit FAIL на UI assertions.** В обоих
DNS_GLOBAL=true и настоящий anonymous HTTPS `/v1/me`→401. Второй повтор без новой
записи подтвердил already-saved target/disabled Save/enabled Login; это не общий
PASS и не authenticated E2E. Последний test-only fix compiled, на телефоне не ran.

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
   target. Если такой же target уже сохранён, проходит read-only ветку
   CONFIG_ALREADY_SAVED → WAIT_ALREADY_SAVED_CONFIG → READONLY_CONFIG_VERIFIED:
   не изображает disabled Save как новую Keystore-запись и не меняет URL туда-обратно.
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
ACCESS_NETWORK_STATE. Фактические device результаты ниже.

## Сборка

Выполнен ровно один короткий build, **BUILD SUCCESSFUL19s**:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
 ./gradlew --no-daemon --console=plain --max-workers=1 :chrona:assembleDebugAndroidTest
```

Test APK: `chrona/build/outputs/apk/androidTest/debug/chrona-debug-androidTest.apk`

Первоначальный test APK SHA256 был `82e9d3bcfd3a4a7796299937d69f087777cbc2d8fadc5ccaf09e73752ab43def`.
После root keep-screen-on/test build43s исторический SHA256:
`7fdb1c70d44d54a4e2a149985c318e72558ff89379f0696cbbeff32ae69ca5bd`.

Production source/Gradle/другие tests не менялись. Main APK не пересобирался:
до/после SHA256 `7c7b5f9050d2460a6036eb158e12d16f3a61e59f5cb23c82e9e0b66f588ca4dc`.
Device PASS **не заявлен**. Последующие два запуска описаны ниже; прежние hashes
относятся к историческим test artifacts, не к последнему test-only rebuild.

Первый desktop preflight после установки code3 не подтвердил unlocked/interactive
экран: OWNER_UNLOCK_REQUIRED. Instrumentation не запускалась, test APK не ставился,
выполнено0device tests; network/config-save PASS не приписывается. После owner unlock
запускается только этот один class с runtime URL; остальные13 UI bodies пока не ran.

## Последующие реальные результаты — актуальнее preflight выше

После нового owner «разблокировал, Tailscale выключил» desktop подтвердил
Awake/showing=false/inputRestricted=false и установил только test APK `install -r`.
Main code3 не менялся; сеть/permissions/wake/unlock агент не менял.

1. **0PASS/1FAIL/0SKIP**, stage `WAIT_NATIVE_CONFIGURE_SAVE_AssertionError`.
   DNS_GLOBAL=true, HTTPS `/v1/me`→401, own native repository/settings ready.
   Save инициирован; совокупное условие не достигнуто. Source показал test-дефект:
   unmerged `.Disabled` отличался от merged `isEnabled()`; production save failure
   этим не доказан. Исправлен только matcher/readonly already-saved branch.
2. После нового owner readiness подтверждения **0PASS/1FAIL/0SKIP**, stage
   `ASSERT_MERGED_CONFIG_CONTROLS_AssertionError`. DNS_GLOBAL=true, HTTP401;
   `CONFIG_ALREADY_SAVED`, freshWrite=false. Safe flags: configuredStatusConstant=true,
   fieldTargetMatches=true, saveMergedEnabled=false, loginMergedEnabled=true,
   logoutExists=false, pendingExists=false. Это evidence сохранённой конфигурации
   после нового открытия, но **JUnit FAIL**, не полный cryptographic reopen PASS.

Root нашёл следующую test-only ошибку: `assertTextContains` по умолчанию exact,
а UI status продолжается « · вход ещё не выполнен». Теперь `substring = true`,
stages разделены на ASSERT_CONFIG_STATUS_TEXT/ASSERT_SAVE_DISABLED/ASSERT_LOGIN_ENABLED.
Последний `:chrona:assembleDebugAndroidTest` **BUILD SUCCESSFUL**, main SHA прежний.
Этот последний вариант на телефоне **не запускался**, чтобы не нарушить начатый
владельцем login. Ни тест не нажимал Login/Sync/Telegram, ни агенты не подтверждали
вход. Нет screenshots/semantics dumps/privateDB/vault/URL/body/serial в отчётах.
Текущий следующий шаг — [новый owner login после worker87](bot-login-recovery-2026-10-18.md),
не очередная instrumentation/reinstall во время pending request.

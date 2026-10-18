# Client fixes — 2026-10-18

**Root update:** code3/0.3.1-fix installed-r поверхcode2 безclear/uninstall, ownlaunch/resumedPASS. Native public smoke собран, но devicepreflight не подтвердил unlocked screen;0device tests, network/config/KeystorePASS не приписываются. [Общий public итог](public-gateway-checkpoint-2026-10-18.md).

ID `chrona-public-client-fix-20261018`, archi → developer. Работа **в MAIN
`/home/niki/android-tracker`**, без правок прежнего `/tmp` worktree. Основание —
явный user EXEC и два source-confirmed cases: base-path mismatch / consent-scope
mismatch. Реализация generic gateway остаётся у sysadmin и здесь не проверялась.

## 1. Один строгий HTTPS base-URL contract

- `domain/ServerBaseUrl.kt` — общий pure syntax guard для UI/core. HTTPS public DNS
  authority + optional safe prefix; default443/trailing slash нормализуются.
  Сохранены прежние UI host/bot/credential-route ограничения. Никаких URL сервера
  или credentials в production source. Root/custom HTTPS port/prefix поддержаны.
- Запрещены userinfo, query/fragment в base, IP/ambiguous hosts по прежней UI policy,
  controls/Unicode/backslash, percent encoding, `//` и dot traversal segments.
  Один trailing slash допустим, doubled trailing slash не маскируется trim.
- `ServerOrigin.parse`, UI `normalizedPublicServerBaseUrl`, vault codec и transport
  используют ту же нормализацию. Старое имя поля `origin` сохраняется для формата
  record/SQL, но identity — **весь normalized base URL, включая prefix**.
- `HttpRequest.resolvedUrl` / `ServerOrigin.requestUrl` добавляет относительный
  `/v1/...` к base ровно один раз, без URI.resolve, который потерял бы mount prefix.
  API path также проверяется на traversal/authority/fragment; percent encoding
  разрешён только в обычных query values, например opaque cursor/search.
- Смена prefix, порта или хоста сбрасывает pending/verifier/session. Эквивалентная
  нормализация host/canonical443/одного trailing slash не меняет target и не сбрасывает
  вход. UI confirmation теперь учитывает **prefix**, не только authority. Geometry
  не менялась; уточнены только эти safety-тексты/условие.
- PKCE/Telegram allowlist не ослаблены; TLS defaults/redirect prohibition сохранены.
  JSON vault round-trip проверен через тот же codec, что encrypted storage; это
  **не** физический тест AndroidKeyStore/AES provider.

## 2. Bounded one-shot consent

`RequestSync` больше не означает постоянный uploadEnabled для всей будущей очереди.

1. `syncMutex.tryLock` отклоняет одновременный повтор без нового snapshot. До HTTP
   `approveSnapshot` в Room transaction фиксирует scope UUID, normalized target,
   server account, local profile ID и **точные operation IDs** имеющейся outbox.
   Producer записывает raw+operation атомарно, поэтому это согласованный barrier.
2. Approval membership — отдельная metadata, не изменяемый фильтр всей очереди и
   не max(rowid), который мог бы включить новые IDs после повторного использования rowid.
   Новые gap/manual updates после barrier остаются unapproved.
3. Existing incomplete scope возвращается без расширения даже при повторном manual
   confirm. После pause/relogin на том же target можно явно возобновить прежний
   subset; чтобы отправить будущие записи, сначала завершить его и подтвердить новый.
4. Freeze допускает только approved IDs. На retry исходные body/key/baseRevision
   не меняются. Старый frozen request replay идёт до pull для устранения lost ACK;
   перед каждым raw send снова проверяется active scope + точная frozen row identity.
5. Dependencies вне snapshot не подмешиваются. Manual child требует подтверждённый
   server habit; согласованный ранее parent operation отправляется по очереди первым.
   Иначе CONSENT_DEPENDENCY_PENDING, без тихой догрузки будущего parent.
6. ACK атомарно сохраняет revision/applied marker, удаляет только свою outbox/wire и
   свою approval membership. Raw актуальная запись не удаляется/не заменяется старым
   отправленным значением. Gap open→ACK revision→close и frozen replay сохранены.
7. После ACK всего subset и финального pull `success(scope)` атомарно снимает
   uploadEnabled и завершает scope, даже если появился новый pending raw/social.
   Notice прямо говорит: завершён снимок, новые записи ждут следующего согласия.
   Last-success — время завершения снимка; pendingOperations не скрывается.
8. Ошибка/корутинная отмена/процессный restart не расширяет разрешение и не удаляет
   old approved pending. Process-alive loop повторяет только сохранённый subset.
   Logout/config change приостанавливает его. Нельзя отозвать уже начатый разрешённый
   HTTP request; его ACK всё равно сохраняется без повторной отправки/потери raw.
9. Login/grant/discovery не создают raw approval. Их собственные explicit frozen
   social operations могут повторяться независимо, но не включают raw outbox.

Origin/account/local-profile/scope generation mismatch fail-closed. Неизвестное не
становится0; Shorts actual остаётся collector-only. Нет новой recurrence/coverage/
autosync opt-in политики, новых UI настроек или очистки истории.

## 3. Schema3 и upgrade

- JSON schemas **1/2 сохранены**, generated3:
  `chrona/schemas/by.bebuliby.chrona.data.ChronaDatabase/3.json`.
  Identity hash **`b0ddf7f3af7ffe7167eab1205da63866`**.
- MIGRATION_2_3 создаёт только две metadata tables `upload_scopes`, `approved_uploads`
  + индекс. Старые raw tables/данные не меняет, destructive fallback отсутствует.
- Прежний schema2 unbounded flag выключается; старое разрешение **не** используется
  для фоновой отправки после upgrade. Где нет другой ошибки, виден
  CONSENT_RECONFIRM_REQUIRED. Frozen requests/IDs/bodies остаются для нового явного
  approval. Цепочка MIGRATION_1_2 + MIGRATION_2_3 зарегистрирована.
- Debug identity прежняя `by.bebuliby.chrona.debug`; **versionCode3 / 0.3.1-fix**.
  Signing/SDK/AGP/Compose/dependencies не менялись. Старое code2 на телефоне здесь
  не обновлялось. Без owner confirmation никакого ADB/install не было.

## 4. Фактические проверки

Один полный ограниченный проход (max-workers1), **BUILD SUCCESSFUL за1m49s**:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
 ./gradlew --no-daemon --console=plain --max-workers=1 \
 :app:testDebugUnitTest :chrona:testDebugUnitTest :chrona:assembleDebug \
 :chrona:assembleDebugAndroidTest :chrona:lintDebug
```

На этом проходе Chrona116 tests; затем добавлена только дополнительная регрессия
реальной отмены/повторного создания client с уже сохранённым scope. Focused repeat
`:chrona:testDebugUnitTest` **BUILD SUCCESSFUL27s**, итог **117/117 Chrona**,0failures/
0skips. Spike8 task был UP-TO-DATE (source не менялся). Итого текущие Android unit
results **125 = прежние112 +13 новых**, без удаления старых тестов.

Новые13 проверяют UI→Configure→Session→native URL builder для root/customport/prefix,
vault normalized roundtrip, prefix credential reset, unsafe inputs/routes, stale
binding, snapshot/future gap+manual, repeat/pause/frozen/restart, changed scope,
dependency outside barrier, social no raw consent, real client concurrent double tap
и cancellation→new client/process-alive retry только прежнего subset.

`python -B chrona/src/test/schema_migration_test.py`: **5/5 PASS**. SQLite сравнивает
реальные migration statements с generated schema3, сохраняет raw decimal/rows/
frozen body, снимает legacy flag без implicit scope, проверяет barrier membership/
completion и transaction rollback. Это SQL-only, не Android Room runtime test.

Lint **0 errors /17 warnings** — сохранённое количество; warnings не подавлялись.
Reports: `chrona/build/reports/tests/testDebugUnitTest/index.html`,
`chrona/build/reports/lint-results-debug.{html,txt,xml}`. Известные source-set switch,
JBR25 host-target fallback и SDK XML warning не скрывались.

APK (реально собран, не установлен):
`chrona/build/outputs/apk/debug/chrona-debug.apk`

SHA256 **`7c7b5f9050d2460a6036eb158e12d16f3a61e59f5cb23c82e9e0b66f588ca4dc`**.
Test APK также собран; instrumentation не запускались. После добавления последнего
unit test main runtime source не менялся, APK остаётся тем же.

Backend не изменялся/не тестировался этим исполнителем; предыдущие80 backend tests
не переобъявляются новым прогоном. Native TLS/Keystore/Room/phone/login/sync E2E и
gateway deployment не проверялись. Нет SSH/sudo/сетевого auth/Telegram/provider/
ADB/Git/других агентов/High/OpenCode проверок и private URL reads.

## 5. Owner-действие для старой binding

Старый local profile может быть привязан к private base URL, а новый public root
URL/порт/prefix — **другой target**, даже если за gateway тот же backend/account.
Смена адреса сбросит вход; новый exchange/me получит ACCOUNT_BINDING_CONFLICT.
Это намеренный запрет автоматического переноса, а не ошибка TLS/base-path.

Варианты владельца: вернуться к прежнему bound target **или отдельно явно согласовать
перенос существующего профиля/истории на новый target** с проверкой идентичности
аккаунта/сохранности. В этом scope transfer/rebind UI/команда не создавались.
Не предлагать в качестве автоматического «исправления» uninstall, clear-data,
destructive migration, ручное редактирование личной DB или загрузку другой учёткой.
До решения raw/outbox/frozen остаются на устройстве; никакого тихого merge/upload.

Root обновляет shared README/architecture/roadmap/CHANGELOG и закрывает source cases
после review. Этот файл и актуальные app client sections обновлены фактически.

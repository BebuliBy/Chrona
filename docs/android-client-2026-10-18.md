# Android API client — checkpoint 2026-10-18

Исходный этап `chrona-client-20261018` выполнялся в отдельном worktree. Актуальный
fix `chrona-public-client-fix-20261018` выполнен **в `/home/niki/android-tracker`**:
единый base-path contract и bounded one-shot upload consent, schema3/code3.
[Проверки и owner-действия](client-fixes-2026-10-18.md). Серверные runtime сведения
определяет root deployment report; здесь live auth/phone E2E не проверялись.

## Что реализовано

- `HttpsTransport` на HttpsURLConnection, системный TLS/hostname verification,
  explicit connect/read timeouts, bounded request/response, no cache, **no redirects**.
  Нет trust-all, вшитого IP/domain/credentials, обращения к loopback backend.
- Пользовательский HTTPS **base URL**: authority и optional safe base path,
  без userInfo/query/fragment/encoded traversal. Один валидатор ServerBaseUrl
  используется UI/core/vault/transport. Prefix добавляется к относительному `/v1/`
  один раз, redirects/TLS bypass запрещены. Empty config отключает доступ.
  Смена домена/порта/**prefix** удаляет session/pending и приостанавливает raw consent.
- PKCE S256, SecureRandom32bytes verifier, private pending record, явный bot intent
  только на проверенный `https://t.me/<bot>?start=<UUID>` без verifier/token.
  Status pending не становится login success. Confirmed → one-use exchange → `/me`;
  профиль/linked/account UI берутся из реального `/me`, не demo fixture.
- Pending login переживает process restart. Expired/cancelled/denied/consumed
  прекращают ожидание; exchange-in-flight marker не допускает повтор consume после
  потери ответа. Refresh-in-flight marker аналогично предотвращает повтор spent
  refresh token. В неопределённом результате нужен новый login, не ложный success.
- SessionClient mutex сериализует refresh/token use/config changes. На401 ровно
  один refresh/один retry исходного запроса; concurrent401 не запускают stampede.
  Rotation сохраняется до использования нового access token.
- `KeystoreSessionVault`: AndroidKeyStore AES-GCM, randomized IV, authenticated
  app-specific AAD, AtomicFile в **noBackupFilesDir**. Tokens/verifier не в
  DataStore/Room/logs/Intent. allowBackup=false сохранён. Transport/session `toString`
  redacted, UI error codes whitelist, серверные body/exception causes не логируются.
- Logout сначала устойчиво удаляет local credentials, затем best-effort cancel/
  revoke прежней session. Network failure даёт REMOTE_REVOKE_UNCONFIRMED, raw history
  не удаляется. Logout не replay-ится против новой session после повторного входа.
- Реальные actions friends/list/search/request/respond/authorized activity view/
  explicit sharing window. GET friend-requests объединяется со списком; когда backend
  не возвращает имя отправителя, UI получает его реальный account UUID как label,
  а не выдуманный Telegram profile. Search501/empty undiscoverable — честная ошибка/
  пустой результат. Defaults/grants не расширяются автоматически.
- DTO parser понимает Ready/Unavailable и Known/Partial/Unknown; не подменяет их
  нулём. Friend cells принудительно read-only, snapshot проверяет IDs/range/refs.
  Ошибка чтения убирает expanded snapshot, не показывает его как свежий доступ.

## UI integration

Только согласованные изменения `ChronaUiContract.kt`:

```kotlin
data class ConnectionUiState(val baseUrl: String = "", val loginPending: Boolean = false, val discoverable: Boolean = false)
// последнее default поле ChronaUiState:
val connection: ConnectionUiState = ConnectionUiState()
// новый ChronaAction:
data class ConfigureServer(val baseUrl: String) : ChronaAction
data class ConfigureDiscoverability(val enabled: Boolean) : ChronaAction // root-approved social follow-up
```

VM соединён с настоящим AndroidClient/AuthView/FriendsUiState и notices. Network
actions выполняются отдельно от serial local-write actor, чтобы долгий HTTP не
блокировал навигацию/числовой local input. MainActivity открывает валидированный
Telegram URL; host resume инициирует refresh pending/current login. AppLink для
этого checkpoint не требуется. Backend-confirmed `/me` переводит DataMode в CONNECTED;
до этого — LOCAL с настоящими сохранёнными данными, не fake account.

В исходном client-only проходе UI/preview **не менялись**. Последующий явно
разрешённый social integration follow-up в root изменяет их узко, см. ниже.
Shared README/roadmap/CHANGELOG остаются у root.

## Binding, consent, retry

SQL meta привязывает один local profile к паре **normalized base URL (включая prefix)
и account UUID**. Login не запускает raw upload. Первый binding возможен, но другой target/account
после binding даёт ACCOUNT_BINDING_CONFLICT без автоматического слияния/очистки.
На отвергнутый exchange делается best-effort revoke только новой неподходящей session.
Перенос origin/аккаунта требует отдельной явной recovery/migration функции, которой
здесь нет; просто URL edit не мигрирует личную историю.

**RequestSync — bounded one-shot consent**, не включение постоянного autosync.
Под mutex, до HTTP, Room transaction фиксирует точные operation IDs текущей очереди,
scope UUID, target/account/local profile. Новые записи не входят в этот набор.
App-process loop может повторить с backoff5..120s **только старый approved pending**,
включая после process restart; нет WorkManager/wake/force-stop обхода.
Повторное нажатие/возобновление незавершённого scope не расширяет membership и не
пересчитывает frozen body. После успешного ACK/pull scope consent снимается атомарно,
будущая очередь ждёт нового диалога. Logout/смена target приостанавливает разрешение,
сохраняя raw/frozen data. Никакого постоянного auto-upload opt-in здесь нет.

Отдельные social mutation уже явно заказаны своим action, поэтому их frozen request
может повториться while process alive без согласия на **raw history upload**. Это
не разрешает отправку outbox manual/collector до RequestSync.

IDLE не означает «очередь пуста»: UI получает pendingOperations. Последний success
timestamp относится к завершённому **approved snapshot**, не ко всем будущим записям.
Поздние raw/social операции не расширяют и не удерживают завершённый raw scope.
Reject/conflict остаются error, не ack/delete. Успешные уже ack-нутые операции при
частичной ошибке не дублируются и не отменяют локальную историю.

## Sync и restore

- Первый pull получает случайный server-provisioned automatic Shorts UUID и
  устойчиво сопоставляет его с `ChronaRepository.SHORTS_ID`. Старый v1 outbox
  provision удаляется **только как локальный технический provision после mapping**;
  автоматическая activity не push-ится как manual. Goal/interval/segment/gap habitId
  переводятся в server UUID. Local raw IDs/foreign keys остаются стабильными.
- Local boot-session labels переводятся в детерминированный UUID по device+boot;
  это wire encoding, не новая точность/attestation. Device UUID не подменяется:
  старый record чужого device не отправляется от имени нынешнего устройства.
- Одна операция за push. Перед **первой попыткой** в SQL сохраняются requestId,
  origin/account, method/path, exact canonical body, operationId/baseRevision.
  Retry использует тот же body/key, даже после изменения local value/refresh/restart.
  Никакого пересчёта frozen payload/revision после неизвестной доставки.
- Перед pull сначала повторяются только **approved** старые frozen raw requests: сервер мог уже commit-нуть
  запрос, но ответ потерялся. Dedup ACK обновляет revision/applied set и удаляет
  только соответствующую operation в той же transaction. Это предотвращает
  ошибочный conflict на собственном unacked echo.
- Новая unfrozen операция получает последнюю **подтверждённую** revision. ACK старой
  value не перезаписывает более свежую local value, только revision metadata.
  Коррелированные updates одной записи поэтому не получают одинаковый stale base.
- Pull owner-only; typed raw validation/source guards. Remote manual для Shorts
  запрещён. Нет enqueue импортированных записей. Страница records + revision/
  applied-set + cursor применяется одной transaction.
- Pending local collision, конфликт daily entry ID или pending same-day goal
  revision — явный PULL_LOCAL_CONFLICT, не LWW/не молчаливая замена. При ошибке вся
  страница/cursor откатываются. Resnapshot сбрасывает только cursor; records,
  local pending/frozen requests и applied history не очищаются.
- Tombstone/delete пока не поддержаны backend; неизвестный kind/schema/enum
  fail-closed. Нельзя объявлять восстановленным недоставленное/неизмеренное.
- После restore automatic records нескольких devices не суммируются наугад:
  LocalReadModels возвращает MULTI_DEVICE_UNRESOLVED, включая overlap с local open
  checkpoint. Known manual cells продолжают работать. Day/coverage политики не выбраны.

## Schema1/2/3 и migration

Сохранён schema1 JSON. Добавлен настоящий generated schema2:
`chrona/schemas/by.bebuliby.chrona.data.ChronaDatabase/2.json`, hash
`90bb968aabacde68b5bfc2d6633adbd8`.

MIGRATION_1_2 создаёт только четыре служебных таблицы и индекс:
`sync_meta`, `remote_entities`, `applied_changes`, `wire_requests`.
Старые raw tables/данные не меняются, destructive fallback отсутствует.
`wire_requests` содержит только data/social body, **не auth tokens/verifiers**.
Pending count включает raw outbox + social requests без двойного подсчёта raw wire.
Locale.ROOT используется для wire enum names.

Schema3 добавляет только `upload_scopes`/`approved_uploads` и индекс. MIGRATION_2_3
снимает прежний unbounded uploadEnabled, сохраняя raw/outbox/frozen records; старое
разрешение не превращается в произвольный новый снимок. Нужен manual reconfirm.
JSON1/2 сохранены; JSON3 реально экспортирован Room. Полный migration/test status —
в [client fixes](client-fixes-2026-10-18.md). После fix debug code3/0.3.1-fix;
applicationId/signing/SDK/Compose не менялись.

Новых production dependencies нет. Единственная новая **test-only** зависимость
`org.json:json:20240303` даёт настоящий JVM org.json вместо Android stub для fake
transport tests; в APK не входит. Старые SDK/AGP/Compose/KSP/signing/version identity
не менялись. Добавлены INTERNET и usesCleartextTraffic=false, не новые системные настройки.

## Исторические проверки client-only прохода

- KSP task и copyRoomSchemas успешно сгенерировали v2. Совмещённая короткая команда
  закончилась ошибкой **временного build/-only dependency resolver init script**,
  не Room: ошибочный task delegate `project()` исправлен на captured project.
  Повтор resolver завершился BUILD SUCCESSFUL9s. Полный Android build здесь не запускался.
- `env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" bash chrona/src/test/run-core-check.sh`:
  Kotlin2.2.10/JVM17 compile domain/data/client/tracking/presentation/generated Room
  и **51 JUnit tests PASS** (fake HTTP, memory vault/DAO; не Android Keystore).
- `python chrona/src/test/schema_migration_test.py`: **3 tests PASS**, настоящий
  in-memory SQLite SQL: реальные MIGRATION_1_2 statements соответствуют generated
  v2 columns, сохраняют v1 raw decimal/rows/FK; frozen body roundtrip; transaction
  rollback сохраняет pending operation и старый cursor.
- Covered: HTTPS origin rejection, RFC7636 vector/random verifier, no verifier in
  Telegram URL, pending restore/status/exchange/me, cancelled/interrupted one-use,
  backend Telegram503, cross-account binding, origin session clearing, concurrent401
  one refresh, frozen body/key across refresh/lost response, logout offline, Shorts
  mapping/boot UUID, source constraints, unknown import, authorized snapshot parser/
  read-only cells, multi-device fail-closed, прежние scoring/tracking tests.

### Follow-up: observation_gap open ACK → close/replay

Source-based проверка подтвердила: ACK revision сохраняется в remote_entities **для
любого entityType**, включая gap, даже если raw ObservationGap не имеет revision.
Новая unfrozen operation берёт baseRevision из этой таблицы, не из старого null
поля outbox. Поэтому закрытие после ACK открытия уже корректно в новом клиенте.

Добавлен regression `acknowledgedOpenGapRevisionIsUsedForClosureAndFrozenReplayRetainsRawGap`:
actual ClientSyncStore + fake DAO/transport, open → ACK100 → новый close operationId
с baseRevision100 → lost response → recreated store/replay того же frozen body/key
даже при более новой доступной metadata revision101 → duplicate ACK101. Валидатор
fake server проверяет backend-условия baseRevision и изменение только endUtc;
REVISION_CONFLICT не возникает, raw closed gap остаётся, queue очищается только ACK.

Для теста введён internal transaction/DAO injection seam; production constructor
по-прежнему оборачивает все операции в Room withTransaction, sync-политика/схема/UI
не изменены. Helper test compile использует friend-paths для internal seam.
Последний повтор: **51/51 JUnit +3/3 SQL PASS**, без Gradle/phone/live auth.

Commands для root после UI integration:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
  ./gradlew --no-daemon --console=plain --max-workers=1 \
  :chrona:testDebugUnitTest :chrona:assembleDebug :chrona:assembleDebugAndroidTest :chrona:lintDebug
python chrona/src/test/schema_migration_test.py
```

В client-only проходе это были следующие, ещё не выполненные checks. В актуальном
MAIN fix полный Android build/test/lint **выполнен**: [code3 report](client-fixes-2026-10-18.md).
Лёгкий compiler probe сам по себе не включал MainActivity/UI/Gradle graph; SQL tests
не являются Android Room runtime acceptance. Native Keystore/HTTPS/phone E2E здесь
по-прежнему не проверены.

## Реальные ограничения / что не называть готовым

1. Актуальную activation/public gateway конфигурацию ведут root/sysadmin в deployment
   report. Этим клиентским fix live auth/phone/server не проверялись; не переносить
   прежнее историческое «token отсутствует/auth disabled» на нынешний runtime.
   Реальный phone login/sync/friends E2E **не принят**. Loopback адреса в APK не вшиты.
2. При неизвестной доставке one-use refresh/exchange возможен необходимый повтор
   login. Нельзя обещать автоматическое восстановление session без её ответа.
3. Backend `Activities.mutate` сейчас принимает manual actual **только за текущий
   день** в заданной зоне. Накопленные offline/прошлые local entries могут получить
   POLICY_UNRESOLVED; остаются в очереди/raw, не silently skip/false success.
4. Backend transport limits (decimal512, title120, unit code/label) строже некоторых
   прежних local resource bounds. Существующие несоответствующие facts не обрезаются;
   server reject остаётся видимым. Изменять продуктовые ограничения задним числом нельзя.
5. Явные conflict-resolution/account-rebind UI/actions не согласованы. Неисправимый
   frozen rejection сохраняется и останавливает complete success; требуется отдельное
   решение, а не переписанный requestId/baseRevision или удаление данных.
6. Server source validation не является device attestation; unknown/partial/day/
   zero-curve/streak/sharing-default policies не выбраны этим клиентом.
7. Android code3 build/lint выполнен; отдельно нужны разрешённые physical tests Keystore,
   process kill/file persistence/Room upgrade, native HTTPS/offline/rotation,
   real bot confirmation, binding consent, full sync/restore/ACL revoke. Cached
   friend snapshot уже показанного не может быть гарантированно «отозван» офлайн.

Git/агенты/OpenCode/серверные изменения/SSH/sudo/OS installations/signing/shared
README/history/портрет не выполнялись. Root обновляет shared status/changelog при
интеграции; этот checkpoint не заменяет финальную продуктовую приёмку.

## Root social activation follow-up — 2026-10-18

ID `chrona-social-20261018`, актуальная рабочая копия **`/home/niki/android-tracker`**,
не прежний worktree. Узкий backend+client+UI scope явно разрешён root/user;
текущий staged index не изменялся, Git не выполнялся.

- RemoteProfile/`/me` parsing читает собственный boolean discoverable. Missing flag
  старого backend =false; string/null вместо boolean не принимаются. Это не public
  user DTO и не токен/сохранённый credential. Новое default поле ConnectionUiState
  поставляет VM из настоящего remote profile, по logout/origin change сбрасывается.
- ConfigureDiscoverability → VM → AndroidClient → existing authenticated social
  outbox → PUT `/v1/me/discoverability` с strict `{enabled:boolean}` и frozen UUID key.
  После ACK читается `/me`, в том числе после retry; никакого optimistic true по
  одному dispatch, auto-on при login, token/vault/OAuth изменений или raw upload.
- SaveSharing сначала проверяет **finite explicit TimeWindow** с обоими концами;
  Unresolved/null end не расширяются в all-time. Для local Shorts identity при
  необходимости остаётся owner pull/mapping, не raw push consent. Пустой набор
  revocation не зависит от Shorts mapping и не запускает лишний pull.
- Перед первым PUT нового absolute набора с expectedRevision=null выполняется
  **owner** GET `/v1/friends/{friendId}/sharing` для текущей revision. Только409
  POLICY_UNRESOLVED означает отсутствие grant/null revision. Прочие ошибки не
  превращаются в null. Затем body+revision+Idempotency-Key frozen обычной очередью.
  Concurrent409 остаётся conflict, не force/re-fetch-and-overwrite/retry с новым key.
  Явно переданная draft revision не заменяется. Owner чужой grant не запрашивает.
- Social mutex сериализует submit с retry/sync. Если для той же настройки/friend
  остаётся unacked/rejected frozen request, новый набор **не отправляется** и UI
  получает SOCIAL_PENDING. Это предотвращает поздний replay старого grant/opt-in
  поверх нового противоположного выбора. Conflict resolution UI не изобретён.
- Проверен существующий путь pending relations: refreshFriends объединяет accepted
  и pending incoming/outgoing, сохраняет request UUID. UI уже содержит реальные
  RequestFriend/Accept/Reject actions; после respond client обновляет список.
  При отсутствии public имени отправителя label остаётся его account UUID, не fixture.

Проверка из root: `env JAVA_HOME=/home/niki/.local/opt/android-studio/jbr
ANDROID_HOME=/home/niki/Android/Sdk bash chrona/src/test/run-core-check.sh`
→ **55/55 JUnit PASS** (прежние51 +4 parse/authorized mutation/revision/window checks),
core/data/client/VM/generated Room source скомпилированы Kotlin2.2.10/JVM17.
Это **не Android Gradle/Keystore/physical/E2E acceptance**. Новых dependencies/schema/
signing/permissions нет. Backend80 и UI49 checks — в social-activation-2026-10-18.md.
Root выполняет один final Android build+lint после этого source checkpoint.

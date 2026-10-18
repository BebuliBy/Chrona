# Chrona — data/UI/API contract v1

Дата: **2026-10-18**. Поручение `chrona-contract-20261018`, child developer → archi.
Основание: [requirements](requirements.md), DEC-010/013/015/016/018 из
[decisions](decisions.md), [architecture](architecture.md) и прямое поручение
зафиксировать общий контракт перед параллельной реализацией.

**Статус:** общий baseline, использованный реализацией UI/core/client/backend/infra.
Состояние проверки реализации — в [integration](integration-2026-10-18.md).
Принятые продуктовые правила ниже отделены от технической формы протокола и
открытых решений. Контракт не утверждает новые defaults/правила истории/серий.
К первому контрактному проходу относились только модели. В выполненном этапе подключены Room/DataStore и Kotlin/Ktor/JDBC/PostgreSQL; secret values не часть контракта.

## 1. Ownership и точные имена

| Исполнитель | Единолично владеет | Не редактирует |
|---|---|---|
| UI developer | `chrona/src/main/java/by/bebuliby/chrona/ui/**`, `preview/**`, `chrona/src/androidTest/**/ui/**`, `chrona/src/test/**/preview/**` | `domain`, `data`, `tracking`, `presentation`, MainActivity, Gradle, manifest |
| Android core developer | `domain/**`, `data/**`, `tracking/**`, **`presentation/ChronaViewModel.kt`**, `MainActivity.kt`, Android unit tests вне preview, **все Android Gradle/manifest/resource wiring** | UI/preview и UI tests |
| Backend developer | новый `backend/**`: API, SQL migrations, application use cases, bot adapter, protocol tests | Android, infra, этот общий контракт без согласования |
| Sysadmin | согласованный `infra/**`, runtime/deploy/proxy/secrets/runbooks | Android и backend business code |
| Root archi | интеграция, общие README/requirements/decisions/architecture/roadmap/CHANGELOG, единственный Git-писатель | не интегрирует несовместимые изменения контракта молча |

`domain/ChronaModels.kt` и `domain/ChronaUiContract.kt` из этого прохода — общий
baseline, который root передаёт UI/backend **до** их работы. Worktrees сами не
получают новые файлы друг друга. UI не создаёт свою копию моделей. После передачи
core сохраняет публичные имена; изменение через root и явный список потребителей.
Для новых UI drawables UI передаёт core отдельное задание/содержимое, не конкурирует
за общие Android resources. `app/**` diagnostic spike в этом разделе не меняется.

## 2. UI ↔ VM: готовая граница для независимой работы

UI предоставляет именно:

```kotlin
@Composable
fun ChronaApp(state: ChronaUiState, dispatch: (ChronaAction) -> Unit)
```

Пакет `by.bebuliby.chrona.ui`. Корневой composable не создаёт production VM,
не обращается к Room/collector/business HTTP API, не вычисляет бизнес-оценки. Узкое
исключение renderer — загрузка уже связанной Telegram-фотографии только с явно
разрешённого exact HTTPS origin через ограниченный image loader. По умолчанию
allowlist пуст, нет token-bearing bot-file URL, redirects или авто-upload. Он оборачивает
экраны центральной темой из `state.preferences`. Preview создаёт свои fixtures
и может иметь отдельный in-memory reducer/VM **только внутри preview**.

Core создаёт `by.bebuliby.chrona.presentation.ChronaViewModel : ViewModel(),
ChronaUiBridge`. Сигнатуры интерфейса уже в Kotlin:

```kotlin
val state: StateFlow<ChronaUiState>
val effects: Flow<ChronaEffect>
fun dispatch(action: ChronaAction)
```

`MainActivity` принадлежит core: создаёт VM/factory, lifecycle-aware собирает
state, передаёт `ChronaApp(state, vm::dispatch)`, собирает effects ровно одним
host-потребителем. Host запускает Android Settings/photo picker/Telegram intent;
результат picker возвращает `ProfilePhotoPicked`. После возврата из Settings/login
host обновляет наблюдение разрешений/`RefreshLoginStatus`, не включает службу сам.
Effects не сохраняются/replay как state; повторный composition не открывает intent.
VM доступен UI только через контракт, не через concrete implementation.

- Навигация `MainTab`: **TODAY, HISTORY, HABITS, FRIENDS** именно в этом порядке;
  настройки отдельно через профиль. UI-local dropdown/dialog/scroll — UI ownership.
- `todayCells` и `history.cells` — derived `DayCell`, история плюс `PeriodSummary`.
  Core поставляет q/эмоцию/кривую; UI форматирует единицы и интерполирует цвет.
- `details` содержит cell, разрешённые intervals и gaps. `canEditActual`/`canEditGoal`
  задаёт core. Даже при поддельном action write layer повторно проверяет право.
- `SaveManualValue` — абсолютное число за явный день, **не delta**, не toggle goal,
  не обрезка actual. UI разбирает ввод в BigDecimal, локализованную запятую может
  нормализовать до точки. Невалидную строку не отправляет; core валидирует повторно.
- `SaveGoal` меняет только план. `CreateManualHabit` не создаёт automatic activity.
  Изменение названия/удаление данных сверх `ArchiveHabit` пока не добавлено в API:
  архивирование не должно трактоваться как очистка истории или смена retention.
- `valueCommit.eventId` выдаётся после подтверждённой записи/нового состояния,
  не на каждый collector tick. UI играет эмоцию однократно по eventId и посылает
  `AcknowledgeValueAnimation`. Учитывает системное отключение анимаций; rebuild
  того же state не является новым событием. Remote/friend read не запускает editor.
- `DataMode.DEMO` всегда имеет видимый demo banner. `LOCAL` без Telegram не показывает
  fake success login/friend search/sync. До реализации сеть отвечает NOT_CONNECTED /
  notice NOT_IMPLEMENTED, а не случайными «настоящими» друзьями.
- `busy`, `notice` отражают подтверждение/ошибку, state не оптимистично выдаёт
  неудачную запись за сохранённую. UI не генерирует operation IDs: core создаёт
  один ID принятого command, сохраняет и повторно использует при retry.

### Выполненные client/social дополнения baseline

`ConnectionUiState(baseUrl, loginPending, discoverable)` добавлен последним default-полем UI state; ConfigureServer и ConfigureDiscoverability добавлены как явные actions. PKCE/Keystore/sync/friends реализованы в client layer, не renderer. GET `/me` включает только собственный discoverable, PUT `/me/discoverability` — authenticated/idempotent `{enabled:boolean}`. Public friend DTO этот private preference не раскрывает. Upload и SET sharing требуют отдельных явных подтверждений, automatic opt-in нет. Подробности/проверки — в client/social activation/integration docs.

## 3. Первичные данные и wire primitives

Kotlin baseline: `domain/ChronaModels.kt`; wire DTO — отдельные backend/client
transport types, **не Room entities и не Kotlin auto-serialization доменных классов**.

| Тип | Правило |
|---|---|
| ID | UUID string для habit/entry/goalRevision/interval/gap/operation/device/account. Ник Telegram не ключ. Локальный profile ID отделён от server account ID |
| Decimal | Kotlin BigDecimal; JSON **строка**, например `"12.75"`; не JSON float, exponent/NaN/Infinity/locale comma не wire-format. Raw значения неотрицательные; вычисленные deviations бывают signed. Не вводить продуктовый cap9999/target |
| Timestamp | UTC RFC3339 с `Z`, точность milliseconds; строки, не LocalTime. Timestamp precision не обещает detector accuracy |
| DayKey | `{date:"2026-10-18",zoneId:"<IANA>"}`. DayRange `{fromInclusive,toExclusive,zoneId}`. Дата без zone не достаточна; range end exclusive |
| Revision | opaque server string; `null` = new entity. Optimistic concurrency, не client clock LWW |
| enum | lower_snake_case имён Kotlin; неизвестный enum — явная unsupported-version/error, не zero/false |
| Unit | `{code,label}`. Automatic Shorts canonical unit **`duration_ms`**, raw interval duration integer milliseconds. Минуты — только presentation; не сохранять округлённое минутное значение обратно |

Raw DTO поля повторяют Kotlin, JSON имена camelCase:

- `HabitDto`: `id,title,unit,direction,collectionMode,createdAt,archivedAt,revision`.
  Direction `at_least` / `at_most`; collectionMode `manual` / `automatic_shorts`.
  Автоматический Shorts provisioned системой: `at_most`, `duration_ms`; режим и
  unit нельзя изменить, чтобы открыть manual write. Пользователь выбирает limit.
- `GoalRevisionDto`: `id,habitId,amount,requestedFrom,recordedAt,revision`.
  Immutable amount/revision. AT_LEAST требует G>0; AT_MOST допускает G=0.
  `requestedFrom` сохраняет запрос, **не выбирает сам** ретроактивную applicability.
  `DayCell.goal` — явно разрешённая revision для клетки, либо unresolved.
- `ManualEntryDto`: `id,habitId,day,value,recordedAt,revision`.
  Только manual habit, источник implicit manual, absolute day value. Отсутствие
  entry не является нулевой отметкой. Один effective daily entry, конфликты явные;
  повторная запись не суммируется со старой. Политика разрешённых прошлых правок открыта.
- `ActivityIntervalDto`: `id,habitId,deviceId,bootSessionId,startUtc,endUtc,
  startElapsedMillis,endElapsedMillis,durationMillis,collectorVersion,quality,endReason`.
  Источник implicit collector, только automatic activity. Закрытый immutable record;
  same boot, duration=endElapsed−startElapsed≥0. Wall clock не источник длительности.
  Clock jump — quality/gap, не abs(end−start)/склейка/выдуманная длительность.
- `ObservationGapDto`: `id,habitId,startUtc,endUtc,reason`; end=null означает открытый
  gap. Закрытие существующего gap — version-checked update с новым operation ID.
  Unknown, disabled, permission lost и NOT_SHORTS различны. У подтверждённого
  NOT_SHORTS может быть известная нулевая длительность наблюдаемого сегмента,
  но это не доказательство complete observation всего дня.
- `TrackerHealth` — локальное состояние устройства/разрешений и freshness,
  **не публичный friend DTO**. Coverage evidence хранится отдельно от intervals:
  интервалы Shorts сами по себе не доказывают наблюдение всех остальных часов.
- `ObservationSegmentDto`: `id,habitId,deviceId,bootSessionId,startUtc,endUtc,
  startElapsedMillis,endElapsedMillis,observation,quality,collectorVersion`.
  Immutable закрытый segment одного observed состояния: shorts/not_shorts/unknown.
  Никаких UI texts/IDs контента. Same-boot elapsed evidence не выбирает day policy;
  неизвестные/непокрытые промежутки не заполняются по последнему состоянию.
  До доказанного coverage всего scope automatic total не Known/complete, даже
  если intervals пусты. Эти raw segments sync owner-only, не device telemetry другу.

Профиль: `localProfileId` остаётся локальным, `accountId` появляется после реального
входа. Binding/восстановление нескольких локальных профилей не делать молчаливым
merge. `photo={kind,location}`: local URI после явного picker или Telegram URL после
привязки. Нет сканирования галереи/автоматического upload local photo. Local URI
не синхронизируется/не отдаётся другу. Theme preferences — `themeMode,paletteId,styleId`;
UI владеет каталогом tokens/стилей, core DataStore хранит IDs, не Compose Color.
Имена существующих draft палитр для маппинга: `ocean`, `lilac`; базовый стиль
`standard`. Новые UI варианты имеют стабильные IDs, fallback для неизвестного ID
не перезаписывает сохранённый выбор. Конкретные палитры/defaults не утверждаются
этим контрактом. Системное reduce-motion
проверяется UI; своё пользовательское правило анимаций здесь не придумывается.

## 4. Read модели, математика и защита источника

`ActualValue` имеет **Known(value,source)** / **Partial(observedValue,source)** /
**Unknown(reason)**. Partial — измеренная часть, не окончательный total. `Computed`
имеет Ready(value) или Unavailable(reason); wire: `{state:"ready",value:...}` /
`{state:"unavailable",reason:...}`. Actual wire:
`{state:"known",value:"...",source:"manual"|"collector"}` /
`{state:"partial",observedValue:"...",source:...}` /
`{state:"unknown",reason:...}`. Все decimal — string.
`PlanStatus` planned/not_planned/unresolved; unknown/unplanned не false/0.
Эмоции/score/цвет не первичные факты и не sync-write DTO.
`PeriodSummary.knownCells` считает planned cells с Known actual, не все записи;
полный score требует также resolved goal/plan/coverage. Нулевой denominator
агрегата (нет плановых клеток) даёт unavailable/not_planned, не 0% или 100%.

**Принято, реализовывать без повторного выбора:**

- Для G>0, p=100A/G: AT_LEAST эмоции по [0,25),[25,50),[50,75),[75,100),
  [100,150),[150,200),[200,+∞): tears/very_sad/sad/neutral/smile/big_smile/stars.
  AT_MOST: p=0 stars; (0,50] big_smile; (50,100] smile; (100,150] neutral;
  (150,200] sad; (200,400] very_sad; >400 tears. **Семь**, не шесть.
- AT_LEAST q=clamp(A/G,0,1); AT_MOST q=1 при A≤G, иначе clamp(2−A/G,0,1).
  При complete planned cells S=100×mean(q), равный вес каждой habit-day клетки.
  Цвет S0 бордовый/S50 жёлтый/S100 зелёный; дробность сохранять до интерполяции,
  перевыполнение не компенсирует чужой провал. Actual и эмоция не обрезаются.
- Кривая от начала выбранного range: individual cumulative A−G (AT_MOST G−A),
  unit привычки; general cumulative (A−G)/G (AT_MOST (G−A)/G), `plan_shares`.
  Никакой суммы страниц+минут/cap q на curve. +20×7 = **+140**.
- AT_MOST G=0: **known** A=0 даёт q=1/100% выполнения, A>0 — бинарное нарушение/q=0.
  Никакого A/0, usage percent unavailable. Unknown не known0. Zero-limit emotion
  и коэффициент general curve — unavailable до выбора, не самодельная шкала.

UI не рассчитывает дробные q/curve самостоятельно. Деление BigDecimal может иметь
бесконечное представление: core явно документирует вычислительный MathContext,
проверяет границы точными сравнениями/перекрёстным умножением, не округляет в целый
процент перед эмоцией/цветом. Raw факты при этом не меняются.

**Manual automatic write запрещён на всех границах:**

1. UI: Shorts canEditActual=false, нет manual number editor/override.
2. VM/use case/repository: SaveManualValue отвергается для collectionMode!=manual.
3. Collector пишет только typed interval/gap path; сумма вычисляется из наблюдений,
   не через универсальный `setActual(habitId,value,source)`.
4. HTTP/sync: manual_entry для Shorts, смена collectionMode и запись total/adjustment
   отвергаются с SOURCE_NOT_WRITABLE. `source:"collector"` в manual payload не обход.
5. Friend data read-only; даже разрешённый exact interval не даёт write scope.

Client measurements не являются hardware attestation: злонамеренный modified APK
может подделать collector. API source restrictions защищают согласованное поведение,
не заявляют криптографическое доказательство просмотра.

## 5. API v1: общие правила и login

Base `/v1`, HTTPS JSON UTF-8. Authenticated requests `Authorization: Bearer ...`;
owner/account берётся из сессии, **не доверяется ownerId в write body**. Unknown
properties/source fields на write отвергаются, не молча игнорируются. Secrets,
verifiers, token-bearing URLs, Telegram updates не логируются. Ни домен/IP, ни
bot token не вшиваются в APK. API и bot используют общие application use cases/ACL.

Ошибка: `{error:{code,message,requestId,details?}}`, details без секретов. Статусы:
400 VALIDATION; 401 UNAUTHENTICATED; 403 FORBIDDEN/SOURCE_NOT_WRITABLE;
404 NOT_FOUND (в т.ч. скрытый объект); 409 REVISION_CONFLICT/IDEMPOTENCY_CONFLICT/
POLICY_UNRESOLVED; 410 EXPIRED/CONSUMED; 429 RATE_LIMITED; 503 NOT_READY.
Неполная/невыбранная продуктовая политика не превращается в выдуманный success.

| Method/path | Request → Response / invariant |
|---|---|
| POST `/auth/telegram/challenges` | `{deviceId,deviceLabel,codeChallenge,codeChallengeMethod:"S256"}` → `{requestId,expiresAt,telegramStartUrl}` |
| POST `/auth/telegram/challenges/{requestId}/status` | `{codeVerifier}` → `{status,expiresAt}`; pending/confirmed/denied/cancelled/expired/consumed; никакого account/token по одному публичному requestId |
| POST `/auth/telegram/challenges/{requestId}/exchange` | `{codeVerifier}` → `{account,sessionId,accessToken,accessExpiresAt,refreshToken}`; атомарное однократное consume только confirmed запроса |
| POST `/auth/telegram/challenges/{requestId}/cancel` | `{codeVerifier}` → cancelled; запрос больше не даёт сессию |
| POST `/auth/refresh` | `{refreshToken}` → новая token pair, атомарная rotation/reuse protection |
| DELETE `/sessions/current` | отозвать текущую device session/refresh tokens |
| GET `/me` | `{accountId,displayName,telegramLinked,photo?}`; ни phone, ни токены/служебные настройки |

App генерирует private random verifier ≥256bits и сохраняет только в private app
storage до завершения; public challenge=base64url(SHA256(verifier)). Start payload
не verifier, не app token. Challenge краткоживущий, срок берётся из expiresAt;
backend задаёт ограниченный TTL/rate limits как явно документированные security
parameters, не бесконечный запрос. Bot показывает запрос/устройство и требует
**явное подтверждение/отказ** от Telegram sender, не вход по одному `/start`.
Telegram update provenance/dedup проверяется адаптером; username не удостоверение.
Возврат в app не содержит bearer token и сам по себе не подтверждает вход.
Status/exchange проверяют verifier, request binding, TTL, состояние, session revocation.
Один публичный payload не может забрать сессию другого инициатора.

Auth exchange не обычный retryable sync write: consumed повтор не создаёт новую
сессию; при потере ответа допускается новый login challenge, не повторный consume.
Конкретный app-link origin/return scheme и recovery/перепривязка устройств ещё
выбираются; backend не внедряет произвольный callback URL из запроса. До host
wiring intent allowlist/auth security tests реальный login не объявляется готовым.

## 6. Friends, sharing и одинаковый view

| Method/path | Shape / правило |
|---|---|
| GET `/users/search?q=...&cursor=...` | `{items:[PublicUserDto],nextCursor}`; только discoverable users, rate limit, не phone/Telegram directory |
| POST `/friend-invites` | → `{inviteId,inviteUrl,expiresAt}`; ссылка приглашения не grant на статистику |
| POST `/friend-requests` | `{targetAccountId}` **или** `{inviteCode}` → `{requestId,state}`; no self request |
| GET `/friend-requests` | incoming/outgoing requests текущего account |
| POST `/friend-requests/{id}/respond` | `{accept:boolean}` → friend/request state; принимает/отклоняет только адресат |
| GET `/friends` | `{items:[FriendDto],nextCursor}`; accepted/incoming/outgoing различны |
| DELETE `/friends/{accountId}` | удалить дружбу и прекратить grants/read access атомарно |
| GET/PUT `/friends/{accountId}/sharing` | explicit `{habitIds,historyScope,expectedRevision}` → versioned effective grant; только владелец меняет свои grants |
| GET/PUT `/sharing/defaults` | template DTO зарезервирован; при невыбранной inheritance policy PUT возвращает POLICY_UNRESOLVED, не fan-out существующим друзьям |
| GET `/owners/{accountId}/activity-view?fromInclusive=...&toExclusive=...&zoneId=...` | один ViewSnapshotDto для owner и friend, server-side ACL фильтр перед чтением/агрегацией |

`PublicUserDto={accountId,displayName,photo?}`, только shareable Telegram/approved
remote photo, **не local URI**. `FriendDto` добавляет relation/requestId; никакого
номера телефона или private Telegram binding ID. Discoverability consent/default
не выбирается здесь: пока неизвестен — не публиковать пользователя в поиске молча.

`historyScope` wire: `{kind:"unresolved"}` или
`{kind:"time_window",fromInclusive:<UTC>,toExclusive:<UTC|null>}`. Null end значит
явно открытый конец, не отсутствие ограничения вообще. Временной scope должен
быть явно разрешён; unresolved grant не даёт чтения. Этот формат **не решает**,
выбран ли scope всей истории/после дружбы. Defaults не распространяются на старых
друзей или новые habits автоматически, пока OPEN-DEFAULTS не закрыт.

`ViewSnapshotDto={owner:PublicUserDto,range:DayRange,habits:[HabitDto],cells:[DayCellDto],
summary:PeriodSummaryDto,intervals:[IntervalViewDto],gaps:[ObservationGapDto]}`.
DayCellDto соответствует Kotlin DayCell; для friend оба canEdit=false. Набор
habits/cells/intervals/gaps, summary и curves пересчитывается **после ACL и временного
ограничения**; даже forbidden названия/IDs/counts/вклад в общий score не утекут.
HabitDto в friend view не содержит device/service metadata; goal revision только
доступного habit-day, без скрытой истории revisions. IntervalView — начало/конец/
длительность/качество, без bootSession/device IDs. Локальные photo/preferences,
tracker settings/tokens не часть friend view. Пагинация не меняет итоговую область
статистики и должна явно сообщать truncation/nextCursor, не выдавать страницу за всё.

ACL проверяется на каждом API чтении **и перед каждой отправкой bot**; нужен accepted
friendship + explicit grant + valid temporal scope. Bot не читает SQL в обход этих
use cases. Отзыв прекращает последующие чтения/delivery, инвалидирует известный
friend cache; уже показанное/доставленное вернуть невозможно. Offline friend view
не считается доказательством текущего доступа. Owner view не содержит fake friends.

## 7. Sync, idempotency, конфликты и восстановление

Authenticated mutation endpoints требуют `Idempotency-Key: <UUID>`; одинаковые
account+key+canonical request повторно возвращают прежний результат, другой request
с тем же key —409. Операция/ack и её dedup запись атомарны в SQL. Для sync batch
дополнительно каждый operationId переживает retries и не меняется при новом batch.
Auth challenge/exchange имеют отдельную одноразовую семантику раздела5.

```text
POST /v1/sync/push
{deviceId, operations:[{
  operationId, entityType, entityId, kind, baseRevision, payload
}]}
→ {results:[{operationId,status,revision?,error?}]}

GET /v1/sync/pull?cursor=<opaque>&limit=<bounded>
→ {changes:[{entityType,entityId,kind,revision,payload?}],nextCursor,hasMore}
```

EntityType v1: `habit`, `goal_revision`, `manual_entry`, `activity_interval`,
`observation_gap`, `observation_segment`. Kind `upsert`/`tombstone`, но не любая комбинация разрешена:
interval/goal revision/observation segment immutable после create; habit archive — поле, не purge;
деструктивное удаление/retention пока не включать. Collector gap допускает закрытие
с revision check. Direct owner writes и sync проходят один validator; агрегат
`actual_total`, эмоция, q или manual adjustment не являются entityType.

- Server owner из session, deviceId должен быть привязан к ней. Локальный чужой
  accountId/UUID не меняет owner. Все FK проверяются в owner scope.
- Batch обрабатывается по порядку, результат per operation. Неуспешная зависимость
  не создаёт orphan. Accepted и duplicate ack; conflict/rejected не стирают локальную
  запись и не считаются успешным sync. Persist + outbox в одной local transaction.
- Одинаковый entityId/payload immutable interval — не второй интервал; отличающийся
  payload под тем же ID — conflict, не overwrite. Повтор manual set не increment.
- Optimistic baseRevision, server revision/cursor, tombstones; client timestamp не
  выбирает победителя. Разные concurrent values — явный conflict, не скрытый LWW.
- Pull — только owner records, не реплика всех данных друзей. Friend view отдельный
  ACL-checked endpoint; права нельзя обойти через sync/restore/export/bot.
- Network failure → persistent retry/backoff, lastSuccessAt только после ack/pull
  принятого набора. Перезапуск не меняет operation IDs. Cursor expires → явный
  resnapshot, не очистка недоставленных операций/БД. Multi-device dedup/overlap
  aggregation не выдумывать: сохранять device metadata, mark unresolved.
- Restore возвращает только уже синхронизированные записи; gaps/недоставленное/
  force-stop не «восстанавливаются» выдуманным просмотром. Sync не backup.

## 8. Что блокирует только конкретную функцию

| Открыто | Блокирует | Не блокирует |
|---|---|---|
| Zero-limit general curve coefficient/эмоция | нормированную zero-limit ветку, её эмоцию | бинарный q=1/0, хранение G=0, G>0 score/эмоции/curve |
| Partial coverage | единый complete score/color/непрерывную curve через неизвестное | raw intervals/gaps, Known manual values, статус partial и complete-only расчёт |
| Day timezone/clock changes/multi-device | authoritative day attribution/overlap totals | raw UTC+monotonic storage, явный DayKey, отсутствие invented totals |
| Schedule/goal revisions/retro edits/streak rules | выбор planned cells, прошлые goals, реальную серию | сохранение raw request/revisions, числовой ввод/валидацию, scoring явной готовой клетки |
| Year layout | окончательный годовой экран | week/month layouts, переключатель/year unavailable, общие statistics |
| Defaults/history/discoverability | default propagation, temporal grant/search policy | ACL engine explicit grants, request/accept, fail-closed tests |
| Login return origin/recovery, final identity/signing | production login host wiring/release/real history acceptance | контракт и security tests, local debug code без ключей |

Unavailable не вечная заглушка вместо реализации: root собирает точечные вопросы
пользователю, затем фиксирует принятые ответы; не задерживает независимые функции.

## 9. Минимальный backend — выбранная реализация

Сохранить **модульный монолит/API + SQL**, один общий application слой для HTTP и
bot adapter. В текущей реализации Kotlin/JVM + Ktor + JDBC + SQL migrations,
PostgreSQL17.11; API/bot — разные modes одного distribution/image. Один процесс может содержать API и polling
worker с разделёнными lifecycle/readiness; не нужны микросервисы/очередь брокера.
Durable bot update offset/dedup, login state, sessions, grants и operation dedup
должны быть SQL-backed, не memory fixtures. Telegram adapter использует proxy из
runtime settings и общий use case; token не в source/DTO/logs. Infra/deploy остаются
у sysadmin. Никаких установок/серверных соединений в этом проходе.

## 10. Проверки и фактический changelog этого прохода

- Прочитаны README/AGENTS/requirements/decisions/architecture/roadmap, текущие
  `chrona` model/VM/UI/theme/MainActivity/manifest/build и spike-план.
- Добавлен этот контракт и два Kotlin файла **только типов/интерфейса**, без reducers,
  calculation/storage/network/collector реализации и без новых dependencies.
- Ручная сверка интерфейсов/границ с SHORTS-005, MOOD/HISTORY/STATS и zero-limit
  правилами выполнена; Gradle/компиляция/unit/phone/server/SSH/Git не запускались.
  Это не build PASS и не приёмка runtime. Старые20unit/c7 не новая проверка.
- Следующие обязательные tests реализации: все emotion boundaries; overgoal raw;
  mean/cap без компенсации; +140; zero known0 vs unknown; division-by-zero absence;
  manual-write/source spoof rejection; interval retry dedup; ACL sums+intervals+
  hidden counts/bot+revocation; verifier/expiry/replay/session swapping; offline
  outbox/restart/conflict/restore; no fixture import; Room migration без destructive fallback.
- Из-за узкого поручения общие README/roadmap/CHANGELOG не редактировались:
  root интегратор добавляет ссылку/статус и реальное состояние проверок при принятии
  этого baseline. До такой интеграции общий documentation status ещё не завершён.

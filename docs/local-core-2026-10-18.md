# Локальное ядро Chrona — 2026-10-18

**Актуализация интегратора:** ниже исходный core checkpoint schema1. После него реализованы [client/Room schema2/migration1→2](android-client-2026-10-18.md), networking/Keystore/social, Application-only VM и собран общий APK. INTERNET теперь есть, cleartext запрещён; microphone/broad storage нет. Текущие команды/112 Android tests/остатки — в [integration](integration-2026-10-18.md). Старые утверждения «network NOT_CONNECTED / schema1 / APK не собран» относятся только к первому этапу.

Поручение `chrona-core-20261018`, archi → developer, worktree
`/tmp/opencode/chrona-core`. Основание — [data-api-contract.md](data-api-contract.md)
и прямой scope реализации ядра. **Реализован исходный код локального ядра, не
объявлен готовым полный APK или проверенный на устройстве продукт.**

## Реализовано

- Room 2.8.5, schema v1 с exportSchema=true, plugin schemaDirectory; DataStore
  preferences 1.2.1. Стабильные local profile/device UUID, тема/палитра/стиль,
  выбранный photo URI, явный tracking consent. Никаких fixtures/import демо.
- Первый запуск provisioned только системную Shorts activity с постоянным UUID,
  AT_MOST и canonical `duration_ms`. Goal пользователь ещё должен задать;
  intervals/manual history изначально пустые. Пустота не actual=0.
- Create manual habit + initial goal, immutable goal revisions, absolute BigDecimal
  за явный DayKey, archive без удаления истории. Decimal TEXT без Float/Double,
  без ограничения actual целью; отдельные resource bounds длины числа/названия.
- Повторная запись manual value заменяет effective запись того же habit/day/zone,
  не суммирует; её ID сохраняется. Goals append-only. Local row insertion order
  однозначно выбирает последнюю явную revision **этого дня**, в том числе при
  одинаковом millisecond timestamp, без случайного UUID tie-break.
- SourcePolicy вызывается в VM и ещё раз в настоящей repository transaction по
  сохранённому collectionMode. SaveManual для Shorts запрещён, scalar override/
  source selector отсутствуют. Collector имеет отдельный typed interval/segment path.
- Все raw changes и persistent future outbox пишутся одной Room transaction.
  Operation UUID/payload сохраняются; immutable interval/segment повтор с тем же
  ID и payload не дублируется, отличающийся payload — ошибка, не overwrite.
- Scoring: семь эмоций, точные границы сравнениями/умножением; q/mean cap,
  no compensation, signed individual/general cumulative curve и +140, нулевой
  лимит known0 q1 / positive q0. Unknown/Partial не Known0. Для нулевого лимита
  usage/emotion/general curve unavailable согласно открытым правилам.
- Единый `presentation.ChronaViewModel : ChronaUiBridge`: state/actions/effects,
  сериализация commands, busy/error notices, актуальные permission/freshness states,
  valueCommit только после подтверждённой raw записи. MainActivity использует
  реальный factory/VM/lifecycle collection, Android Settings и OpenDocument picker.
- Photo: только явный системный выбор image/*, takePersistableUriPermission READ,
  VM повторно проверяет persisted read grant/MIME/content scheme. URI локальный,
  не upload и не outbox. Отмена не удаляет старое фото. Нет broad storage permission.
- Network/friends/login/sync actions честно NOT_CONNECTED. Нет INTERNET permission,
  fake друзей, bearer tokens или заявления, что outbox уже отправляется.

Публичные baseline `ChronaModels.kt` / `ChronaUiContract.kt` **не менялись**.
Новые `Scoring.kt` / `CollectorRecords.kt` — implementation types ядра, UI не
должен от них зависеть. UI export остаётся `ChronaApp(state, dispatch)`.

## Явный день, а не неутверждённое расписание

`CreateManualHabit.requestedFrom` / `SaveGoal.requestedFrom` разрешают goal ровно
для переданного DayKey. Это не ежедневная recurrence и не изменение всех прошлых
дней. Known manual value + exact-day goal дают готовые score/emotion **сейчас**;
отсутствие recurrence/streak rules не скрывает эту клетку.

Сохранить raw value можно и без цели этого дня: число Known, goal/score тогда
GOAL_UNRESOLVED, пока пользователь явно не сохранит цель для выбранного дня.
Прошлые/будущие explicit-day requests не применяют молча inheritance. Окончательная
политика допустимых прошлых правок ещё не выбрана; этот слой не объявляет её
универсальным правилом автоматической истории.

`todayCells` представляет активные привычки **selectedDay** (today — текущая
дата UI отдельно). SelectDay обновляет также день открытых деталей. `habits`
содержит архивные records для подписи истории; UI может скрыть archivedAt!=null
из активного списка, но не должен терять их исторические строки.

Итог периода не считает unresolved schedule/неизвестные клетки провалом или полным
успехом: summary/непрерывная curve могут быть unavailable, при этом известные
отдельные клетки остаются рабочими. Нет fake streak. Годовая раскладка не выбиралась.
Текущая системная зона используется как **UI context** явного ввода, не как принятое
правило авторитетного автоматического переразбиения истории при travel/timezone change.

## Collector и сохранность

`tracking.ShortsAccessibilityService` — отдельная product service `chrona`, старый
`app` не менялся. Запускается/разрешается Android только после действий пользователя.
Consent DataStore по умолчанию **false**, до чтения настроек также false. При false
нет traversal YouTube, нет периодического heartbeat/checkpoint; фиксируется статус/
намеренный gap при смене состояния. Разрешение Android не равняется consent Chrona.

Минимальные positive markers: только проверенные в spike
`reel_player_page_container` / `reel_recycler`. Не читаются text,
contentDescription, event.source, названия/содержимое роликов, чаты, тексты других
приложений. У другого active root только package metadata и немедленный выход.
В Room/outbox нет UI dumps или списков content IDs.

Окна: small bounds <1/3 площади экрана исключаются; near-fullscreen проверяется
консервативной structural эвристикой (width≥85%, height≥70%). Другие большие
split/pop-up режимы UNKNOWN/UNSUPPORTED_WINDOW, **не новое продуктовое исключение**.
Обычные modal panel structural IDs или отсутствие positive player markers дают
UNKNOWN, не начисление. Это сохраняет исключение предупреждения в ранее проверенной
сцене, но **не доказывает** распознавание всех нынешних/будущих YouTube overlays.
Детектор размеров/modal guards — candidates, требуют новых phone checks.

- Event-driven throttled inspect 200ms без бесконечно откладывающего debounce;
  safety heartbeat 10s только при enabled. Room checkpoint threshold 15s, на idle
  cadence примерно20s, не 1s write loop. Doze/delays не выдаются за точный SLA.
- Frames сериализуются IO actor; reconnecting service instances защищены общим
  Mutex до flush старого actor. Storage failure прекращает сбор; freshness/notice
  показывают недоступность, нет скрытого продолжения и сырого exception logging.
- UTC timestamps + same-boot elapsed durations, BOOT_COUNT как локальная boot-session
  metadata, fallback conservative process UUID. Переход lock/Home закрывает Shorts.
  Unknown/disabled/permission loss отдельны, gaps не склеиваются в просмотр.
- Checkpoint содержит start и last confirmed timestamps, IDs открытых segment/
  interval/gap. Recovery закрывает только **до saved last confirmed**, не до now;
  downtime — PROCESS_DEATH/REBOOT gap. Stall>30s не дорисовывается просмотром.
- Wall-clock discontinuity помечается quality/gap, не abs(end−start). В raw clock
  gap timestamps могут отражать откат часов; UI read bounds не превращает их в
  измеренную длительность. Raw monotonic facts сохраняются отдельно.
- Весь automatic daily actual пока Partial/Unknown: точно измеренная часть в ms,
  а не authoritative whole-day total. Неподелённые crossing-midnight/anomalous
  intervals не распределяются пропорционально наугад; они остаются raw/details.
  Для complete attribution нужны принятые day/coverage/multi-device rules.
- Open partial amount берётся только до сохранённого confirmed checkpoint, не
  наращивается UI wall timer. Список details пока показывает закрытые intervals.
- Нет boot receiver/foreground-service обхода force-stop. Ожидается обычный Android
  rebind разрешённой accessibility service; реальное boot/reboot/OEM поведение не
  испытано и «неубиваемость» не обещана.

## Математическая точность

Raw decimals остаются неизменными. Derived division — 34 significant digits;
общие проценты/deviations используют DECIMAL128. Для **внутреннего partial q**
RoundingMode.DOWN и точные terminal comparisons сохраняют различие q<1/q>0:
микроскопический дефицит/нарушение не округляется в полную галочку. Эмоции сравнивают
исходные числа cross-multiply, без округления p перед boundary. UI получает
дробный score, не заранее целый процент. Переполнение Int/goal cap не используется.

## Схема, зависимости и совместимость

Экспортирован реальный Room artifact:
`chrona/schemas/by.bebuliby.chrona.data.ChronaDatabase/1.json`, version1,
identityHash `bd5996024b9b2a28f4e9b319c54418e0`. Девять tables: habits, goals,
manual_entries, intervals, segments, gaps, tracking_checkpoint, tracker_health, outbox.
Manual unique(habitId,day), FK к habit, immutable inserts; destructive fallback нет.
Это первая настоящая schema, старое demo не имело БД. Никаких ложных migrations из
fixtures. При следующем schema bump обязательна явная migration + upgrade test.

Room runtime/ktx/compiler/plugin2.8.5, KSP2.2.10-2.0.2, DataStore1.2.1,
coroutines1.10.2. AGP9.1.1/Wrapper9.3.1/Compose compiler2.2.10/BOM2025.08.01 не
обновлялись. Debug applicationId остаётся `by.bebuliby.chrona.debug`, versionCode2 /
versionName0.3.0-local. Signing/release key не менялся/не создавался. Manifest app
label теперь Chrona, Application/AccessibilityService зарегистрированы; INTERNET/
microphone/broad storage permissions не добавлены.

Первый KSP attempt выявил настоящий blocker AGP built-in Kotlin:
`Using kotlin.sourceSets DSL to add Kotlin sources is not allowed with built-in Kotlin`.
Добавлен предложенный самим AGP compatibility switch
`android.disallowKotlinSourceSets=false`. Повтор generation прошёл, legacy-kapt/
kotlin-android duplicate/AGP upgrade **не нужны**. Switch experimental warning
сохранён; не объявлять его zero-warning или новой постоянной архитектурой.

## Фактически выполненные проверки

1. Ровно два коротких KSP attempts (один initial failure, один после targeted fix):

   ```bash
   env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
     ./gradlew --no-daemon --console=plain --max-workers=1 :chrona:kspDebugKotlin
   ```

   Initial14s failure описан выше; repeat **BUILD SUCCESSFUL34s**, 7tasks,
   включая copyRoomSchemas. Maven project dependencies разрешены этим поручением.
   Warnings: experimental source-set switch, Kotlin compiler host target25 fallback
   JVM24, известный SDK XMLv4 warning. Android compileOptions остаются17.

2. Лёгкий compiler/JUnit probe с cached dependencies, без Gradle/APK:

   ```bash
   env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
     bash chrona/src/test/run-core-check.sh
   ```

   **PASS: domain/data/tracking/presentation + generated Room sources compiled**
   Kotlin2.2.10/JVM17; затем **29/29 JUnit tests, 0 failures**. Script использует
   cached transformed API jars и не заменяет Gradle dependency graph/final build.
   В этот probe **не входят MainActivity и UI/preview**: root интегрирует новый UI
   перед final compile. Warnings JBR25 native-access/Unsafe не подавлялись.
   Ранние проходы24/28 tests были промежуточными; последний включает29.

Покрыто: все emotion boundaries/adjacent, дробные boundaries, precision/overgoal,
q без компенсации/mean, +140/general normalized, zero-known0 vs partial/unknown,
no divide-by-zero, source/archived guards, monotonic transitions/lock/disabled,
no per-second/disabled periodic writes, process/reboot/stall/clock recovery,
floating/modal/unknown classifier, explicit-day resolution/no recurrence,
archive history/read-only, raw converter round trips.

**Не проверено:** реальное открытие/перезапуск Room/DataStore на Android, DB transaction
rollback/source-guard integration с настоящим Room, process upgrade/миграции,
MainActivity+новый UI compile, Android lint/manifest merge/full APK, picker restore,
сервис на телефоне/точность/battery/OEM/reboot. Pure guard tests проверяют общий
validator, не выдаются за runtime DB integration. Нет device/ADB/SSH/server tests.

## Root integration и следующие проверки

Root копирует только owned files, включая JSON schema и Gradle property, не build/
или .gradle outputs. UI/preview и `app` не изменены. После переноса нового UI выполнить
единственный финальный Android build/test/lint запуск, например:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" \
  ./gradlew --no-daemon --console=plain --max-workers=1 \
  :chrona:testDebugUnitTest :chrona:assembleDebug :chrona:lintDebug
```

Эта команда **здесь не запускалась**. Затем отдельно, только после user confirmation,
phone tests: persisted manual/goal/theme/photo после process restart, start consentOFF,
user enable/settings, Shorts/ordinary/warning/floating/lock/Home, process/reboot,
DB reopen/upgrade/source-guard integration и controlled battery/coverage measurements.

Outbox — durable задел, **не готовый sync**: sender/auth/API origin/acks/cursor/
rebasing revisions/retry scheduling/restore пока отсутствуют. При binding нельзя
молча отправить local profile другому account. Backend integration остаётся отдельно.

Неизбранные product details остались открыты и блокируют только свои вычисления:
zero-limit emotion/general coefficient, partial aggregate, recurrence/goal-history/
streak, authoritative day/timezone/multi-device, yearly layout, sharing defaults/
historical scope, final identity/signing. Никакие из них не выбирались молча.

Shared README/roadmap/CHANGELOG/requirements не менялись по ownership. **Root должен
обновить общий статус/ссылку/проверки при интеграции; этот отчёт не заменяет их.**

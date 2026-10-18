# Effective-from лимит Shorts — Android client

2026-10-19; ID `chrona-effective-goal-client-20261019`, chain `[archi]`.
Основание: прямое принятие пользователем постоянного лимита Shorts до следующего
изменения. Baseline по поручению: MAIN972baae, code6/0.4.0-update уже опубликован.
**Статус этого изменения: source-only, не собрано и не опубликовано.**

## Точное правило

Только для `CollectionMode.AUTOMATIC_SHORTS` среди GoalRevision текущих локальных
facts выбираются совпадающие habitId и **точный** zoneId явного DayKey, с
`requestedFrom.date <= requestedDay.date`. Побеждает максимальная effective date.
На одной effective date побеждает последняя запись в существующем локальном
insertion order (`ChronaDao.goals`: `ORDER BY rowid`, repository сохраняет порядок).
UUID, recordedAt/текущее время и lexical server revision для tie-break не используются.
Другие profiles/facts не загружаются и не объединяются; текущая граница локального
профиля/привязки не менялась. Между разными habitId/zoneId перенос запрещён.

Примеры/границы (описание правила, **не выполненные тесты**):
- Вчера30мин → сегодня30, пока нет новой применимой записи.
- Сегодня20мин → сегодня/дальше20; вчера остаются прежний лимит и расчёт.
- Уже есть будущая explicit revision25мин → с её даты25, даже если более поздним
  сохранением добавили запись20 с более ранней effective date.
- Несколько сохранений на одной effective date → последняя локально вставленная;
  сохранённые immutable entities/их даты не переписываются.
- До первой applicable revision лимит остаётся unresolved; другая зона/привычка
  не даёт fallback. Нулевой лимит и actual используют прежние scoring rules.
- Архив/исторические entities не меняются; правило чтения не восстанавливает и
  не копирует rows. Существующие вчерашние exact rows начинают наследоваться
  при чтении, без migration/backfill/daily copy/reset в полночь.
- `MANUAL` остаётся строго exact-day; recurrence/streak/прочие OPEN не решались.

## Точные файлы

- `chrona/src/main/java/by/bebuliby/chrona/presentation/LocalReadModels.kt`:
  выбор применимого лимита; общий cell используется деталями/today/history,
  week/month/year не получают отдельной бизнес-семантики.
- `chrona/src/main/java/by/bebuliby/chrona/domain/ChronaModels.kt`: комментарий
  семантики requestedFrom, **DTO shape не менялся**.
- `chrona/src/main/java/by/bebuliby/chrona/ui/HabitComponents.kt`: одна целевая
  строка заголовка Shorts editor «Лимит с <дата>», manual caption прежний.
- `chrona/build.gradle.kts`: code7 / `0.4.1-goals`.

Repository append-only insertGoal/queue, DAO/order, Room schema/migrations, raw
actual/readonly/scoring/7эмоций/ranges не изменены. Preview fixtures не переделывались:
это demo reducer, не production lookup. Серверный effective-from/friend-view —
отдельный backend sibling, здесь не изменялся.

## Проверки / handoff

Только focused source reads и source edits; **никаких tests/lint/Gradle/build/
phone/USB/server/Git/agents/model checks**. Нет нового compile/runtime/PASS.
Shared README/requirements/DEC/CHANGELOG, publisher/formatting не изменялись.
Root после backend готовности выполняет одну сборку и normal publish;
доставка пользователю — через встроенный updater, **не USB install**.
После READY исполнитель прекращает запись, чтобы не пересечься с root build.

## Wire stitch — chrona-effective-goal-wire-stitch-20261019

Только `data/client/WireCodec.kt`, проверка goal applicability в snapshot:
AUTOMATIC_SHORTS допускает requestedFrom раньше cell.day (в том числе до начала
видимого периода), но только в той же точной зоне и при той же habitId.
Manual остаётся exact-day; проверки references/UUID/уникальности cells/range и
остальные guards сохранены. Даты/DTO/revision ordering не менялись и не backdate.
Same-date server revision order и локальный insertion order остаются прежними.
Только source edit, без tests/build/lint/Git/phone/server/других файлов.
**STOP writes: root далее build.**

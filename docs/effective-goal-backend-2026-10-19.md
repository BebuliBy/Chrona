# Effective Shorts goal — backend source, 2026-10-19

ID `chrona-effective-goal-backend-20261019`, chain `[archi] → developer`, MAIN
`/home/niki/android-tracker`. Новое правило **прямо принято пользователем**:
Shorts limit действует ежедневно с requestedFrom до следующего effective изменения;
новый сегодняшний limit не меняет вчерашний. Manual recurrence остаётся OPEN/exact-day.

## Source diff

- `backend/src/main/kotlin/by/bebuliby/chrona/backend/ActivityViews.kt`:
  общий `applicableGoal` выбирает для automatic_shorts тот же habit/zone и
  requestedFrom.date≤cell.date, max effective date, затем прежний numeric server
  append revision order внутри одной effective date. Ни client recordedAt/clock,
  ни random UUID tie-break. Manual — только exact date/zone. Partial authorized day
  не получает унаследованную конфигурацию. Owner/friend/bot используют общий projection.
- `backend/src/main/kotlin/by/bebuliby/chrona/backend/Social.kt`:
  после owner/viewer/friendship/habit/window ACL разрешены configuration candidates
  **только authorized habit+same zone**. Для разрешённых full-day cells выбираются
  необходимые revisions, включая Shorts configuration до начала visible period.
  В facts остаются только действительно применённые revisions, не superseded/
  future/unrelated history. Grant window, даты cells, manual entries и права не
  расширяются. В output нет отдельного raw goal history; только selected cell.goal.

Пример:30 с D1,20 с D3 → D1/D2 имеют30, D3 и далее20. Новый revision для D3 выбирается
прежним server append order; не перезаписываются old facts/configuration rows.
Другой zone не переносится. До первого применимого goal — unresolved, не default.
DTO сохраняет **настоящий** requestedFrom исходного selected configuration, не
подменяет его датой клетки. Новых полей/schema/migrations/materialized daily copies нет.

## Обязательный client compatibility handoff root → core

В прочитанном MAIN `WireCodec.snapshot` ещё проверяет `goal.requestedFrom == cell.day`
для всех habits. После effective Shorts это отвергнет корректный server snapshot.
Core sibling должен сохранить habit ownership/reference/zone checks, но для
AUTOMATIC_SHORTS допустить `goal.requestedFrom.date <= cell.day.date` в той же zone;
для manual оставить exact day. Старую effective date не переписывать ради parser.
Core/UI файлы этим backend child не менялись.

## Не затронуто / проверка

**Source-only, без tests/lint/build/Git/server/SQL/live calls/агентов.** Новый
installDist не собирался. Существующий runtime artifact ещё без этого read fix;
root выполняет один build и передаёт новый **API-only** artifact sysadmin по своему
разрешённому scope. Child закончил запись source и не делает deploy/перезапусков.

Activities.write/source/idempotency/immutable revision guards и существующие
POLICY_UNRESOLVED ветки не менялись; никакого специального retroactive write bypass.
Важно: текущая goal_revision write ветка уже сохраняет raw requestedFrom без
отдельного past-date guard (в отличие от manual actual). Этот read-only по смыслу
fix не вводит новую политику разрешённости backdated submissions; core не должен
переносить сегодняшнее изменение на вчера. Отдельный запрет новых backdated
config requests, если нужен сверх текущего write scope, требует решения root.

Backend actual Partial/Unknown/scoring/coverage/multi-device/day-boundary поведение
не исправлялось попутно. Новый applicable goal не обещает Ready score при старой
partial actual policy. Live user rows/history/credentials не читались, SQL не менялся.

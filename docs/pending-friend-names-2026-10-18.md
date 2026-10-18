# Pending friend names — source-only fix, 2026-10-18

ID `chrona-pending-friend-names-20261018`, chain `[archi] → developer`.
MAIN `/home/niki/android-tracker`; scope только backend + этот doc/own run.
**Source исправлен, tests/lint/build/deploy не выполнялись.** Старые PASS/artifact
не проверка и не сборка этого изменения. Core/UI sibling files не редактировались.

## Причина

- `Social.friends()` выдавал accepted public summaries с displayName, search также
  использует Identity.publicUser. `Social.requests()` возвращал только структурные
  requestId/senderAccountId/targetAccountId/state, без имени другого участника.
- Текущий `AndroidClient.refreshFriends()` отдельно читает pending requests и явно
  создаёт `FriendSummary(other, other, ...)`, то есть UUID используется как имя.
  **Backend-only rollout сам по себе не исправит этот literal fallback в уже
  установленном клиенте.** Root передаёт ниже точечный consumer fix core sibling.

## Минимальное backend изменение

1. `backend/src/main/kotlin/by/bebuliby/chrona/backend/Social.kt`:
   requests по-прежнему выбираются только для sender/target текущего owner. Для
   incoming label берётся public summary отправителя, для outgoing — адресата.
   В item добавлен **displayName**. Старые четыре структурных поля сохранены;
   requestId остаётся ключом accept/reject, не текстом label. Accepted-only friends
   endpoint не смешивается с pending — старые клиенты не примут pending за accepted.
2. `backend/src/main/kotlin/by/bebuliby/chrona/backend/Identity.kt`:
   единый public summary нормализует blank/legacy `Chrona user`/UUID-only label в
   **«Пользователь»**, не fallback account/request ID. Known name сохраняется;
   confirmedName добавляет valid available @username только если его ещё нет
   (case-insensitive token match), не дублирует уже содержащий nick displayName.

Schema уже хранит combined публичный display_name. Confirmed login ранее сохранял
известное имя + `(@username)`; отдельных first_name/username columns нет. Новых
mandatory username fields/колонок/migrations нет. Если nickname не был сохранён
при login или Telegram username отсутствует, возвращается известное имя без
выдуманного @. Старые неизвестные данные не восстанавливаются SQL/backfill/Telegram
lookup, никакой forced login или чтение реальных profiles не выполнялись.

## Совместимость и точный handoff core sibling

GET `/v1/friend-requests` item теперь:

```text
{requestId, senderAccountId, targetAccountId, state, displayName}
```

displayName — label **другого** участника для текущего viewer, как accepted/search;
например known name with optional `(@username)`, либо «Пользователь».
Нет отдельного обязательного username, private Telegram ID, discoverability flag,
phone, history, interval или grant в response. Current WireCodec.user shape accepted/
search не меняется. В прочитанном pending parser текущего клиента unknown-fields
guard отсутствует: additive displayName не ломает старую разборку, но игнорируется.

**Root → core:** в `AndroidClient.refreshFriends()` оставить вычисление incoming/
other/requestId/relation прежним, заменить `FriendSummary(other, other, ...)` на
label из optional `r.displayName`, fallback **«Пользователь»**, никогда other/requestId.
Строгий consumer allowlist, если он вводится sibling-ом, должен разрешать displayName;
отдельное обязательное username поле не требуется. UI сохраняет relation/actions,
показывает готовый displayName без повторного дописывания @ и без UUID fallback.

## Границы приватности и приёмка

- Только участник получает summary другого участника **своей** заявки. Читаются
  account id/display_name, не private profile/Telegram binding/secrets.
- Pending не становится friendship и не получает статистику: accepted+explicit
  grant+temporal ACL activity-view не изменялись. Discoverability default/rate limit
  и фильтр search не расширены. Stable identity остаётся Telegram ID, username — attribute.
- Ничего не запускалось: ни tests/lint/build/Git/SQL/сервер/перезапуски/phone/агенты,
  ни чтение actual accounts/logs/env/tokens. Старый installDist **не пересобран**.
- Root после consumer integration отдельно проверяет incoming/outgoing/accepted
  labels, known name/no username/blank name/duplicate nick, structural requestId
  actions и запрет pending activity access. Никакого PASS заранее не заявлено.
- После scope approval sysadmin может обновить **API image** новой сборкой; worker/
  DB/SQL data/restart/deploy сами по себе этим child не менялись. Обновление
  установленного client consumer также необходимо для устранения UUID label.

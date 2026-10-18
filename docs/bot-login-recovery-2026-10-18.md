# Telegram-вход и worker87 — 2026-10-18

## Диагноз и исправление

Владелец сообщил: бот не ответил, кнопки подтверждения не было. Узкая read-only
SQL projection показала один истёкший challenge (TTL5мин), без Telegram/account
binding, sessions0. Worker/API healthy, poll/lease прогрессировали; inbox processed1,
outbox0. Последнее normalized событие — noop/argumentCount0. Исходный input и
timestamps noop не сохранены: нельзя доказать, что это был bare `/start`, или где
потерялся payload. Владелец не обвиняется в ошибке.

Source подтвердил UX-дефект: backend80 bare `/start` превращал в noop и молчал.
Worker87 отвечает на новые bare `/start` и `/help` безопасной инструкцией. Это
**не** вход и не привязка к «последнему» challenge. Valid fresh UUID сохраняет
confirm/deny кнопки; invalid/expired/consumed/other-bound links и ошибочные login
callbacks дают одинаковый generic ответ без раскрытия состояния/identity.
Identity/PKCE/TTL/one-use/SQL migration не менялись; processed noop не replay.

## Проверки и rollout

- MAIN backend `test installDist`: **87 passed/0 failed/0 skipped**, старые80+7новых
  fake protocol tests; не реальная Telegram-приёмка.
- JAR SHA256 `8ebccc3727c422fdff6c1bdcee33e38bab97435e41ae985e218f025d62643fef`
  совпал локально/на сервере; SQL V001 checksum сохранён.
- Развёрнут **только worker**, image
  `sha256:b47432c6cfb4cf4a1959b18fa2bd97f521b54de035351bf22d525df51a02002f`.
  API остался80 на
  `sha256:4d820186b4f15068348e8c9a6b4d9c01099e99285fbd47434c41442c0c3b09b6`.
  `BACKEND_IMAGE` — API/default, optional `BOT_IMAGE` — отдельный worker.
- Старый poller остановлен SIGTERM, свободная/истёкшая lease подтверждена до нового
  старта. Один healthy worker/restart0;7samples/36s live poll progression, offset не
  регрессировал, queue counts не изменились. SQL rewind/delete/drop не было.
- DB/env/proxy/secrets/security profile сохранён. API/DB/proxy/Nextcloud IDs/images,
  Serve/private443/public gateway metadata неизменны. Сообщений/login от агентов нет.
- Staging stdin failure исправлен до остановки worker. Первый ready candidate
  консервативно откатился на80 из-за сравнения порядка profile metadata. После
  нормализации env/mount и раздельных image/profile checks rollout PASS. Restart0
  относится к финальному контейнеру, не отсутствию неудачных попыток.
- MAIN infra `python3 -B infra/scripts/validate.py`: **37/37 PASS**, AST/PyYAML PASS;
  local Compose SKIP (нет плагина), actual server Compose config PASS.

Rollback: `/var/backups/chrona/worker-help87-1792338258384166442/`; старый image
сохранён. Только worker stop → release/expiry lease → точные config/image backups →
bot `--no-deps`; не трогать API/DB/proxy/gateway/offset/inbox/outbox.
`infra/scripts/worker_help_rollout.py` — bootstrap из80: **не повторять после87**.

## Действия владельца

1. В Chrona «Проверить статус входа» для старого истёкшего pending.
2. Начать **новый вход из Chrona**, открыть именно новую generated Telegram-ссылку.
3. При необходимости «Запустить», затем подтвердить **свой** запрос кнопкой бота.
   Вернуться в Chrona/проверить статус в пределах5мин.
4. Новое bare `/start`/`/help` должно дать инструкцию, а не вход. Старое processed
   событие намеренно не переигрывалось.

Реальная доставка нового ответа/confirm/exchange/session/`/me` ещё не проверена.
Пока владелец входит — не instrumentation/reinstall/restart/clear-data и не чтение
vault/privateDB/чата. Login не разрешает upload: sync — отдельный bounded consent.

# Session / network / stale notices — core source fix

2026-10-19, ID `chrona-session-notice-fix-20261019`, chain `[archi]`.
Baseline по root: MAINbea052f, code7/0.4.1-goals установлен через updater,
подтверждён владельцем. Пользователь сообщил частые login/offline/load ошибки
при фактически работающей сети/sync. **Ниже — найденные source paths и правки,
не доказанная runtime-диагностика конкретного устройства.**

## Причины в исходниках → изменения

1. `DefaultNetworkObserver` вызывал disconnect всех активных HTTPS connections
   на смене/потере default route. Такой abort мог оборвать уже отправленный
   одноразовый refresh и сам создать SESSION_REFRESH_UNCERTAIN. VALIDATED=false
   также трактовался как offline, хотя это Android probe, не проверка Chrona API
   через VPN. Теперь observer сообщает только route hints, без abort и без
   интерпретации capabilities. Client больше не ставит OFFLINE по callback;
   реальная ошибка HTTP остаётся источником сообщения о недоступности. Network
   available лишь будит controller/reset transient delay, не очищает конфликты.
2. `SessionClient.me()` выполнял authorized GET под mutex, но затем отпускал его
   до сохранения profile. В промежутке другой запрос мог поставить нормальный
   rotating=true; поздний profile persist классифицировал его как неопределённый
   refresh и показывал welcome. Теперь request/parse/bind/profile persistence
   атомарны под тем же singleton session mutex, через private authorizedLocked.
   Обычная ротация сохраняет refreshInFlight distinction; startup с оставшимся
   durable rotating marker по-прежнему означает неопределённый исход.
3. Ротация теперь завершает bounded transport/persist в NonCancellable section:
   закрытие VM/отмена вызывающей coroutine не оставляют marker только из-за
   lifecycle cancellation. Это не второй sender/FGS и не обход OS process death.
   Transport timeouts/TLS/default VPN route/HTTP guards сохранены. Внешняя потеря
   ответа после отправки всё ещё принципиально неопределённа.
4. Session AuthView получил in-memory generation по смене origin/account/sessionId.
   Authorized ошибки помечаются generation под mutex. Client и VM применяют
   delayed errors только к актуальной сессии/операции; старый OFFLINE/uncertain
   после успешного нового login не перезаписывает его состояние. Реальный
   UNAUTHENTICATED401 текущей сессии обрабатывается прежним security path.
   Generation не токен, не DTO/vault schema, не новая запись в БД.
5. Resume раньше вызывал RefreshLoginStatus даже для обычного valid profile;
   refreshLogin повторял `/me` и refreshFriends, и независимая ошибка friends
   выглядела как ошибка только что завершённого login. Теперь resume проверяет
   только pending login; no-pending refresh — no-op. После состоявшегося exchange
   transient `/me` failure не объявляет login неудачным: остаются сессия и cached
   identity. Настоящие invalid response/binding/auth failures не маскируются.
6. В foreground loop social retry, raw sync и selected friend refresh имели один
   catch: friend404/offline/policy могли пометить raw sync ERROR/OFFLINE и даже
   не дать ему выполниться. Теперь raw sync выполняется независимо, friend/social
   ошибки имеют свои notice scopes/status и не меняют общий raw-sync backoff.
   Background social failure также не блокирует собственный raw cycle.
7. Success очищает старые transient notices соответствующей операции; новый login
   убирает устаревшие auth/transient notices и сбрасывает старый retry delay.
   Повтор одной неизменной ошибки не создаёт новый notice ID каждые5s. VM передаёт
   очищение именно client-owned notice, не стирая локальное сообщение другой
   операции. Friend results/errors также защищены session generation.
8. Cached profile сам по себе больше не очищает persisted queue error в updateMeta.
   Write conflicts/policy/binding/consent guards и frozen operations не очищаются
   как «успешный sync». Auth-required/uncertain — session state, а не новая
   persistent ошибка очереди. Transient notice не вытесняет уже видимый hard
   write conflict. Никакого clear-data/миграции/изменения raw.

## Точные файлы / ownership

- `chrona/src/main/java/by/bebuliby/chrona/data/client/SessionClient.kt`
- `chrona/src/main/java/by/bebuliby/chrona/data/client/ClientTransport.kt`
  (только optional in-memory generation в ClientFailure; transport/TLS не ослаблен)
- `chrona/src/main/java/by/bebuliby/chrona/data/client/AndroidClient.kt`
- `chrona/src/main/java/by/bebuliby/chrona/data/sync/DefaultNetworkObserver.kt`
- `chrona/src/main/java/by/bebuliby/chrona/data/ChronaContainer.kt`
- `chrona/src/main/java/by/bebuliby/chrona/presentation/ChronaViewModel.kt`
- `chrona/build.gradle.kts`: **code8 / 0.4.2-session**.

UI/таймер notices5s — sibling, здесь UI не менялся. Backend/infra/shared rootdocs
не менялись; новых сервисов/зависимостей/endpoint/vault/DB migrations нет.

## Проверки / остаток

Только targeted source reads/edits. **Tests/lint/build/Gradle/Git/server/phone/
agents/model checks не запускались.** Нет compile/runtime/PASS и нет обещания,
что все повторные login на устройстве уже устранены.

Потерянный ответ refresh, который мог быть выполнен сервером, всё ещё требует
повторного входа или отдельно согласованного server idempotent recovery contract.
Spent token не replay, security/401/binding/TLS не ослаблены. Профиль из cache —
last-known identity, не утверждение о свежем HTTP успехе. Backend read-only анализ
интегрирует root отдельно; новая серверная схема здесь не изобреталась.

**READY / STOP writing**: root делает одну сборку, доставка code8 только через
in-app updater. USB installs и публикация этим исполнителем не выполнялись.

# VPN/network + sync client — 2026-10-18 (source only)

ID `chrona-vpn-sync-client-20261018`, archi → developer, MAIN. Provided root baseline
9352444; Git не проверялся. **Ни tests/compile/lint/build, ни phone/server/HTTP/vault/
личных DB проверок этим child не было.** Root отдельно собирает обновление.

## Предварительные причины — что source доказывает и чего нет

- Старый SessionClient.rotate catch-all удалял session при любой ошибке, включая
  DNS/timeout/VPN switch; initialize также удалял rotating session. Это реальный
  source-путь logout после сетевого сбоя, **не доказательство конкретного crash**.
- Старый cold-open показывал profile=null до успешного `/me`, даже если credentials
  оставались в vault. Offline мог выглядеть как logout без фактической потери session.
- Open collector checkpoint был local-only; immutable interval попадал в outbox
  только при закрытии. Непрерывный Shorts мог долго не доходить до friend history.
  ACL/view scope и конкретную асимметрию рассматривают другие siblings; grant здесь
  не менялся, чужая история не копируется в owner Room.

## Auth/transport fix

- Default Android HTTPS/network/DNS сохранены. Нет physical network binding,
  NOT_VPN filter, custom DNS/IP/host fallback, trust-all или redirects.
- HttpsTransport отмечает только delivery phase boolean: если refresh body точно
  не начал отправляться (DNS/connect/TLS failure), старая unspent refresh запись
  сохраняется и допускает обычный bounded retry. Unknown/may-have-sent не выдаётся
  за безопасный retry. Ошибки без URL/body/cause, tokens/verifier не логируются.
- Только401 с корректным API code UNAUTHENTICATED запускает auth refresh/authoritative
  invalidation. HTML/invalid JSON401 не считается автоматически истёкшим токеном.
- MAY-have-rotated/lost response сохраняет encrypted rotating record и last identity;
  SESSION_REFRESH_UNCERTAIN останавливает authenticated flow/старый refresh replay.
  Restart больше не удаляет rotating credentials. Подтверждённый refresh401 остаётся
  настоящей auth ошибкой, не переименовывается в «VPN offline».
- Cached own RemoteProfile добавлен optional полем encrypted AtomicFile record,
  validated against session account/SQL binding. Старые records совместимы; для
  прежней подтверждённой session без cache используется generic «Профиль» с её
  настоящим account UUID, не выдуманное имя. Свежий `/me` обновляет cache. Cache
  означает last-known local identity, **не** свежую сетевую авторизацию; все API
  запросы по-прежнему проверяются сервером. Logout/target change очищает cache
  вместе с session, чужая identity не переносится.
- Application-lifetime DefaultNetworkObserver следит только за сменой default
  network/usable state (в том числе VPN), без выбора маршрута. Старые sockets
  disconnect-ятся, следующие используют текущую default route; Connection:close
  предотвращает stale pooled reuse. Restored route снимает transport cooldown и
  запускает coalesced retry, не сбрасывает credentials. ACCESS_NETWORK_STATE normal
  permission объявлен явно (он уже входил через WorkManager).

## Частота и работа без открытого UI

- Active process loop: nominal10s вместо30s; producer/login/network signal5s вместо10s,
  без бесконечного postponing debounce. Ошибки дают bounded backoff до120s, повтор
  одинаковой ошибки не создаёт новый banner каждый цикл.
- Новые immutable confirmed Shorts slices каждые5s; между ними нет выдуманного gap,
  суммы monotonic duration не растут. Checkpoint/recovery/stall/UNKNOWN/lock/Home
  guards сохранены. Raw source/content/window metric и manual actual prohibition
  не менялись. В API используется существующий endReason unknown как техническая
  граница slice, не новое состояние UNKNOWN экрана.
- Сlices увеличивают число raw interval/segment rows; длинные просмотры могут
  потребовать будущей UI grouping/pagination, но не удаления фактов/смены ACL.
- Backlog pacing минимум100ms между raw ACK; mutex/frozen body/key/revision/ACK
  атомарность и bounded cycle100/~90s сохранены. Нет100HTTP/s flush.
- Application.onCreate начинает тот же singleton container даже headless; уже
  включённая Accessibility service использует его и может поддерживать process
  после закрытия Activity. Не создаётся новый service/notification/system component.
- Уже открытый authorized friend snapshot refetch-ится в том же owner/range/period;
  concurrent close/смена selection защищены object identity.403/404/401 убирает
  snapshot; offline сохраняет только уже доставленное с error state/banner, не
  утверждает новый доступ. Server ACL выполняется при каждом GET, grants неизменны.
- WorkManager остаётся network-constrained unique work + **periodic min15min**.
  Это fallback при отсутствии active process, **не5s scheduler**. Healthy cadence
  примерно5–10s + фактический transport/IO latency, не SLA: Doze/OEM/process death/
  force-stop/ошибки могут задерживать. Свайп UI не равен Settings force-stop.

## Не решается безопасно одним client patch: refresh recovery contract

Текущий backend хранит hash и меняет refresh одноразово; одинаковый старый refresh
после lost response нельзя посылать снова: reuse detection может отозвать family.
Client **не** отправляет новых полей и **не** придумывает endpoint. Участок с rotating
uncertainty остановлен до безопасного нового user login/отдельного server recovery.

Предложение root на отдельное согласование: durable client rotation operation ID,
атомарный server dedup той же операции с короткоживущим encrypted cached token
response, bound к прежней session/device/request fingerprint. Идентичный retry
получает тот же результат, другой replay старого refresh сохраняет reuse-revoke.
TTL/хранение secrets/expired recovery требуют отдельного security contract; текущий
Identity.refresh принимает только refreshToken, клиент этот контракт не меняет.

## Если нужна более строгая непрерывность

Отдельный user-approved план, **не реализовано здесь**: explicit foreground start,
видимое ongoing notification со Stop; FGS/dataSync manifest permissions и Android13+
notification consent; ограничения питания/старта/timeout. Target35+ dataSync FGS
имеет Android15+ quota/timeout (не24/7 guarantee), force-stop не обходится и этим.
Нельзя обещать постоянные5–10s после OS kill без такого отдельного плана и принятия
ограничений. Сейчас используется только уже разрешённая Accessibility + WorkManager.

## Changed files / статус

`data/client/{ClientTransport,SessionVault,SessionClient,AndroidClient}.kt`,
`data/sync/AutomaticSyncWorker.kt`, новый `data/sync/DefaultNetworkObserver.kt`,
`data/ChronaContainer.kt`, `tracking/{TrackingEngine,ShortsAccessibilityService}.kt`,
`chrona/src/main/AndroidManifest.xml`, `chrona/build.gradle.kts`.
Version **code5/0.3.3-sync**. Managed URL/env config, schema/raw storage/owner bindings,
backend/infra/UI/share scope не менялись. Profile cache — optional existing encrypted
record field, без Room migration/purge. APK этим проходом не создавался/не установлен.
UI отображение нового recovery code может требовать root mapping; старые PASS counts
не evidence для этого source. Тесты намеренно не менялись ради green и не запускались.

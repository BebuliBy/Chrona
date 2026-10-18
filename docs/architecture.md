# Chrona: архитектура и границы решений

Статус на 2026-10-18: выбран локальный вариантБ, начат UI-чекпоинт. `app` — прежняя Accessibility-диагностика; `chrona` — Compose UI с чистым in-memory DemoReducer/DemoViewModel и Theme, без реальной БД/collector. [UI-результаты](ui-checkpoint-2026-10-18.md), [spike](spike-results-2026-10-17.md). Серверная архитектура ниже не реализована.

## Целевая схема

```text
Android: UI → локальное хранилище → очередь синхронизации
                 ↑                         ↕ HTTPS API
       collectors активностей       общая серверная логика
       (первый — Shorts proof)          ↙             ↘
                                   SQL storage    bot adapter
                                                      ↕
                                             Telegram через proxy
```

Android и Telegram-бот обращаются к одним правилам пользователей, дружбы и доступа. Прямого доступа клиента к SQL нет. Начальное направление — лёгкий модульный backend, не микросервисы; конкретный язык/framework/SQL-продукт ещё не выбран. PostgreSQL — кандидат, не установленное проектное решение.

## Модули

- **Identity:** внутренний ID пользователя, внешняя Telegram-привязка, отзываемые сессии устройств. Telegram username/номер — изменяемые атрибуты, не ключ.
- **Activities:** тип активности и единицы, интервалы, качество/пробелы измерения, дневные агрегаты. UI-detector не смешивается с социальными правами.
- **Collectors:** платформенное наблюдение с явным пользовательским разрешением, возможностью выключения и состоянием unknown. Общий интерфейс определить по результатам proof, не строить заранее произвольную plugin-систему.
- **Friends/sharing:** взаимное согласие; policy по умолчанию и отдельные разрешения друга. Авторизация фильтрует и суммы, и точные интервалы на сервере, одинаково для API/bot.
- **Sync:** локальное устойчивое хранение, operation IDs/дедупликация, retry, last-sync, явные удаления/конфликты. Конкретный контракт пока не выбран.
- **Notifications/bot:** вход и продуктовые действия через общие use cases. Bot notification не означает push приложения; дополнительные push-каналы не обязательны для первого результата.

## Регистрация — выбранный пользовательский сценарий

Приложение начинает одноразовый запрос → открывает бот → пользователь явно подтверждает/отменяет → возвращается в приложение. Запрос связан с инициирующим устройством/сессией, краткоживущий и однократный. Один публичный start-payload не даёт получить app token. Сервер проверяет источник Telegram updates; bot token никогда не попадает в APK. Детали verifier, возврата, угроз session swapping, recovery и отзыва сессий проектировать и тестировать перед реализацией.

## Приватность интервалов

Владелец разрешает конкретные типы активности конкретному другу. Разрешение может включать **точную временную шкалу**, как запросил пользователь. Тексты/контент/полные UI dumps не являются частью этой шкалы. До согласования наследования настроек не расширять sharing автоматически. Проверка доступа выполняется на сервере при каждом чтении и перед дальнейшей доставкой ботом, не только скрытием карточек в UI.

## Среда и переносимость

- Прототип: Kotlin/Android, обычная framework Activity, без Compose/Room/DI/backend. AGP9.1.1/Gradle9.3.1/JBR25, bytecode17, compile/target36/min26 **только для spike**; минимальная ОС продукта не выбрана. JUnit4.13.2 для локальных тестов. Последующие библиотеки продукта выбрать отдельно по задаче.
- Проверенная отдельно toolchain: Studio2026.2.1.8/JBR25, SDK API36, AVD Tracker_API36; временный smoke AGP9.1.1/Gradle9.3.1/built-in Kotlin2.2.10/bytecode17. Это входной ориентир, не обязанность копировать все версии в продукт без проверки.
- SDK: `/home/niki/Android/Sdk`; IDE/JBR: `/home/niki/.local/opt/android-studio`. Пути машины не вшивать в будущий общий build; local.properties и секреты не коммитить.
- Стабильный HTTPS-домен/API configuration и воспроизводимые миграции/развёртывание обеспечивают перенос. Произвольный новый origin не получает старые токены автоматически.
- Для Telegram планировать изолированный outbound proxy, без автоматической смены default route сервера. Доступность/параметры VPN ещё не проверены и не развёрнуты.
- Синхронизация не заменяет backup. Нужны согласованный off-host backup и доказанное восстановление; destination/retention ещё не выбраны.

## Ограничения

UsageStats не даёт готовой метрики Shorts. Фоновая работа/boot/force-stop зависят от Android и устройства. Accessibility и способ установки для частной группы не отменяют системных ограничений и согласия пользователя. Сервер не восстанавливает пропущенное наблюдение. Клиентские измерения не являются защищённым от подделки доказательством для соревнований.

Текущий spike использует **один active root**, не перечень видимых окон. На Home с маленьким Shorts он дал `youtube_not_foreground/no interval`. После этой проверки пользователь явно решил **исключить маленькое окно** (SHORTS-003/DEC-006), так что поведение соответствует правилу в наблюдённом режиме. Это не превратило эвристику в универсальный детектор размеров/режимов: focused Samsung pop-up/PiP/split-screen отдельно не проверены (OPEN-WINDOW-MODES). Не расширять сбор на другие окна автоматически, не читать содержимое других приложений и не обещать полного покрытия без тестов.

## Локальная реализация: выбранное направление и будущие детали

- Kotlin/Compose/Material3 + ViewModel/StateFlow используются в первом UI-этапе описанного плана; Room/DataStore — следующий storage-этап, пока не подключены. ВариантБ подтвердил manual habits/goals/streaks, не любую новую бизнес-семантику или финальный дизайн.
- Один Android app с ясными пакетами/слоями `ui`, `domain`, `data`, `tracking`; отдельные Gradle-модули вводить при реальной необходимости, не ради количества слоёв. UI не зависит от resource IDs YouTube напрямую.
- Локальный профиль без Telegram/регистрации в этом этапе; будущая привязка Telegram/sync через отдельные границы, без подмены утверждённого будущего bot flow. Интервалы получают стабильные ID; сеть/бот пока не реализуются.
- Длительности по monotonic time, timestamp UTC, явные gaps/качество, восстановление после потери процесса без дорисовки просмотра. Разбиение суток/timezone и точность принять до реализации.
- Event-driven collector плюс обоснованный safety heartbeat вместо безусловного переноса каждосекундной записи spike в production. Это направление оптимизации, не обещанный результат экономии батареи.
- Применять SHORTS-003/004; отдельные состояния «точно не Shorts», «наблюдение недоступно» и «отключено пользователем» не скрывать одним ложным нулём.
- Target/minSdk, final applicationId/namespace, версия Compose/Room, миграции и tests выбрать после принятия плана/проверки совместимости. Не менять диагностическую identity или установленный APK в рамках одной смены названия.

### Технические границы текущего UI

- Отдельный `chrona` module сохраняет воспроизводимость старого `app` без массового переноса. Debug APK `by.bebuliby.chrona.debug`, target/compile36/min26; релизный base ID `by.bebuliby.chrona` пока зарезервирован технически, до настоящих данных подтвердить final identity/подпись. Release key не создавался.
- Compose compiler2.2.10 соответствует встроенному Kotlin2.2.10/AGP9.1.1. Для консервативной совместимости выбран официальный Google BOM2025.08.01 (UI/runtime/foundation1.9.0,Material3 1.3.2), ActivityCompose1.11.0, LifecycleCompose2.9.2. Gradle успешно разрешил/собрал эту связку с JBR25; более новый BOM2026.09.00 не используется.
- `preview/DemoState` и DemoReducer — **fixtures/контролы UI**, не production-domain серий/периодичности. Значения обновляются только в памяти VM. Шаблон дневной цели, числа серии и поле лимитов ввода — примеры для дизайна, не утверждённый алгоритм привычек.
- `ui/ChronaTheme` централизует draft tokens; две палитры/темы нужны для выбора пользователем. Тема хранится только в VM, не DataStore. `@Preview` компилируются, факт их показа IDE ещё не проверен.
- Основной APK не имеет INTERNET/Accessibility/storage/microphone permissions; AndroidX добавил только собственную signature-scoped dynamic receiver permission. Нет фонового сбора, серверов или импорта старых диагностических интервалов.

## UI и локальное ядро — независимые рабочие направления

По запросу пользователя отделить локальные storage/tracking от меняющегося дизайна. Это **план/предлагаемый контракт, не готовая БД и не запущенный второй агент**. Серверный backend/deploy всё ещё вне текущего локального этапа.

Предлагаемая граница:

```text
Compose UI → use cases / observed state → repository interfaces → Room
                                                   ↑
                          Android Shorts collector → raw observations/intervals

                           согласованные scoring/aggregation отдельно от хранения
```

### Предлагаемые данные без преждевременной математики

- Habit: стабильный ID, название/единица, direction(minimum/maximum), состояние/дата создания; цель и её revision отдельно, чтобы не терять исходные значения при правках.
- ManualEntry: ID,habitId, выбранный день, timezone/UTC metadata, actual в точном decimal representation, источник/время изменения. Actual не обрезать по goal. Точные правила применения goal revisions/прошлых правок ещё принять.
- ActivityInterval: ID,activityId,start/end UTC и monotonic duration/boot-session metadata, причина закрытия/качество/collector version. Process gap не достраивать просмотром; диагностика только минимальных YouTube-признаков, не тексты/ролики/чаты.
- TrackerHealth/gaps: последнее подтверждённое наблюдение/доступность/разрешения, чтобы отсутствие данных не становилось ложным нулём. Не хранить эмоции/цвет как первичные факты.
- **ManualEntry не может записывать actual автоматического YouTube**: пользователь явно отклонил ручную коррекцию, источник факта только collector/система(SHORTS-005). Предложение Adjustment для YouTube не принято и не входит в контракт. Редактирование плана/лимита отдельно от факта.

Room должна иметь schema version/тестируемые миграции, без молчаливого destructive fallback. Переход из демоданных — явный; fixtures не импортируются в настоящий учёт. Release signing/identity, финальные правила дней/частот и схема ещё требуют принятия до реальной пользовательской истории. Техническая готовность к future sync не означает сетевые запросы в локальной версии.

Фактическое ядро можно развивать, не ожидая выбора палитры: хранить raw facts и отдельно подключать согласованные вычисления. В этом шаге источники/DAO/БД/collector не добавлены и фоновой работы нет. Параллельное агентное поручение фактически не отправлено.

**Текущий статус:** пользователь возобновил запрос реализации и server prep (DEC-015/016), zero-limit success уточнён как 100%. Штатным task обычному desktop поручен review/исправление новых профилей; эта конфигурационная работа проверена, но **не является High-разработкой или серверным исполнением**. Текущий API Арчи не предоставляет `chrona-*` targets/model-variant override. Ниже — передача разрешённому High-координатору, не работающая серверная БД/инфраструктура.

## План исполнения координатором — пока не выполнен

### Предварительные условия запуска

1. Реально доступный `task` (V1)/`subagent` (V2) с нужными разрешёнными High-профилями. В текущем API Арчи есть только browser/desktop/sysadmin/developer, без `chrona-*` и model/variant override; developer дополнительно требует ручного вызова пользователем. Обычный desktop review подтверждает работу task, но не запуск High. Не стартовать новые OpenCode/model сессии из shell/HTTP ради обхода.
2. High задаётся фактическим supported task/session override или предварительно согласованными разовыми профилями, с проверкой model/variant metadata. Если этого нет — не запускать Medium и не назвать его High. Штатные профили/default Medium сохранить; при временных изменениях вернуть/проверить в конце. Сейчас5профилей/default проверены Medium, config не менялся.
3. Подтвердить target по датированной server-карте, не угадывать private адрес. Private закрыт всем 7 job-профилям, включая coordinator: обычный Арчи/владелец передаёт только необходимый подтверждённый SSH alias/контекст, не портрет/credentials, без публикации в Git. Координатор не обходит запрет через поиск/shell. Текущий аудит ещё не выполнен; старый Debian/Docker/CPU inventory — ориентир, не runtime.
4. До пакетов/сервисов/портов получить точный план после аудита и отдельное нужное sudo-разрешение. Не выполнять system upgrade/reboot/global route/firewall изменения по умолчанию. Прежние bot dirs можно реорганизовывать только в относящемся scope с backup/проверкой активности, не удалять чужое рабочее окружение вслепую.

### Разделение исполнителей / файлов

| Исполнитель | Задача | Граница |
|---|---|---|
| Координатор | Контракт API/DB/метрик, зависимости, интеграция, Git/dev и проверка High/Medium | Один Git-писатель; это пока не запущенная роль |
| Developer UI | UI-v2 по requirements:nav/profile/cards/emotions/history | Отдельное worktree/ветка/файлы UI, без секретов/deploy и гонки со сборками другого исполнителя |
| Developer Android core | Room/DAO/repositories,raw manual entries,Shorts collector,source constraints/миграции/score tests | Не переписывать одновременно UI/sharedGradle; owner общих build/schema/DTO назначает координатор |
| Sysadmin | Живой аудит,безопасный Xray/proxy/Docker/секреты/сеть/deploy/backup/документация | Никакого глобального VPN/непроверенных удалений/секретов в Git |
| Developer backend при необходимости | API/bot/serverSQL и протокольные тесты по общему контракту | В текущем task-пакете запускается root по запросу sysadmin, с отдельными server-файлами; не второй автор тех же Android/core файлов |

Использовать обычный task/subagent с ID/chain/целью/scope/checks, не вызывать себя/предков. При необходимости второго sysadmin его назначает root-координатор параллельно, не sysadmin-self-call. Максимальная глубина2. Дочерние помощники не commit/push/общий журнал. Для worktrees/shared files нужна явная изоляция; не запускать несколько тяжёлых Android build/install на одном устройстве одновременно. Продуктовые High-сессии/worktrees не создавались; обычная desktop review-сессия отдельно проверяла конфигурацию.

### Предлагаемое серверное устройство

```text
new authorized consumers ─── private Docker network ── Xray SOCKS :10808 ── outbound VLESS
                                ↑
                           Chrona bot worker ── Telegram Bot API
                                ↕
                      application/API layer ── own SQL data
                                ↕ HTTPS
                       Android local-first Room
```

- Отдельный reusable proxy service/закрытая сеть и документированный endpoint типа `socks5h://xray-proxy:10808`. Для host-потребителей optional bind только `127.0.0.1:10808`, если порт свободен; внутри Docker localhost другого контейнера не является proxy. **Не** публиковать SOCKS наружу/на0.0.0.0host, не использовать privileged/TUN/global DNS без отдельной необходимости.
- Официальный Xray release/image с закреплённой версией/digest/provenance и проверкой CPU/arch (старый аудит безAVX2), не случайныйcurl|sh. Копировать приватно только необходимую **client**-конфигурацию/outbound/key; не SSH private key и не всё personal VPN окружение. Laptop proxy не отключать/не менять.
- Secrets вне project/build context/Git: owner-limited files/0600/read-only mount. Значения не выводить, не писать token-bearing Bot API URLs в logs. `.env.example` только placeholders; Compose secrets сами по себе не шифруют storage.
- Для каждого consumer явно проверить поддержку SOCKS/remote DNS и фактический маршрут. Одного HTTP_PROXY/ALL_PROXY недостаточно для любой библиотеки. Если клиенту нужен HTTP proxy, это отдельный inbound/адаптер, не молчаливое использование HTTP наSOCKS-порту.
- Бота создать через BotFather владельца/разрешённый интерфейс. Username availability и token не угадывать; пока бота/токена нет, mock не считается успешной Telegram-проверкой.
- Для требования≈5с предлагается `getUpdates(timeout=5)` long polling с HTTP timeout запасом, durable offset/update-id dedup и одним consumer, без лишнего sleep после каждого ответа. Это проектное предложение реализации интервала, ещё не испытанное/не развёрнутое. Poll loop/новые сообщения/ошибки proxy проверяются реально.
- Docker/Compose persistent volumes, schema migrations,health/readiness,ограниченные logs и `restart: unless-stopped` как кандидат. **Internet loss требует reconnect/retry/backoff в приложении/proxy**; restart policy не замечает живой зависший worker, а healthcheck unhealthy автоматически не рестартит контейнер. Не перезагружать весь сервер ради сетевого сбоя.
- Существующие services/ports/data сохраняются; новые Chrona components изолированы. Server SQL(например PostgreSQL) — собственные DB/role/volume, не таблицы Nextcloud; окончательный выбор после runtime-audit. Нельзя считать Android Room заменой серверной SQL или наоборот.
- Регулярный backup/restore test нужны отдельно от sync; off-host destination/ключ восстановления выбрать, не покупать storage/не публиковать secrets автоматически.

### Что должно быть доказано перед «близко к работе»

- Xray config validation/start/version/listener/privacy boundary; laptop listener/SSH/Tailscale/старыйHTTPS не нарушены.
- Реальный Telegram `getMe/getUpdates`/test message-response через явный proxy без токенов/сырых chats в отчёте; работоспособность новых consumers подтверждается одинаковым endpoint/config template.
- Контролируемый сбой только собственного proxy/bot network (не host internet/SSH), reconnect/повторная доставка после восстановления, нет второго polling consumer/повторной бизнес-операции, offset/jobs сохраняются после restart.
- Core DB/tests/migrations/безопасное восстановление; Shorts actual только collector, unknown не ложный0. Zero-limit known0 имеет100% score, raw actual/0 не вычисляется; undefined zero-curve coefficient не выдумывать.
- До независимого API/bot/client кода общий DTO/auth/session/ownerID/friendACL контракт. Реальные профили/синхронизация/разрешённые интервалы проверяются end-to-end, не «потом просто склеим» по разным догадкам.
- Документация: что реально изменено/версии/неудачи/команды/checks/rollback/backup и добавление нового consumer к10808; config примеры обезличены, не имитировать непроверенное успехом.

**Фактический статус этого плана:** подготовка документов/профилей и штатный desktop review с исправлением прав. Ни server SSH/sudo, ни перенос config/key, ни BotFather/токен, ни Docker/deploy, ни продуктовые High-вызовы не выполнялись. Git-доставка документов/конфигурации — отдельный принятый workflow, не серверное исполнение. Блокер запуска нужных High-targets остаётся.

### Подготовленные High-профили: конфигурация, не запущенные работники

Для корректного запуска **без shell-обхода** добавлены проектные `.opencode/agents/`:

| Профиль | Назначение |
|---|---|
| `chrona-coordinator-high` | Явно выбираемый primary-координатор задачи наHigh |
| `chrona-ui-high` | Developer UI-v2 |
| `chrona-core-high` | Developer Android Room/tracking/метрики |
| `chrona-backend-high` | Developer server API/bot/SQL-код |
| `chrona-infra-high` | Sysadmin audit/Xray/proxy/Docker/deploy/recovery |
| `chrona-browser-high` | Официальные источники/разрешённые веб-интерфейсы |
| `chrona-desktop-high` | Локальная среда/ADB/необходимый laptopVPNclient scope |

Все job-профили задают `openai/gpt-6.1-sol`, `variant: high`. **Пять обычных глобальных ролей и provider/default не менялись, остаютсяMedium.** Нет project `default_agent` override. High-профили используются только при явном выборе/вызове, не как новый постоянный default.

Здесь применяется **плоская** схема: coordinator→helpers, у helpers task deny. Sysadmin возвращает запрос дополнительного кодера root, не вызывает свою/другие роли самостоятельно. Только root Git/integration. У helpers edit deny по умолчанию, разрешены только prospective own worktree/run; root/shared/peer правки проверены как deny. Private read/edit/external_directory закрыт всем семи, в том числе coordinator; необходимый контекст — только точечным handoff от обычного Арчи/владельца без секретов/портрета. Это не расширяет текущему чату инструменты или право вызывать себя.

Первоначальный packaged Desktop catalog test прошёл `CHRONA_HIGH_CATALOG_OK`. Последующий независимый review обнаружил, что read deny не закрывает grep, а helper edit без начального deny наследует ask. Перед исправлениями сохранены 7 исходных профилей: `/tmp/opencode/chrona-high-before-review-20261018/`. В финальных профилях grep отключён: backend проверяет его query, не файловый путь; нужный поиск только отдельно проверенной ограниченной командой/через координатора с явным scope. Неработающие узкие private-исключения coordinator удалены: проверка dirname остаётся fail-closed.

Финальный isolated test `/tmp/opencode/verify-chrona-high-review-20261018.cjs` прошёл **`CHRONA_HIGH_REVIEW_OK; 409 assertions; 7 High / 5 Medium`**. Он получает только in-memory каталог и проверяет правила извлечёнными без изменений pure evaluator/wildcard-функциями установленного backend. Synthetic own/root/peer/shared/private paths, task, grep, модели/default проверены без открытия реальных private-файлов. HOME/XDG изолированы, auth только тестовая, MCP/plugins выключены лишь в test override; listener/session/prompt/model calls отсутствуют. Глобальные config/пять обычных профилей неизменны. Это **не** actual High model execution и не полный tool-pipeline/OS sandbox.

Ограничения защиты: glob/list могут раскрывать метаданные, broad scans запрещены инструкциями. Shell/MCP/wrappers не файловая песочница; доступ/запись через них требуют отдельного scope/одобрения и не служат обходом read/edit/Git deny. Symlink/adversarial pipeline не испытывались. До кода root создаёт/назначает worktrees и ownership; в этом этапе они ещё не созданы.

Чтобы загрузить эту конфигурацию, пользователь должен полностью выйти/запустить OpenCode и открыть **папку `/home/niki/android-tracker`**, не desktop-customization. Затем новая UI-сессия с `chrona-coordinator-high` и явной проверкой High в выбранной модели. Root сначала проверяет **наличие всех шести `chrona-*` targets в task/subagent и runtime model/variant metadata**; одного обычного task недостаточно. Если нужных targets нет, остаётся блокер клиента/API, не повод shell/HTTP-обхода. Перезапуск не гарантирует этот каталог. После job проверить штатный Medium/default и сохранённый UI-выбор, не заявлять восстановленным то, что не менялось.

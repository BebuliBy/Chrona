# Chrona: архитектура и границы решений

Статус на 2026-10-18: UI-v2, Room2/DataStore/collector, Android HTTPS/PKCE/sync/social client и Kotlin/Ktor/JDBC backend объединены. Xray/Postgres/API/private HTTPS работают; после owner hidden token-install verified worker activated/auth enabled. Successful poll/SQL progress проверены, но phone/owner auth/sync E2E ещё нет. Fixtures только preview, старый `app` сохранён. [Интеграция](integration-2026-10-18.md), [контракт](data-api-contract.md), [runtime](server-deployment-2026-10-18.md).

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
- Локальный профиль работает без Telegram. Client уже реализует отдельно настраиваемый HTTPS/PKCE/session binding/sync; token/verifier — Keystore vault, не Room. Интервалы стабильные ID, случайный server Shorts UUID отображается на local system UUID без manual provisioning. Реальная привязка ждёт нового бота/телефонной приёмки.
- Длительности по monotonic time, timestamp UTC, явные gaps/качество, восстановление после потери процесса без дорисовки просмотра. Разбиение суток/timezone и точность принять до реализации.
- Event-driven collector плюс обоснованный safety heartbeat вместо безусловного переноса каждосекундной записи spike в production. Это направление оптимизации, не обещанный результат экономии батареи.
- Применять SHORTS-003/004; отдельные состояния «точно не Shorts», «наблюдение недоступно» и «отключено пользователем» не скрывать одним ложным нулём.
- Target/minSdk, final applicationId/namespace, версия Compose/Room, миграции и tests выбрать после принятия плана/проверки совместимости. Не менять диагностическую identity или установленный APK в рамках одной смены названия.

### Технические границы текущего UI

- Отдельный `chrona` module сохраняет воспроизводимость старого `app` без массового переноса. Debug APK `by.bebuliby.chrona.debug`, target/compile36/min26; релизный base ID `by.bebuliby.chrona` пока зарезервирован технически, до настоящих данных подтвердить final identity/подпись. Release key не создавался.
- Compose compiler2.2.10 соответствует встроенному Kotlin2.2.10/AGP9.1.1. Для консервативной совместимости выбран официальный Google BOM2025.08.01 (UI/runtime/foundation1.9.0,Material3 1.3.2), ActivityCompose1.11.0, LifecycleCompose2.9.2. Gradle успешно разрешил/собрал эту связку с JBR25; более новый BOM2026.09.00 не используется.
- `preview/DemoState` и DemoReducer — **fixtures/контролы UI**, не production-domain серий/периодичности. Значения обновляются только в памяти VM. Шаблон дневной цели, числа серии и поле лимитов ввода — примеры для дизайна, не утверждённый алгоритм привычек.
- `ui/ChronaTheme` централизует 12 палитр/3 стиля, IDs сохраняются DataStore. Preview компилируются; visual/real phone acceptance отдельно.
- APK имеет INTERNET/cleartext=false и user-enabled AccessibilityService, без microphone/broad storage. Tracker пишет только минимальные structural observations/intervals/gaps; fixtures и старые diagnostic intervals не импортируются.

## UI и локальное ядро — независимые рабочие направления

Разделение выполнено обычными developer-субагентами с разными ownership/worktrees. Общий data/UI/API contract передан до кода; root объединил UI/core/client/backend/infra и собрал main/test APK. Compose renderer не зависит от DAO/YouTube IDs; VM/repositories/collector и transport разделены. Сервер разрешён пользователем отдельным последующим сообщением и развёрнут sysadmin.

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

Ядро хранит raw facts, расчёты подключены отдельно. Room schema1→2 имеет явную migration без destructive fallback; frozen operations/ACK revisions/pull cursor атомарны, конфликт не удаляет local facts. Background sync — пока process-alive best effort после явного согласия, не обход Android force-stop и не обещание бессмертного сервиса.

**Текущий статус:** исходный план выполнен до source/server checkpoint, не до production E2E. User явно поручил обычным разработчикам реализацию и разрешил сервер/sudo; реальные child jobs перечислены в integration report. Новых ролей нет. Временный variant4 existing specialist profiles выставлялся High и возвращён Medium, без запрещённой пользователем повторной настройки/metadata-проверки; это не утверждение independently verified runtime effort.

## План исполнения координатором — пока не выполнен

### Организация через существующие роли

1. Обычный root **archi** назначает отдельные sibling-сессии **developer** для UI/core/backend; новые имена/профили не нужны. Прямой developer способен работать без task, не должен ждать настройки параллелизма и не вызывает себя/предков. В текущем API developer требует ручного вызова пользователем (`@developer` через autocomplete); это ограничение не обходить.
2. Task нужен только для реальных поручений помощникам. Вызовы текущего Арчи к desktop подтверждены returned child IDs. Не стартовать новые OpenCode/model сессии из shell/HTTP ради обхода; отсутствие инструмента в другой сессии диагностировать отдельно, не угадывать по файлу permission или советовать restart без причины.
3. Task не имеет model/variant override, parent effort не гарантирует child effort. В этом этапе по прямому указанию пользователя временно изменены только variants4 existing profiles, без новых ролей и без проверки High; после этапа восстановлен Medium. Не выдавать изменение конфигурации за независимо проверенные model metadata.
4. Подтвердить target по датированной server-карте, не угадывать private адрес. Арчи передаёт специалисту только необходимый SSH alias/контекст, не портрет/credentials, без публикации в Git. Доступ к секретам не расширяется ради оркестрации. Текущий аудит ещё не выполнен; старый Debian/Docker/CPU inventory — ориентир, не runtime.
5. До пакетов/сервисов/портов получить точный план после аудита и отдельное нужное sudo-разрешение. Не выполнять system upgrade/reboot/global route/firewall изменения по умолчанию. Прежние bot dirs можно реорганизовывать только в относящемся scope с backup/проверкой активности, не удалять чужое рабочее окружение вслепую.

### Разделение исполнителей / файлов

| Исполнитель | Задача | Граница |
|---|---|---|
| Обычный archi | Контракт API/DB/метрик, зависимости, интеграция, Git/dev и проверка High/Medium | Один Git-писатель; не новая роль |
| Developer UI | UI-v2 по requirements:nav/profile/cards/emotions/history | Отдельное worktree/ветка/файлы UI, без секретов/deploy и гонки со сборками другого исполнителя |
| Developer Android core | Room/DAO/repositories,raw manual entries,Shorts collector,source constraints/миграции/score tests | Не переписывать одновременно UI/sharedGradle; owner общих build/schema/DTO назначает координатор |
| Sysadmin | Живой аудит,безопасный Xray/proxy/Docker/секреты/сеть/deploy/backup/документация | Никакого глобального VPN/непроверенных удалений/секретов в Git |
| Developer backend | API/bot/serverSQL и протокольные тесты по общему контракту | Отдельное поручение root, с отдельными server-файлами; не второй автор тех же Android/core файлов и не подмена coder ролью sysadmin |

Использовать обычный task/subagent с ID/chain/целью/scope/checks, не вызывать себя/предков, максимум2уровня. Четыре worktree подготовлены root; UI/core/backend/infra писали разные файлы. Финальный social follow-up передан одному integration developer после завершения прочих writers. Дети Git/shared journal не выполняли; Git/integration и Android full-build — один root. UI/instrumentation на телефоне пока не запускались.

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

**Фактический статус:** Xray private10808/Postgres/API/HTTPS и verified bot worker healthy, auth enabled после owner token-install. Source/secrets разделены, old Nextcloud/services сохранены. Proxy recovery/DB restore и live poll progress проверены; owner phone/login/social E2E ещё нет. Не выполнять прежний synthetic authbegin503 smoke в enabled режиме — он создаёт challenge. Подробности/rollback в server deployment.

### Проверенный механизм обычных субагентов и нерешённая диагностика

- По исходнику установленного Desktop 1.18.31 несколько task-вызовов `subagent_type=developer` от root archi без task_id создают разные sibling sessions, а не self-call. Для продолжения используется returned task_id той же задачи; неверный ID может создать новую сессию, поэтому returned ID сверяется. Одновременно один child не возобновлять двумя запросами.
- UI: выбрать обычного archi/developer внизу composer; **после** агента выбрать variant High рядом с моделью. Это настройка основной сессии, не High всех детей. Для ручного developer-вызова выбрать `@developer` именно как agent autocomplete. Если task в этой сессии отсутствует, одно упоминание не добавляет инструмент.
- Обычные permissions на диске уже разрешают нужную делегацию. Pure tests установленного registry/filter/provider conversion: **31 assertions PASS**. Parent High → configured child Medium подтверждён кодом; новый blanket allow или имя с суффиксом high не решение.
- Read-only SQL-проекция только whitelist metadata реального Desktop-хранилища показала у найденной проблемной coordinator-сессии tools.task absent и отсутствие task/* session deny; фактический variant xhigh. Это исключает два конкретных механизма скрытия, но не доказывает наличие task в отправленной модели schema. Текущая рабочая archi-сессия имеет High и действительно вызывает desktop.
- Первоначальная точная Chrona root выборка не нашла старого developer. Дополнительная узкая SQL-проверка уже известного рабочего archi-root обнаружила в нём прежние developer-ходы: это **та же сессия после смены роли**, отдельный ID у пользователя не нужен. Только metadata последних 3 user/3 assistant developer: Sol, tools.task absent, user variants low/high/high, assistant low; тексты/parts/переписки/auth не читались. Эти поля не доказывают исходящий toolset/исторические permissions; следующий участок — effective runtime registry → prepared tools → delivery. Session permissions/конфиги не менялись.
- Последующее явное поручение пользователя обычным разработчикам получено, реальные task jobs UI/core/backend/sysadmin выполнены и дали код/серверный checkpoint. Пользователь отдельно запретил повторные High-проверки: source variants временно изменены/возвращены без них. Не возвращаться к старому metadata-расследованию как барьеру продолжения, не подменять выполненные task jobs ручными чатами и не приписывать непроверенные effort metadata.

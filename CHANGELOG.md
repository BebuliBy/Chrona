# История изменений

Здесь только факты внесённых изменений. Планы — в docs/roadmap.md, принятые решения — в docs/decisions.md. Разработка функций не считается выполненной по одному описанию.

## 2026-10-17 — исходная документация

- Созданы README, проектные AGENTS и связанные документы требований, архитектурного направления, решений, плана и Accessibility spike.
- Зафиксированы подтверждённые пользователем private-first распространение, Telegram bot login, индивидуальные/default sharing-настройки, точные интервалы разрешённых активностей и приоритет проверки открытого экрана Shorts.
- Неопределённые модели/версии устройств, критерии точности, область истории и семантика defaults явно оставлены вопросами.
- Код приложения, detector, Gradle build проекта, backend/bot/server/VPN не создавались; реальный Accessibility/YouTube тест не выполнялся. Git commit/push проекта не делались.

### Уточнение устройств

- Пользователь подтвердил: Android16/One UI8.5 на его телефоне; Galaxy A33(2022) у друга, ОС друга неизвестна. Обновлены требования и план теста; точную модель и YouTube version ещё проверить при подключении.

### Диагностический Accessibility prototype

- По прямой команде проверки Shorts добавлены минимальный Kotlin app, безопасное локальное объяснение/consent, AccessibilityService, candidate-ID classifier, UNKNOWN fallback и monotonic интервалы; без INTERNET и сбора текстов контента.
- Generated Wrapper9.3.1 проверен по SHA256; unit tests **8/8**, debug APK build/install/launch прошли на авторизованном SM-A376E/Android16/API36.
- Начальные candidate IDs не подтверждены на YouTube. Служба при контрольном чтении выключена, телефон Dozing; реальные Shorts-данные ещё не получены. [Отчёт](docs/spike-results-2026-10-17.md).
- Permission settings пользователя, сервер/VLESS/бот и Git commit/push проекта не менялись.

### Первые реальные данные Accessibility

- Пользователь самостоятельно включил службу и выполнил YouTube-сценарии. Собственный debug-report подтвердил наблюдение `reel_player_page_container` и6intervals-candidates суммой33,36с; это не ручная разметка и не подтверждённая точность.
- Записаны переходы UNKNOWN/краткие пропуски маркера; код/семантика подсчёта не менялись и пропуски не склеивались. Следом — контролируемый контраст Shorts/обычное видео.
- По просьбе пользователя в AGENTS и план добавлено явное уведомление, когда нужно включить/разблокировать телефон и какой экран открыть.

### Контролируемая неподвижная сцена Shorts

- После подтверждения пользователя сняты8последовательных замеров:8/8SHORTS, `reel_player_page_container`+`reel_recycler`,44nodes/truncated=false, свежие точки за8,229с, без закрытия интервала в серии.
- Код не менялся. Ordinary video/ложные срабатывания, переключение и погрешность ещё не проверены; [результаты](docs/spike-results-2026-10-17.md).

### Отрицательный контроль обычного видео

- После подтверждения пользователя8замеров дали0SHORTS/8UNKNOWN, без открытого Shorts interval и без роста числа закрытых интервалов;94nodes/no truncation, свежие точки за8,236с.
- `reel_player_page_container`/`reel_recycler` отсутствовали; `reel_time_bar` присутствовал и потому не годится как единственный positive marker. Код не менялся;8/8UNKNOWN не выдаётся за confident NOT_SHORTS или общую гарантию.
- Следующее — переключение5роликов Shorts, затем выход/lock/погрешность; [отчёт](docs/spike-results-2026-10-17.md).

### Перелистывание и предупреждения лимита

- Пользователь уточнил лимит Shorts0минут с закрываемым вручную предупреждением перед каждым роликом. Поэтому5переключений не являются изолированным swipe test.
- Own report:5UNKNOWN-периодов223–1799мс между положительными состояниями; последний удержанный ролик4/4SHORTS,43nodes/no truncation. Связь gaps с окнами пока гипотеза, не объявлена багом и не замаскирована склейкой.
- Код/лимит/настройки не менялись. Следом отдельный контроль удержанного открытым предупреждения.

### Удержанное окно лимита

- На подтверждённом открытом предупреждении8свежих замеров далиUNKNOWN/no interval,22nodes/no truncation/стабильный набор за8,326с. Positive player IDs отсутствовали, generic panel IDs и reel_time_bar присутствовали.
- Код/лимит не менялись. Связь с прежними gaps возможна, но не доказана для каждого; не склеиваем. Предложение не учитывать предупреждение как просмотр остаётся открытым вопросом требования.
- Следующий сценарий — пользователь закрывает окно, держит Shorts≈5с и блокирует телефон для отдельного контроля.

### Блокировка из Shorts

- После подтверждённой пользователем блокировки8свежих замеров за8,283с далиNOT_SHORTS/screen_off_or_locked; openInterval=false, closedIntervals15стабильно; power metadataDozing.
- Предыдущий interval6209мс закрыт именно по screen_off_or_locked; это не измерение погрешности относительно приблизительного ручного времени. Проверка выполнена с USB/питанием, не долгий standaloneDoze.
- Код/настройки экрана не менялись. Дальше — user unlock/возвратShorts, затемHome; [отчёт](docs/spike-results-2026-10-17.md).

### Возобновление после разблокировки

- Подтверждённый Shorts после unlock дал 8/8 SHORTS на свежих замерах за 7,277с, 46 узлов/no truncation, стабильные player IDs.
- Создан отдельный новый интервал, его начало не менялось в серии; прежний закрытый по блокировке интервал сохранился. Время блокировки не склеилось с новым просмотром.
- Код/настройки не менялись. Задержка после unlock не измерена; следующий тест — Android Home, а не главная YouTube.

### Домашний экран с маленьким окном

- Пользователь уточнил, что на Home остался видимый маленький Shorts.8замеров за8,122с далиNOT_SHORTS/youtube_not_foreground/no interval; код/окна/системные настройки не менялись.
- Зафиксировано ограничение single-active-root: плавающее видео не учитывается, поддержку PiP не заявляем. Снимок YouTube был историческим, launcher contents не читались.
- Добавлен OPEN-WINDOW (предложено считать видимый floating Shorts, подтверждения нет). Для чистого Home попросили пользователя закрыть окно и оставить экран разблокированным.

### Чистый Home и принятое исключение маленького окна

- Пользователь закрыл окно и явно решил не учитывать просмотр Shorts в таком маленьком окне. Добавлены SHORTS-003/DEC-006, OPEN-WINDOW о выборе правила закрыт; вместо него OPEN-WINDOW-MODES о техническом покрытии других режимов. Предыдущее предложение агента не было принятым требованием.
- На подтверждённом чистом Home8свежих замеров за7,043с далиNOT_SHORTS/youtube_not_foreground/no interval, closedIntervals16без роста; исторические YouTube IDs не использовались как текущее evidence.
- Завершён базовый ручной проход на одном телефоне/версии YouTube. README/план/итог отделяют эту проверку от общей точности, battery/reboot/совместимости. Код/системные настройки не менялись, очередной scope не начат.
- Пользователю сообщено, что держать телефон включённым больше не нужно; предложено вручную остановить диагностику, факт её остановки не проверялся.

### Chrona, правило предупреждения и проектный Git

- Пользователь утвердил исключение времени предупреждения лимита: SHORTS-004/DEC-007, прежний OPEN-OVERLAY о выборе правила закрыт. Прочие overlays/UNKNOWN остаются отдельным вопросом; runtime prototype не менялся.
- Выбрано имя **Chrona** (после краткого первоначального MyChrona); обновлены README/требования/Gradle project name. Диагностический APK/package не переименован и не переустановлен одной сменой имени.
- Создана рабочая ветка `dev`, актуальный origin по последнему уточнению **git@github.com:BebuliBy/Chrona.git**. Прежний MyChrona.git заменён до первого push; в него ничего не отправлялось. В AGENTS закреплены постоянные осмысленные commit/push после согласованных изменений с проверками и без секретов.
- Расширены ignore-правила для сборок/дампов/локальной диагностики. Добавлен **неутверждённый** план локальной Chrona и процесса выбора UI; полноценная локальная реализация не начата.
- После смены Gradle project name offline debug build успешен; unit-тесты отдельно повторно выполнены с `--rerun-tasks` (8/8). SDK XML-version warning остаётся, lint/новых телефонных тестов на этом Git-этапе не выполняли, диагностический APK на телефон не переустанавливали.
- Для переносимого первого Git-снимка добавлен `.gitattributes`: Linux wrapper LF, Windows `.bat` CRLF при checkout/нормализованный текст в index, JAR binary. Первоначальный whitespace-check отметил CRLF сгенерированного `gradlew.bat`; исправлена политика строк проекта, глобальный Git config и vendor-логика не менялись.

## 2026-10-18 — вариантБ и первое UI-превью

- Пользователь выбралБ: ручные привычки/отметки/цели/серии включены в локальный scope, зафиксированы HABIT-001/002 и DEC-010. Детальные бизнес-правила и окончательный дизайн ещё не приняты.
- Добавлен модуль `chrona` с Compose/Material3, DemoViewModel/StateFlow/reducer и центральной темой:4раздела, детали, пробные create/delete/goal/progress, история/empty/reset,2палитры и system/light/dark. Везде demo banner; реальная история/БД/collector не подключены.
- Старый `app` spike сохранён отдельно. Новый debug APK `by.bebuliby.chrona.debug`, label «Chrona — дизайн», без INTERNET/Accessibility/storage/mic permissions; релизный ключ/подпись не создавались.
- Build app/test APK и20unit tests успешны. APK установлен/запрос запускаStatusok, но телефонDozing;3UI instrumentation tests только скомпилированы, визуал/скриншоты не подтверждены. Ожидается добровольное открытие пользователем, lock не обходится.
- [Подробный UI-отчёт](docs/ui-checkpoint-2026-10-18.md). Полная локальная Chrona не объявлена готовой; следующий шаг — пользовательская визуальная проверка, затем реальные данные.
- Lint завершён с0errors/8warnings(7dependency updates,1v26 qualifier). Явно закрыты cloud/device extraction для UI-чекпоинта, добавлен monochrome icon и синхронизация системных bar icons с темой. Неудачное перемещение icon вернули в рабочийv26 path и проверили полным rerun; warning не скрывали. Финальная debug-сборка повторно установлена поверх своей identity без удаления; production-data migration этим не проверена.

## 2026-10-18 — новый UI-запрос, математика и разделение ядра

- Пользователь посмотрел первый UI/основа понравилась. Буквально записаны новая навигация/profile menu/friends/cards/numeric overgoal/emotions/animations/weekly-monthly-yearly history и cumulative stats, DEC-011. **Новый UI пока не реализован**, старые tests/APK не выданы за обновлённые.
- 6vs7эмоций, промежуточные границы/цвет/компенсация, сумма разных единиц и170vs140, ручная правка autoYouTube и нулевой лимит оставлены открытыми. Варианты в requirements явно НЕ утверждены, не подгоняют требования под код.
- По дополнительному запросу выделены UI и local Room/repository/tracking направления, предложен сырой data contract, DEC-012. Второй разработчик в этой сессии не запущен/никому не делегировано; БД/collector/сервер не созданы.
- Изменена только документация. Новых build/device checks не было; предыдущие20unit/build/lint и compiled UI-tests — исторический результат первого UI, не новая приёмка. Телефон/permissions/releasekeys/VPN/сервер не трогались.

## 2026-10-18 — согласованные правила и пауза

- Пользователь подтвердил7эмоций/все предложенные границы, colour score с равными весами/cap0..100%/без компенсации, нормированную накопительную кривую и исправление140вместо170. Правила формализованы в requirements/DEC-013, не выданы за реализацию.
- Shorts actual **строго read-only для пользователя**, только системой; добавлен SHORTS-005 и ограничение в AGENTS/контракте данных, manual Adjustment отвергнут.
- Нулевой лимит разрешён как бинарное соблюдение/нарушение без actual/0. Конкретный вклад этой ветки в общую curve/отдельное отображение не выбран автоматически.
- По просьбе пользователя работа на паузе: только фиксация документации/памяти и её Git-порядок. Код/APK/БД/зависимости не менялись, сборок/телефонных тестов/новых сессий не было. Оставшиеся вопросы не замаскированы как решённые.

## 2026-10-18 — новый запрос исполнения и пакет передачи

- Уточнено: соблюдённый нулевой лимит при известномactual0 даёт **100%выполнения/q=1**, не actual/0. Unknown не стал известным0; zero-curve коэффициент не выбран самовольно.
- Пользователь возобновил разработку и запросил временныйHigh(штатныйdefaultMediumпосле), UI/core и sysadmin сXray/proxy10808/приватнымпереносомclient config+key/новымботом/≈5сpolling/Docker/recovery/документацией. Намерение записано в требованиях/DEC-015/016, execution plan вarchitecture.
- В исходном чате не было task/subagent, новых исполнителей/High не запускали; shell-обход отказан по правилам среды. Прочитаны публичные профили(все5Medium) и датированная server-memory, но не private map/credentials/VPNkeys/token. Config/default не менялись.
- **Никаких live server действий, SSH/sudo/install/переноса ключей/BotFather/бота/Docker/deploy/новыхсессий не было.** Изменена только документация передачи; план не выдан за готовую инфраструктуру. Нужны правильныйlauncher и отдельные gates конкретного server плана/привилегий/BotFather.

### Штатная High-конфигурация вместо shell-обхода

- Добавлены7проектных профилей `.opencode/agents/chrona-*-high.md`: один primarycoordinator и6subagent helpers дляUI/core/backend/infra/browser/desktop. Модель та же, variantHigh; нормальные5глобальныхMedium/default не изменены. Нет новогоdefault_agent.
- У helpers запрещеныTask/Git; coordinatorallowlist толькоHighhelper IDs, privateдоступ ограничен. Схемаroot→helpers, не aliasselfcall/скрытаяделегация. Рабочиеcopy/ветки/исполнители ещё не созданы.
- In-memory packaged parser/catalogGET прошёл `CHRONA_HIGH_CATALOG_OK` без listener/chat/session/prompt/modelcall;7High и5Medium подтверждены как **настройки**, не как actualисполнение наHigh. Глобальный config не менялся; новых модельныхOpenCode-процессов не запускали.
- Инструкции загрузки требуют обычного restart/UI-выбора **Chrona folder + chrona-coordinator-high** и проверки нужных targets/High; файлы не добавляют их действующему API автоматически. Сервер/ключи/бот/runtimeDocker по-прежнему не изменены.
- До будущих server/data работ в `.gitignore` добавлены runtime/volumes и сырыеDB/dump форматы, чтобы реальные данные не попали в Git; таких данных в этом шаге не создавали/не читали.

### Review прав профилей перед отправкой

- У текущего Арчи штатный task уже доступен. Обычному desktop поручены независимый review и минимальные fixes; это не High-исполнение и не продуктовая разработка. API по-прежнему не предоставляет `chrona-*` targets/model-variant override.
- До исправлений сохранён backup 7 новых профилей. У helpers edit теперь deny по умолчанию с own worktree/run allow; root/shared/peer правки запрещены. Во всех 7 grep deny, private read/edit/external_directory deny. Coordinator получает необходимый подтверждённый контекст от обычного Арчи/владельца, не открывает private ради server-карты.
- Финальная isolated проверка установленного backend и его pure evaluator/wildcard: **409 assertions PASS, 7 High / 5 Medium**. Никаких listener/session/prompt/model calls или реальных private reads; глобальные Medium/default не изменены. Glob/list metadata, shell/MCP и непроверенный adversarial pipeline не объявлены OS sandbox.
- README/requirements/decisions/architecture/roadmap уточнены по реальному статусу. Runtime-код/зависимости/APK не менялись; новые build/phone/server tests не запускались. High-исполнители/Room/collector/Xray/bot/Docker всё ещё не запущены/не реализованы этим этапом.

## 2026-10-18 — отмена лишней схемы агентов

- По прямому требованию пользователя удалены все 7 добавленных chrona-*-high профилей. Из текущего README/architecture/roadmap убраны обязательные gates их наличия, нового coordinator и restart. DEC-017 сохранён только как отменённая история, принят DEC-018. Обычные профили/default/permissions и runtime-код не менялись.
- Исходный план субагентов и временного High сохранён: обычный root archi, независимые developer UI/core/backend, sysadmin infra; прямой developer не ждёт task. Добавлен EXEC-002, продуктовые требования/Shorts/математика/server без отката.
- Desktop проверил установленный tool pipeline/renderer и 31 pure assertion: sibling developer sessions поддержаны; task не принимает model/variant override, configured Medium дочерней роли не наследует родительский High. Это не выполненный High-вызов.
- Read-only metadata работающего Desktop исключили tools.task=false/session task deny у найденного проблемного coordinator; actual variant xhigh. Причина отсутствия доставленного task ещё не установлена. Переписки/auth не читались, настройки сессии не менялись; никакого нового model/session/HTTP launch или слепого restart. Продуктовые субагенты/сервер не запущены этим исправлением.
- Дополнительная точечная metadata-проверка нашла старые developer-ходы в том же root, который сейчас archi. Просьба искать отдельный developer sessionID отменена; факт смены роли не доказывает отправленную модели schema. В architecture уточнён следующий участок диагностики, без новых конфигов/сессий/моделей.

## 2026-10-18 — UI-v2, local/client/backend и реальный server checkpoint

- Реальные обычные developer-субагенты разделили UI/core/backend, sysadmin подготовил и развернул infra; root объединил контракт/код/проверки.4 variants временно переключались High→Medium по прямому поручению, без повторных High-тестов/новых ролей.
- UI: Today/History/Habits/Friends, профиль/photo/menu,12 палитр/3 стиля,7 Canvas эмоций/одноразовые animations, numeric manual overgoal, red/white Shorts read-only, week/month sticky table/smooth scroll, statistics/normalized+individual curves. Неопределённый yearly layout не выдуман.
- Room2/DataStore/collector: raw exact decimals, goals/entries/intervals/gaps/health/checkpoints/outbox, source guards, privacy consent/user-only Accessibility, no fixtures. Schema1/2 и migration1→2 сохранены, нет destructive fallback.
- Client HTTPS/PKCE/Keystore/refresh/logout, stable account/origin binding, frozen retries/atomic ACK/cursor/restore/conflicts, explicit upload consent, настоящие social calls/settings. После integration review пустой secret-free vault record не становится SECURE_STORAGE error. Native Android/Keystore runtime пока не проверен.
- Backend auth/sessions/SQL/idempotency/sync/ACL/authorized complete DTO+Unavailable fields, durable bot inbox/outbox/lease/retry/watchdog/SOCKS remoteDNS; explicit discovery setter и privacy default off, sharing editor с подтверждением/датами. Invites/default policies остаются неполными, не fake200.
- Реальный server: pinned official Xray/Postgres/JRE smoke без AVX2, private10808/no host publish, API healthy loopback и HTTPS `/chrona`, old Nextcloud mappings сохранены. TLS proxy/direct deny/reconnect, PG/JDBC/migrate/restart, consistent backup/isolated restore и legacy checks PASS; token отсутствует, worker off/auth503.
- Root Android main/test APK/build/lint PASS:104Chrona+8spike unit tests; backend80, infra30, SQLite3 PASS. Lint0errors17warnings;13instrumentation compiled, не ran. Первый APK build прерван tool timeout, последующие полные builds успешны. APK на телефон ещё не устанавливался.
- Подробные state/commands/limitations/BotFather/hidden token/origin/private tailnet/rollback — в новых docs/integration/local-core/android-client/UI/backend/social/server и infra README. Token/пароль/VPNsecret/private hostname/данные не добавлялись в Git.

## 2026-10-18 — verified bot activation после private token install

- Владелец успешно ввёл token скрытым installer; token в чат/source не передавался. Sysadmin проверил только file metadata, затем настоящий getMe/getWebhookInfo через SOCKS/TLS: verified bot, empty webhook/pending0.
- Один worker реально started/healthy, successful poll progression/SQL offset/lease наблюдались36s, затем API auth enabled/healthy. Existing DB/proxy/backend80 image/Nextcloud root/mappings сохранены. Сообщений/login вместо владельца не отправляли, E2E не объявлен пройденным.
- Усилены activation/rollback/bootstrap guards: fresh identity marker/queues/lease/no second worker, fail-closed webhook/pending; enabled-safe smoke не вызывает synthetic authbegin. Infra **33/33 PASS**; server Compose/native health и legacy checks PASS.
- Android/backend source и APK не менялись. Следующий шаг — подтверждённый unlocked phone, install-r, configured tailnet/baseURL и вход из Chrona по сгенерированному request. Bare `/start` без payload остаётся noop backend80; выдуманный UUID не используется.

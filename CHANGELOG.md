# История изменений

## 2026-10-19 — 0.4.1-goals опубликована

- Shorts limit теперь effective-from/samezone до следующего изменения, а не
  только один день. Append-only facts/старые целевые значения не переписываются;
  manual exact-day неизменён. Wire/server permitted-day projections согласованы.
- Version7 APK/manifest опубликован штатно, прежнийcode6 сохранён неизменно;
  отдельный API-only refresh без worker/DB/publisher/gateway changes.
- Root assemble1m13s/installDist14s PASS, безtests/lint. Owner устанавливает через
  in-app updater и подтвердил успешное обновление. В дальнейшем доставка так же;
  отдельного phone/DB inspection не было.

## 2026-10-19 — опубликована0.4.0-update

- Stable APK/manifest/immutable releases на отдельном public gateway consumer.
  Publisher readonly/nonroot, manifest atomicLAST, same signer/version/hash checks;
  existing private443/Nextcloud/rootAPI сохранены. API pending names deployed отдельно.
- Every-open update check/non-dismissable gate, independent background collector/
  sync, verified APK/systeminstaller; unified Telegram/guest welcome по reference.
- Sync immediate/5s foreground, one-time target10min background; names/username
  pending безUUID, calendar dates, notices3s, explicit friendperiod/font restoration.
- Root assembleDebug55s/backendinstallDist16s PASS, no unit/lint/instrumentation.
  Phone6 ещё не установлен, запрошен unlocked. [Факт](docs/release-0.4.0-2026-10-19.md).

## 2026-10-18 — подготовлена0.3.3-sync

- Сохранение session/last-known profile при обычной потере сети; ambiguous refresh
  сохраняется без unsafe replay и с понятным сообщением о необходимом повторном входе.
- Default VPN network observer/retry, targetloop10s/signals5s/Shorts slices5s,
  headless controller и обновление выбранного friend snapshot в его же ACL/range.
- Read-only server snapshot отличил отсутствие FRIENDданных от отсутствия grants;
  права/сервер не менялись. Один assembleDebug PASS, **ещё не установлено**, безtests/lint.
  [Итог](docs/vpn-two-account-result-2026-10-18.md).

## 2026-10-18 — установлен чистый UX/автосинхронизация0.3.2

- Убраны служебные подписи/gaps/серверный editor, читаемые часы/минуты/секунды и
  interval clocks, today markers, компактные «Настройки», заметный Shorts limit.
- Managed endpoint из сборки сохраняет текущую session/target. Автосинхронизация
  по аккаунту/default-on, WorkManager+process controller, без popup на каждый upload.
- Recorded Shorts counter оценивается сразу, partial не блокирует лицо. Жёлтые
  Canvas emotions/короткие анимации, без Telegram assets. Source guards сохранены.
- После последнего уточнения **не tests/lint**, только final assembleDebug PASS,
  install-r3→4 Success/own launch ok. [Полный факт/ограничения](docs/ux-installed-2026-10-18.md).

Здесь только факты внесённых изменений. Планы — в docs/roadmap.md, принятые решения — в docs/decisions.md. Разработка функций не считается выполненной по одному описанию.

## 2026-10-18 — первая синхронизация владельца

- Owner сам выполнил Sync/pending20+→0. Count-only READ ONLY server check подтвердил
  22 committed accepted operation receipts с revision, conflict/rejected0.
- Изменена только документация: без push/retry/SQL writes/phone действий от агентов.
  Restore/friend ACL/полнота истории не объявлены проверенными.

## 2026-10-18 — владелец подтвердил успешный вход

- После worker87 пользователь сообщил «Я уже вошёл, всё успешно». Login blocker
  закрыт по его подтверждению; повторного входа/SQL/ADB/vault checks не выполняли.
- Sync/friend ACL/restore не объявлены проверенными; upload consent не выдан самим
  фактом login. Обновлены README, интеграционный отчёт, roadmap и login recovery.

## 2026-10-18 — ответы бота и worker-only87 rollout

- Исправлено молчание на новых bare `/start`/`/help`: инструкция через durable
  outbox без выбора/привязки challenge/account/session. Valid fresh UUID сохраняет
  confirm/deny; ошибочные/expired/чужие links/callbacks дают одинаковый generic error.
  PKCE/TTL/Identity/SQL guards не ослаблены, processed noop не переигрывается.
- Backend87/87 и MAIN infra37/37 PASS. Worker87 развёрнут отдельно, API80 сохранён;
  SIGTERM/lease перед заменой, ровно один healthy poller/restart0/36s progression,
  offset/queues/DB/proxy/Nextcloud/Serve/private443/gateway сохранены. Added optional
  BOT_IMAGE, component-aware observer и pinned bootstrap script (не rerun после87).
- Два реальных native smoke:0PASS/2FAIL на UI assertions, оба DNSglobal/HTTPS401;
  второй readonly повтор подтвердил сохранённый target/disabledSave/enabledLogin.
  Test-only merged predicate/already-saved branch/substring assertion исправлены;
  последний test APK compiled, не device rerun во время owner login. Main не менялся.
- Старый owner challenge expired/unbound/sessions0; точный raw input noop неизвестен.
  Вход и ответ на новое сообщение ещё не приняты. Агенты сообщений/confirmation/
  exchange не делали. [Актуальный порядок](docs/bot-login-recovery-2026-10-18.md).

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

## 2026-10-18 — обновление и первый запуск на готовом телефоне

- После явного подтверждения owner USB/unlock desktop подтвердил одну authorized SM-A376E/Android16/API36, сверил APK metadata/signature и выполнил install-r:0.2.0-ui/code1→0.3.0-local/code2, Success. Uninstall/clear/downgrade/автовыдачи permissions не было.
- Собственная MainActivity cold launch Statusok, живой ownprocess/resumed/focus подтверждены. Tailscale установлен, но активное подключение/HTTPS/API пока не доказаны. UI/private DB/vault/чужие apps не читались; ни login, ни sync, ни сообщения не инициировались.
- Код/APK не менялся/не пересобирался. Следом owner Tailscale/явный HTTPS baseURL и настоящее подтверждение входа по generated Telegram link; данные/секреты/serial/privatehostname в Git не добавлялись.

## 2026-10-18 — standalone public gateway и клиент0.3.1

- Generic Funnel publication tool создан отдельно: source `/home/niki/public-gateway`, runtime `/opt/public-gateway`, registry/state `/etc/public-gateway`. CLI consumers register/validate/plan/apply/status/remove; application imports/hardcoded Chrona logic отсутствуют, новый GitHub repo не создавался.
- Только public8443/root первогоconsumer, private443/Nextcloud сохранён; native handlers/ETagCAS/allowlist/rootdelegation/backup/scopedremove. Generic14local/server иappguard34 PASS, external relayDNS/TLS/200/401/negativeNextcloud/443TLSdeny PASS. Один transientrepeatfailedнеустановленнойпричины, затемполныйPASSбезизменений; beta/SLAограниченияне скрыты. Apprepo храниттолько consumer/doc/guard,неgeneric source.
- UI/core/vault/transport baseURL mismatch исправлен общим strict guard, safe prefix/customport поддержаны, guards/TLS/PKCE не ослаблены. Prefix change — новый target/login scope.
- One-shot sync consent фиксирует exactoperationIDs/target/account/profile; будущие записи ждутновогосогласия, retryтолькоapprovedsubset, ACK/frozen/lastsuccessатомарны. Schema3/MIGRATION2_3 не очищаетraw/outbox/frozen, отключаетlegacyunboundedflag.125Androidunit/SQLite5 PASS,lint0err17warn.
- APKcode3/0.3.1-fix собран/install-r2→3/launchPASS. Одинnative smoke compiled(nofixtures/secretreads/login/sync); phonepreflight OWNER_UNLOCK_REQUIRED,0device tests/testAPKнеустановлен/network/configsaveнепроверены. Testпослеunlockдержиттолькосвоёforegroundwindowkeep-screen-on сfinallyrestore, безglobaltimeout/wakeup/unlock/VPNchanges. Реальныйphone/TG/syncE2Eещёнеpassed.

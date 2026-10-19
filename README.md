# Chrona

Android/Kotlin-трекер привычек: автоматический Shorts, ручные значения и цели, история, собственные API/SQL и Telegram-бот. **Выбран вариант Б, включая будущие согласованные серии дней.** UI-v2, локальное хранилище/collector, Android API-клиент и backend реализованы; серверный checkpoint развёрнут. Владелец подтвердил успешный Telegram-вход на телефоне; sync/friend/recovery-приёмка ещё не выполнена — это не готовый production-релиз.

## Статус на 2026-10-18

**Latest2026-10-19:0.4.2-session/code8 опубликована.** Исправлены source-пути
ложного logout при network callback/гонке profile persist, запоздалых ошибок старой
сессии и смешения friends/raw-sync ошибок. Успех убирает соответствующую transient
ошибку, notices5s вместо3s, реальные conflicts/auth failures не скрыты. Один
assembleDebug45s PASS, безtests/lint; штатная публикация immutable8/manifestLAST,
API/DB/worker/publisher image не менялись. **Установка8 и результат на телефоне
пока не подтверждены**, владелец открывает Chrona для in-app update. Настоящий
lost-refresh-response всё ещё может требовать повторного входа; token reuse не
разрешался. [Source](docs/session-notice-fix-2026-10-19.md), [публикация](docs/session-notice-release-2026-10-19.md).

**Latest2026-10-19:0.4.1-goals/code7 опубликована для in-app update.** Лимит Shorts
действует с выбранного дня ежедневно до следующего изменения; сегодняшний новый
лимит не применяется к вчерашней клетке. Применимое правило согласовано между
local/server views и wire; manual goals пока exact-day. API-only refresh выполнен,
прошлые raw/revisions не переписывались. Root assemble/installDist PASS, безtests/lint.
Владелец подтвердил **успешное обновление до7 внутри Chrona**. По его
прямому уточнению дальнейшая доставка — через updater, не USB/пересылка APK.
[Правило](docs/effective-shorts-limit-2026-10-19.md), [публикация](docs/effective-goal-release-2026-10-19.md).

**Релиз0.4.0-update/code6 опубликован (продолжение2026-10-19).** Постоянный путь
`/updates/chrona/latest.apk`, manifest `latest.json` и immutable releases доступны
через отдельный gateway consumer; полное значение ссылки передаётся владельцу,
не хардкодится в Git. В приложении проверка при каждом открытии и обязательный
update gate, единый Telegram/guest welcome, календари дат, notices3s, исправленный
friend week/month и имена заявок. Sync сразу при открытии/target5s, background
one-time chain target10min (Android best effort). API name-fix тоже deployed,
worker/DB/Nextcloud сохранены. App/backend сборки PASS, tests/lint не запускались.
**На телефон владельца code6 пока не установлен**, ожидается новое подтверждение
готовности; старые code4/5 не имеют updater и один раз обновляются по ссылке.
[Релиз и ограничения](docs/release-0.4.0-2026-10-19.md).

**Подготовлено обновление0.3.3-sync/code5:** сетевые ошибки/VPN-switch больше не
очищают вход как обычный logout, сохранена last-known identity; active loop≈10s,
signals5s/immutable Shorts slices5s, headless Application/Accessibility controller,
refresh выбранного friend snapshot. VPN не обходится, TLS/binding сохранены.
assembleDebug PASS; **на телефон эта версия ещё не установлена**. Нет tests/lint.
Если refresh мог совершиться, но ответ потерян, старый token не replay: нужен новый
вход, пока нет отдельного server recovery contract. [Причины/серверный снимок](docs/vpn-two-account-result-2026-10-18.md).

**Последняя установленная версия:0.3.2-ux/code4.** Служебные подписи/разрывы/редактор
сервера убраны, время ч/мин/с и local HH:mm:ss, лимит Shorts заметен, сегодня выделено,
«Настройки» одной строкой, лица жёлтые и анимированные. По последнему прямому
уточнению владельца **синхронизация автоматическая**, а Shorts реагирует на уже
накопленное время, не ждёт полного дня. Endpoint теперь поставляется сборкой,
не редактируется пользователем; existing target/account/session сохраняются.
Финальный assembleDebug PASS и install-r/launch PASS. После последнего уточнения
tests/lint не запускались по просьбе владельца; прошлые PASS не проверка новой
автоматики. Прежние one-shot-only/partial-blocking/code3 указания ниже исторические
и заменены DEC-021. [Актуальный итог](docs/ux-installed-2026-10-18.md).

**Первая синхронизация:** владелец сам нажал Sync, pending20+→0. Последующая
count-only SQL READ ONLY проверка: **22 accepted operation receipts**, все с revision,
conflict/rejected0;22 сохранённых request receipts. Серверный commit подтверждён,
не только клиентский счётчик. Это не доказательство доставки всей локальной истории,
restore или friend ACL; [агрегаты и ограничения](docs/integration-2026-10-18.md).

**Обновление владельца:** «Я уже вошёл, всё успешно». Реальный вход подтверждён
пользователем после worker87, повторять его не нужно. Новых SQL/ADB/vault проверок
агенты не выполняли; sync/друзья/восстановление и весь instrumentation suite этим
не приняты. Передача истории требует отдельного явного согласия.

- Распространение сначала **для себя и друзей**, без обязательного Google Play.
- `chrona`: LOCAL state, Room **schema3 / migrations1→2→3**, DataStore, raw outbox, source guards и collector. Schema3 хранит exact operation IDs одноразового sync consent; будущие записи ждут нового подтверждения. Fixtures только preview; diagnostic `app` отдельно.
- UI: **Сегодня → История → Привычки → Друзья**, профиль/своё фото/меню, 12 палитр/3 стиля, 7 эмоций/анимации, числовые manual actual выше goal, недельная таблица и плавный месяц, статистика/кривые. Shorts actual не редактируется.
- Android client: пользовательский HTTPS-origin, PKCE/verifier, Keystore session vault, refresh/logout, persistent frozen sync/restore/conflicts, явное upload consent, поиск/дружба и explicit-window sharing. Адресов/токенов в APK нет. Это написанный и unit-tested код, **не real-device E2E**.
- Backend Kotlin/Ktor/JDBC: auth/sync/SQL/ACL/views/bot worker. Discoverability по умолчанию off, переключается явно в app/bot; права друзей не расширяются автоматически. Known manual поля выдаются вместе с Unavailable для неизвестных.
- **Публичный API:** standalone Public Gateway опубликован через Funnel8443; **телефонам Tailscale не нужен**. Source `/home/niki/public-gateway`, server `/opt/public-gateway`; Chrona — лишь [первый consumer](infra/public-gateway-integration.md), generic source не часть app repo. Private443/Nextcloud и прежний `/chrona` сохранены. Это ограниченный пилот, не production/SLA guarantee.
- Xray10808 — отдельный private Docker outbound proxy, не ingress. API/bot/PG не пересоздавались при публикации. External global relay/SNI/CA/health200/protected401, Nextcloud404 на8443 и внешний443 TLSdeny проверены, privateNextcloud работает. Router/firewall/globalVPN вручную не менялись.
- Build **PASS**: **125 Android unit** (117Chrona+8unchangedcachedspike), SQLite **5/5**, app infra guard **37/37**, generic gateway **14/14** local/server, backend **87/87**. Lint **0errors/17warnings**. APK **0.3.1-fix/code3 установлен install-r**, own launch/resumed PASS. Native probe реально запускалась **дважды: 0PASS/2FAIL** на UI assertions; оба раза DNS global/нативный HTTPS `/v1/me`→401. Второй запуск подтвердил уже сохранённый target/disabled Save/enabled Login. Последний test-only fix скомпилирован, на телефоне не повторялся.
- Владелец установил token скрыто; verified worker/API auth активны. Развёрнут **только worker87** с ответами на новые bare `/start`/`/help`, API остался80; один healthy poller/restart0/poll progression36s, offset/queues сохранены. Старый challenge истёк; после исправления владелец подтвердил **успешный вход**. Сообщений/входа вместо owner не было; sync/friend flow и Telegram-avatar endpoint ещё не приняты. [Вход/rollout](docs/bot-login-recovery-2026-10-18.md), [публикация/фиксы](docs/public-gateway-checkpoint-2026-10-18.md).
- Годовой layout, recurrence/streak/история целей, complete/partial coverage и zero-limit curve/эмоция открыты; неизвестное не ноль. Из-за неполного Shorts общий score/цвет может быть Unavailable. [Интеграция и остатки](docs/integration-2026-10-18.md).
- Работа реально делегирована обычным developer UI/core/backend и sysadmin с разделёнными ownership/worktrees. Новых ролей нет; временные variants специалистов возвращены Medium, без повторных High-проверок по просьбе пользователя.

## Согласованное направление

1. Вход через Telegram-бота: одноразовый запрос входа, явное подтверждение и возврат в приложение, без пароля и обязательного телефона.
2. Взаимная дружба, запросы и просмотр в приложении и боте. Начать с ника и ссылки-приглашения; телефон необязателен и возможен только с согласия владельца.
3. Владелец выбирает доступные активности **для каждого друга**; предусмотрены настройки по умолчанию.
4. Друг видит те же данные и представления разрешённых активностей, что и владелец, включая **интервалы начала/окончания**, а не только дневную сумму. Доступ к настройкам, токенам и личным служебным данным этим не даётся.
5. Первая метрика — длительность открытого экрана Shorts, **без маленького плавающего окна и времени предупреждения о лимите**. Точность и покрытие режимов проверяются экспериментально; время всего YouTube не подменяет Shorts.
6. Локальный учёт без сети, синхронизация со своим сервером, восстановление данных, расширение новыми активностями и возможность переноса сервера.
7. Простой понятный интерфейс и настройка разрешений. Фоновая работа и восстановление наблюдения после загрузки — цель с учётом ограничений Android, не обещание «неубиваемого» процесса.

## Документация — маршрут работы

| Документ | Назначение |
|---|---|
| [AGENTS.md](AGENTS.md) | Правила работы агентов и изменения требований |
| [Требования](docs/requirements.md) | Согласованные требования, критерии и открытые вопросы |
| [Архитектура](docs/architecture.md) | Границы компонентов и ещё не выбранные технологии |
| [Решения](docs/decisions.md) | История принятых решений и их оснований |
| [План](docs/roadmap.md) | Этапы и фактическое состояние выполнения |
| [Проверка Shorts](docs/accessibility-spike.md) | План первого технического эксперимента |
| [Изменения](CHANGELOG.md) | Только фактически внесённые изменения |
| [Интеграция](docs/integration-2026-10-18.md) | Сборки/тесты, реальные server checks и оставшиеся действия |
| [Контракт](docs/data-api-contract.md) | Общая граница UI/core/API/ACL/sync |
| [UI-v2](docs/ui-v2-2026-10-18.md) / [local core](docs/local-core-2026-10-18.md) | Ownership, код и ограничения |
| [Android client](docs/android-client-2026-10-18.md) / [backend](docs/backend-2026-10-18.md) | Реализация auth/sync/social и проверки |
| [Server deployment](docs/server-deployment-2026-10-18.md) / [infra](infra/README.md) | Proxy, Docker, backup/restore, token и rollback |

**README и явно связанные согласованные требования — продуктовый источник истины.** Предложение, черновик или написанный код не заменяют согласование. При противоречии нужно остановить затронутую работу и уточнить решение, а не подгонять документы под реализацию.

## Git

- Каталог пока `/home/niki/android-tracker`; переименование приложения не перемещало файлы.
- Рабочая ветка: `dev`. Origin: `git@github.com:BebuliBy/Chrona.git` — последний явно указанный пользователем адрес. Прежний MyChrona.git заменён до первой отправки.
- После каждого завершённого согласованного изменения — проверки, документация, осмысленный commit и проверенный push. Полный порядок и границы разрешения — в [AGENTS.md](AGENTS.md).
- Сборки, local.properties, SDK/IDE caches, секреты и диагностика не входят в Git. Debug identity `by.bebuliby.chrona.debug` сохранена, версия `0.3.1-fix`/code3; release key не создавался. Generic gateway — отдельный non-Git source project; в app repo только consumer definition/docs/guard.

## Ближайшее действие

1. Hidden token-install и verified profile/worker/API auth activation **уже выполнены**; повторно token не вводить и не создавать второй poller.
2. Code3 установлен, native связь и сохранённая конфигурация подтверждены ограниченными проверками; общий JUnit не PASS. Пока владелец выполняет вход, **не запускать instrumentation/reinstall/restart/clear-data**. [Probe и два результата](docs/phone-public-smoke-2026-10-18.md).
3. Canonical base URL получить: `ssh -t server 'sudo -- cat /etc/chrona/client-origin'`. После external PASS это **public8443 root без `/chrona/`**; не дописывать прежний prefix, не требовать phone tailnet. Runtime URL не вшит в APK. Code3 исправляет поддержку safe base prefix и one-shot consent: [fixes](docs/client-fixes-2026-10-18.md).
4. Владелец **уже успешно вошёл**, не просить повторять login/сбрасывать сессию. Процедура нового входа сохранена в [отчёте](docs/bot-login-recovery-2026-10-18.md) только на будущий случай; bare `/start`/`/help` не выполняет login.
5. Проверить настоящий sync/friend ACL/revoke/восстановление и закрыть точечные правила streak/year/coverage/avatar. Продолжать обычными ролями; один root Git-интегратор, без новых обязательных агентов/restart.

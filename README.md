# Chrona

Android/Kotlin-трекер привычек: автоматический Shorts, ручные значения и цели, история, собственные API/SQL и Telegram-бот. **Выбран вариант Б, включая будущие согласованные серии дней.** UI-v2, локальное хранилище/collector, Android API-клиент и backend реализованы; серверный checkpoint развёрнут. Сквозная Telegram/phone-приёмка ещё не выполнена — это не готовый production-релиз.

## Статус на 2026-10-18

- Распространение сначала **для себя и друзей**, без обязательного Google Play.
- `chrona`: реальный LOCAL state, Room **schema2 / migration1→2**, DataStore, raw outbox, source guards и Accessibility collector. Fixtures остались только в явно обозначенных previews; старый diagnostic `app` сохранён отдельно.
- UI: **Сегодня → История → Привычки → Друзья**, профиль/своё фото/меню, 12 палитр/3 стиля, 7 эмоций/анимации, числовые manual actual выше goal, недельная таблица и плавный месяц, статистика/кривые. Shorts actual не редактируется.
- Android client: пользовательский HTTPS-origin, PKCE/verifier, Keystore session vault, refresh/logout, persistent frozen sync/restore/conflicts, явное upload consent, поиск/дружба и explicit-window sharing. Адресов/токенов в APK нет. Это написанный и unit-tested код, **не real-device E2E**.
- Backend Kotlin/Ktor/JDBC: auth/sync/SQL/ACL/views/bot worker. Discoverability по умолчанию off, переключается явно в app/bot; права друзей не расширяются автоматически. Known manual поля выдаются вместе с Unavailable для неизвестных.
- **Сервер:** reusable Xray10808 в private Docker-сети, PostgreSQL17.11, API healthy; private HTTPS path `/chrona` с сохранением Nextcloud root. Proxy TLS/direct-deny/reconnect, JDBC/migrations, DB backup/isolated restore и legacy checks выполнены. SOCKS не опубликован на host/в интернет.
- Root build **PASS**: main/test APK, **112 Android unit tests** (104 Chrona +8 spike), backend **80**, SQLite migration **3**. После bot activation infra **33/33 PASS**. Lint **0 errors /17 warnings**. 13 instrumentation tests скомпилированы, **не запускались**; новый APK пока не установлен/не проверен на телефоне.
- Владелец установил token скрыто; getMe/getWebhookInfo через proxy подтвердили бота, webhook пуст. **Один worker и API healthy, Telegram auth включён**, live poll progression/SQL offset/lease наблюдались. Сообщений/входа от агента не было. Реальные owner login/sync/friend flow, Telegram-avatar endpoint и доступ телефона к tailnet ещё не приняты.
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
- Сборки, local.properties, SDK/IDE caches, секреты и локальная диагностика не входят в Git. Debug identity `by.bebuliby.chrona.debug` сохранена, версия `0.3.0-local`/code2; release key не создавался. `app` diagnostic identity не менялась.

## Ближайшее действие

1. Hidden token-install и verified profile/worker/API auth activation **уже выполнены**; повторно token не вводить и не создавать второй poller.
2. Включить/разблокировать телефон по просьбе координатора и подтвердить готовность. Установить `chrona/build/outputs/apk/debug/chrona-debug.apk` поверх прежнего пакета, без удаления данных; native/UI/E2E пока не выполнены.
3. HTTPS base URL получить в своём терминале: `ssh -t server 'sudo -- cat /etc/chrona/client-origin'`. Ввести полное значение с `/chrona/` в настройки; телефону нужен tailnet-доступ. Это private endpoint, не публичный API.
4. Начать вход **из Chrona**, открыть сформированную Telegram-ссылку, подтвердить только собственный запрос и вернуться. Bare `/start` без payload в backend80 — noop, не признак успешного login. Не придумывать requestId и не подтверждать вместо владельца.
5. Проверить настоящий sync/friend ACL/revoke/восстановление и закрыть точечные правила streak/year/coverage/avatar. Продолжать обычными ролями; один root Git-интегратор, без новых обязательных агентов/restart.

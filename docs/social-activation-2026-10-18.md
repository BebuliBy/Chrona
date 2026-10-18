# Explicit social activation — 2026-10-18

ID **chrona-social-20261018**, root archi → integration developer. Работа выполнена
**в `/home/niki/android-tracker`**, после объединения UI/client/backend. Узкий
cross-component scope и additions ниже **явно согласованы root/user**; это не
самовольный выбор default sharing/discoverability. Git/index не трогались.

## Минимальный пользовательский путь теперь есть

1. Настроить свой HTTPS origin и подтвердить Telegram login. В настройках linked
   профиля явно включить **«Разрешить находить меня по имени/нику»**. Начальное
   значение server accounts и `ConnectionUiState.discoverable` — **false**.
2. Друг ищет opt-in профиль по имени или актуальному @username в Chrona, отправляет
   запрос. Во вкладке Друзья incoming содержит реальные «Принять/Отклонить» с
   requestId; outgoing не выдаётся за accepted. Эта цепочка уже присутствовала,
   проверена по source/client parser, не подменена demo/ручным SQL.
3. Владелец у accepted друга нажимает **«Поделиться моими привычками…»**. Выбирает
   **свои** активности, вводит **обе ISO даты** (from inclusive/to exclusive) и IANA
   zone. Ничего не отмечено автоматически; даты не заполнены all-history.
4. Форма честно говорит **«новый набор, не текущий grant»**. Checkbox подтверждает
   именно замену набора/периода. Только отдельная кнопка отправляет SaveSharing;
   пустой набор — отзыв доступа. Смена draft сбрасывает consent; смена profile/
   account/server/friend state делает confirm недоступным.
5. Client читает **owner** grant revision и отправляет absolute PUT с frozen UUID
   Idempotency-Key/expectedRevision. Concurrent409 сохраняется как конфликт, не
   обходит revision новым key/force. Друг получает read-only authorized view.

**Sharing не является согласием на raw upload.** Ручные привычки/факты должны быть
доставлены серверу отдельным явным sync flow, прежде чем grant сможет ссылаться на
их серверные IDs. Несинхронизированная habit даст честный reject/NOT_FOUND, не
автоматический upload или grant на все данные. Shorts mapping выполняется owner
pull существующего клиента; server/local identity не переименовывались.

## API / common use case

- GET `/v1/me` дополнен **собственным** boolean `discoverable`.
- PUT `/v1/me/discoverability`: `{enabled:boolean}`, Bearer session + UUID
  Idempotency-Key; strict body, owner из session, SQL transaction + request dedup.
  Никакого owner/accountId в body или произвольного target account.
- Существующая accounts.discoverable SQL column; **migration/checksum/version не
  менялись**. PublicUser/search/snapshot не содержат flag/private Telegram ID.
- `/discover on|off` у bot использует тот же setter, private linked sender и
  durable update dedup; unknown/unlinked/group command не включает discovery.
- Confirmed login обновляет sanitized displayName с `(@username)`, если Telegram
  sender предоставил valid текущий username. Pending/denied не меняют attribute;
  имя не identity, account key остаётся stable Telegram ID. Consent при повторном
  login не сбрасывается/не включается. Search по-прежнему только по opt-in accounts,
  rate limit сохранён; phone/полный Telegram directory не подключались.
- Off выключает поиск, **не** удаляет существующую friendship/explicit grants.
  Для отзыва статистики пользователь сохраняет пустой sharing set.

## Client / UI безопасность

- Root-approved additions: `ConnectionUiState.discoverable:Boolean=false`,
  `ChronaAction.ConfigureDiscoverability(enabled)`. Parser missing old flag=false,
  non-boolean flag invalid. VM получает реальное значение из `/me`, не dispatch.
- Setter использует существующие authorized transport + durable social outbox;
  после ACK `/me` обновляется, включая retry. Token/vault/refresh/PKCE не менялись.
- Finite explicit TimeWindow обязателен в данном Android userflow. Нет
  unresolved/null-end → all-time fallback. Empty revoke не требует Shorts mapping.
- Предыдущий frozen request для той же настройки/friend блокирует новый submit
  с SOCIAL_PENDING, чтобы поздний retry старого on/grant не переоткрыл права после
  более нового выбора. Hard conflict сохраняется; conflict resolution UI не
  изобретён. Ни неизвестная доставка, ни notice не выдаются за success.
- Текущий grant не загружается в форму как checkbox values; GET используется для
  optimistic revision. Backend подтверждение даёт SHARING_SAVED, UI dispatch — нет.
  Нет auto-dispatch effects, auto-all-habits, default grants, phone или uploads.

## Фактические проверки (не live acceptance)

Все команды из **root**:

```sh
JAVA_HOME=/home/niki/.local/opt/android-studio/jbr ./gradlew -p backend --no-daemon test installDist
env JAVA_HOME=/home/niki/.local/opt/android-studio/jbr ANDROID_HOME=/home/niki/Android/Sdk bash chrona/src/test/run-core-check.sh
bash /tmp/opencode/chrona-social-ui-check.sh
```

- **Backend80/80 PASS**, 0failed/0skipped: все74 прежних +6 SocialActivation tests.
  H2 PostgreSQL mode/fake Telegram; не реальный server/Telegram smoke новой версии.
- **Core55/55 PASS**: прежние51 +4 own flag parsing, authorized boolean/key,
  own grant revision/409 no-force, finite window/revoke/Shorts mapping. Cached
  Kotlin2.2.10/JVM17 compiler компилировал domain/data/client/tracking/VM/generated
  Room; это не Gradle APK/Android Keystore/runtime acceptance.
- **UI49/49 PASS**: прежние43 +6 demo/private default, dates/IANA/DST/end-exclusive,
  explicit consent, current accepted target, own subset/empty revoke/no expansion.
  UI/preview/contract и13 instrumentation bodies compiled offline; instrumentation
  **не запускались**. MainActivity/resource link/Gradle lint этим helper не проверены.
  JBR25 Jansi/native-access/Unsafe warnings остаются.

Latest distribution:
**`/home/niki/android-tracker/backend/build/install/chrona-backend/`** (`bin/`, `lib/`).
Reports backend: `backend/build/reports/tests/test/index.html`, `test-results/test`.
Временный UI helper/JAR — `/tmp/opencode/chrona-social-ui-check.{sh,jar}`, не project
source и не новый model/agent process. Android Gradle **не запускался**.

## Остатки / передача root

- Root выполняет **один полный Android build/unit/APKs/lint** после source changes;
  existing staged index не обновлялся child: root должен включить этот diff сам.
- Root обновляет общие README/architecture/roadmap/CHANGELOG. Checkpoint docs реально
  обновлены: backend, android-client, ui-v2 и этот short social report.
- Sysadmin получает **root** installDist, не старый worktree. Env/private bind/
  auth-disabled параметры прежние, SQL migration новая не нужна. Новую runtime
  версию/PUT smoke проверяет sysadmin отдельно.
- BotFather/token пока нет, server Telegram-auth disabled. Никакого live login/
  real phone/social E2E/сервера не проверялось этим child. Всё ещё нужны owner bot,
  публичный HTTPS origin и отдельно разрешённая runtime/phone проверка.
- Invites/default inheritance/retention/uploads/new signup/recovery, unresolved
  coverage/day/zero-curve policies не расширялись. Это рабочий явный userflow в
  source с local protocol tests, **не** утверждение всего продукта production-ready.

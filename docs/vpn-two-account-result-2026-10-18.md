# VPN, вход и отсутствие истории друга — 2026-10-18

User сообщил повторный запрос входа в Chrona друга при full VPN, пропажу связи и
невидимую историю друга при видимости обратного направления. Nick selectors
использованы только in-process, в отчёте OWNER/FRIEND без реальных идентификаторов.

## Сервер: факты, не история последних запросов

Первый read-only SSH snapshot не вернулся за30s, это не пустой результат/outage.
После уточнения peer получен один bounded READ ONLY snapshot (statement_timeout3s):
- OWNER:2sessions/1revoked/1validaccess/1validrefresh, cumulativeaccepted101,
  rejected/conflict0, сохранённые intervals/segments существуют.
- FRIEND:2sessions/0revoked/0validaccess/2validrefresh, cumulativeaccepted0,
  rejected/conflict0, сохранённых Shorts intervals/segments нет.
- Friendship accepted, directed grants **в обе стороны**, включают нужные Shorts,
  valid windows покрывают сегодняшний день MSK. UI-selected range не сохраняется,
  поэтому про иной выбранный день вывод не делается.
- Request/access/sync arrival timestamps и revocation reason схема не сохраняет.
  Нельзя назвать точное время последнего прихода, причину logout или объявить VPN
  доказанной причиной. Sync click при пустом/неqueued batch не доказательство push.

Непосредственный факт пустой истории — нет сохранённых данных FRIEND при рабочем
двустороннем sharing. Права не расширялись, реальные значения/интервальные времена/
tokens/идентификаторы/логи/чужие приложения не читались.

## Найдено и исправлено в клиенте

Source подтвердил rotate catch-all с удалением session при network exception,
отсутствие last-known profile при offline reopen, local-only открытый checkpoint,
одноразовый GET уже выбранной истории друга. Это source-дефекты/пути, не полная
трасса конкретного события. Подробности: [client](vpn-sync-client-2026-10-18.md).

- Обычный DNS/connect/TLS/VPN-switch не считается авторизационным401 и не стирает
  вход. Cache identity внутри существующего encrypted vault/account binding.
- Когда refresh body точно не отправлен, retained unspent refresh допускает retry.
  Если мог быть отправлен, encrypted rotating marker сохраняется, старый одноразовый
  secret не повторяется. SESSION_REFRESH_UNCERTAIN честно требует нового входа;
  прозрачный lost-response recovery — отдельный backend security contract.
- Default-network callback закрывает старые sockets и будит retry, **не обходит VPN**:
  нет physical binding/customDNS/host fallback/TLS отключения. Недоступный через
  конкретный VPN endpoint не может быть гарантированно доступным этим patch.
- Loop10s, signals5s, confirmed immutable Shorts slices5s; mutex/frozen requests/
  source guards/ACK сохранены. Уже выбранный friend refetch в том же owner/range,
  каждый GET проходит server ACL;403/404/401 снимают snapshot.
- Headless Application/существующая разрешённая Accessibility service поддерживают
  controller без Activity. WorkManager periodic15min — fallback.5–10s+IO/network
  latency — active-process target, не SLA после Doze/OEM kill/force-stop.
- Новый foreground service/уведомление/настройки питания **не добавлены**. Если
  потребуется более постоянная работа, это отдельный согласуемый режим с видимым
  notification/Stop и Android ограничениями, а не скрытый обход.

## Сборка и доставка

Единственный root `:chrona:assembleDebug` **BUILD SUCCESSFUL50s**. Version5/0.3.3-sync,
тот же package/signature, managed origin, Room3 без purge/migration. Tests/lint не
запускались по действующему пожеланию владельца. Backend/server не менялись.
APK SHA256 `d0be07371e094f92ee4cf7c97d0318535e6f1c13ef8fe5a45b1ee24f1a7c317b`.
APK: `chrona/build/outputs/apk/debug/chrona-debug.apk`.

**Установка ещё не выполнена:** owner запрошен новый unlocked confirmation. Другу
надо передать именно этот новый APK/обновить поверх и войти своим Telegram, если
старый клиент уже утратил локальную сессию. Не uninstall/clear. После обновления
нужна фактическая доставка данных; нули текущего server snapshot не переобъявлять
устранёнными одним compile. Friend backend old partial policy/цели отдельно.

# UI: обязательное обновление, страница входа, календарь и друзья

2026-10-18. ID `chrona-update-welcome-ui-20261018`, child developer → archi.
Работа напрямую в MAIN `/home/niki/android-tracker`, исходный code5/0e3f848 указан
root; Git не использовался. Основание — новый прямой user scope и фиксированный
контракт, который параллельно реализует core. Это source implementation, **не**
build/runtime acceptance.

## Написано

### Update gate

- `ChronaApp` сначала проверяет `state.update.required`, затем showAccountEntry,
  только затем создаёт обычный UI. Под gate нет navigation/menu/settings/guest
  controls или способов «пропустить/позже/отмена». Back не переводит в обычный app;
  системный Home/minimize не блокируется, kiosk/window/system restrictions нет.
- Полноэкранное «Доступно обновление», реальная versionName если известна,
  download progress (determinate/indeterminate), checking и InstallUpdate.
  При installerPermissionRequired кнопка «Разрешить установку», при ошибке — retry.
  Отдельная CheckForUpdates позволяет повторить проверку, не отменяя required gate.
- Ошибка required-update не является исчезающим notice: остаётся с повтором.
  Технический message/URL не выводится; пользователь видит короткую ошибку/инструкцию.
  Unknown network check сам по себе не создаёт required=true и не выдаётся за
  «уже актуально» — это решение core. При message вне gate есть короткое сообщение
  о неудавшейся проверке, автоматически скрываемое, без изменения underlying state.
- UI не останавливает tracker/data/network/controllers. Проверку manifest,
  доверие версии/APK, permission, download, installer и снятие gate выполняет core.

### Страница входа

- Прочитан только явно разрешённый reference image:
  `/home/niki/Downloads/Изображения/photo_2026-10-18_23-37-01.jpg`.
  Другие Downloads/личные изображения не читались. Reference не добавлен в assets.
- Светло-голубой hero≈36%экрана с дугообразным нижним краем, собственные Canvas-часы
  вместо чаши; тёмный низ и две широкие pill-кнопки. Telegram paper-plane также
  рисуется Canvas, не скачан/не заимствован из официальных assets.
- «Войти с помощью Telegram» → BeginTelegramLogin, pending → RefreshLoginStatus;
  «Продолжить без учётной записи» → ContinueWithoutAccount. Нет Google/Email login,
  password/token fields, normal nav/menu или UI logout/reset существующего аккаунта.
- Настройки logged-out теперь открывают OpenAccountEntry вместо прямого Begin;
  disconnected friends тоже открывают эту страницу. First-time/required-auth route
  определяет core через showAccountEntry. Валидный account не сбрасывается UI.
- Геометрия в dp/относительных размерах, min-height pills, 1–2строки labels и scroll
  для короткого окна/увеличенного шрифта. Реальное rendering/largefont не проверялось.

### Notices / даты / друзья

- Верхние UiNotice автоматически отправляют DismissNotice через3секунды через
  LaunchedEffect(notice.id), без рестарта от каждого recomposition. Timer находится
  на уровне app route: переход normal↔entry не начинает тот же notice заново.
  Ручной Close удалён; операции/error state/consent/sharing dialog не очищаются.
  Update check message без собственного notice ID имеет отдельный3s UI visibility;
  required-update message остаётся persistent.
- DaySelector (включая details) и обе даты sharing используют Material3
  DatePickerDialog, calendar display по умолчанию, без обязательного ISO typing.
  Picker millis → Instant → **UTC LocalDate**, обратно также UTC midnight; zoneId
  исходного DayKey сохраняется. Sharing хранит прежний ISO internally, from-inclusive/
  to-exclusive/обязательные обе даты/explicit consent не изменены. Период не расширяется
  автоматически, initial calendar month не является автоматически выбранной датой.
- Все UI OpenFriend передают explicit period. Переключатель истории друга по-прежнему
  читает `selected.history.period`, не подменяется отдельным local chip state.
  Week/month day count/font layout остаются зависимыми от фактического HistoryUiState.
  Core обязан сохранить period в auto-refresh.
- Имя/handle отображаются из существующего displayName. Пустой displayName либо
  ровно accountId/requestId заменяется «Пользователь». Sharing friend fallback тоже
  больше не выводит ID. Новых FriendSummary полей/DTO здесь нет.

## Файлы

Относительно проекта:

- `chrona/src/main/java/by/bebuliby/chrona/ui/AccountEntryScreen.kt` **новый**
- `chrona/src/main/java/by/bebuliby/chrona/ui/MandatoryUpdateScreen.kt` **новый**
- `chrona/src/main/java/by/bebuliby/chrona/ui/DateSelection.kt` **новый**
- `chrona/src/main/java/by/bebuliby/chrona/ui/ChronaApp.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/ConnectionSettings.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/HabitComponents.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/SharingEditor.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/SettingsAndFriends.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/UiFormatting.kt`
- этот собственный отчёт.

## Контракт / остатки

UI ссылается на предоставленный root контракт, сам domain не создаёт/не копирует:

- `ChronaUiState.update: UpdateUiState`, `showAccountEntry:Boolean`;
- UpdateUiState fields checking/required/versionName/downloading/progressPercent/
  installerPermissionRequired/message;
- actions CheckForUpdates, InstallUpdate, OpenAccountEntry, ContinueWithoutAccount;
- OpenFriend(accountId, range, period: HistoryPeriod? = null).

Core должен выставлять message для check-failure (при normal success — null),
инициировать проверку/first-entry/need-auth и корректно обрабатывать guest без
неявного logout/reset валидного account. При required=true UI не даёт обхода,
но trust/download/installer authorization **не** являются обязанностью renderer.
Неподключённый backend/manifest/installer не имитируется успешным UI.

Ч/мин/с, HH:mm:ss, yellow faces, auto-sync switch, actual read-only, goal editor,
account/ACL/sharing guards, today markers и скрытый server editor сохранены.
Resources/manifest/domain/data/presentation/MainActivity/Gradle/backend/infra/shared
docs не редактировались. Новых libs/fonts/assets downloads/license changes нет.

**По прямому требованию ничего не запускалось:** tests/lint/build/Git/index/ADB/
phone/server/другие агенты/model checks. Existing tests не исправлялись ради green.
Compile и runtime не объявляются PASS. Root выполняет один build после core/UI
стыковки и отдельно владеет shared documentation/status.

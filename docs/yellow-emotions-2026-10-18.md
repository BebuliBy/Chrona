# Жёлтые эмоции и автоматическая синхронизация — UI

2026-10-18, `chrona-yellow-emotions-auto-ui-20261018`, child developer → archi.
Работа напрямую в MAIN `/home/niki/android-tracker` по явному поручению root,
только UI. Новый прямой выбор пользователя — автоматическая синхронизация вместо
предыдущего подтверждения каждого снимка; накопленный Shorts отображается без
пояснения про неполный день. Sharing consent этим решением **не отменён**.

## Написано

- `ui/ConnectionSettings.kt`: вместо per-upload dialog/checkbox — переключатель
  «Автосинхронизация» из **реального** `state.sync.automaticEnabled`, action
  `SetAutomaticSync(enabled)`. Сохранены account/configured/pending/busy guards,
  компактный last success/error. «Синхронизировать сейчас» вызывает RequestSync
  напрямую, без popup; нажимать её для штатной автоматической работы не требуется.
  Host/адрес не показывается, login/logout не менялись. Старое внутреннее имя
  ManualSyncSettings сохранено, чтобы не переписывать wiring остальных UI-файлов.
- `ui/EmotionFace.kt`: семь собственных Canvas-выражений на круглом жёлто-золотом
  лице с тёмными глазами/ртом, голубыми слезами и светлыми золотыми звёздами.
  Цвета не зависят от палитры приложения и q. Это original emoji-style art,
  **не** официальные Telegram assets и не обещание пиксельной копии Telegram.
- Лицо рисуется всегда: нет серой/пустой заглушки. При unavailable/null emotion
  нейтральное жёлтое лицо без придуманного процента/ступени, включая unresolved
  goal/zero-limit emotion. Математика эмоций остаётся core-owned.
- Короткое появление, мягкое покачивание и одно моргание используют lifecycle
  появления и прежний commit reveal, не collector tick/изменение каждого числа.
  Нет infinite transition; системное отключение анимаций даёт статичное лицо.
- `ui/UiFormatting.kt`: Partial collector duration_ms выводится как накопленное
  время без «≥»; Unknown ручной привычки по-прежнему «Нет данных», не0.
- `ui/HabitComponents.kt`: у Shorts убрана подпись «Недостаточно данных» для
  partial counter. Actual по-прежнему не редактируется, canEditGoal и minutes→ms
  не обходятся. Ч/мин/с, локальное HH:mm:ss, небольшой settings title/today markers,
  скрытые server editor/gaps и sharing ACL/consent не менялись.

Полные source paths относительно проекта:

1. `chrona/src/main/java/by/bebuliby/chrona/ui/ConnectionSettings.kt`
2. `chrona/src/main/java/by/bebuliby/chrona/ui/EmotionFace.kt`
3. `chrona/src/main/java/by/bebuliby/chrona/ui/UiFormatting.kt`
4. `chrona/src/main/java/by/bebuliby/chrona/ui/HabitComponents.kt`

## Интеграция / ограничения

- Core sibling добавляет обещанные `state.sync.automaticEnabled` (default true)
  и `ChronaAction.SetAutomaticSync`, persistence/migration/default-on после собственного
  входа/обновления и сам automatic scheduler. UI только отображает state/dispatch,
  не создаёт таймер/сетевой клиент/скрытые side effects.
- Core предоставляет emotion/score по накопленному времени и итог дня. UI не
  рассчитывает эмоцию или проценты из raw и не переписывает actual/storage.
- Прежние описания обязательного one-shot consent в shared docs теперь требуют
  root-обновления согласно новому явному выбору пользователя. UI child их не менял.
- **Никаких tests/lint/build/source-analysis loops/model checks/сервера/телефона/
  ADB/Git не запускалось** по прямому требованию. Тесты не правились ради green.
  Compile/rendering/runtime/автосинхронизация не объявляются PASS; root выполняет
  один согласованный assembleDebug после стыковки core/UI.
- Новых assets, downloads, dependencies, license changes нет. Domain/data/
  presentation/Gradle/backend/infra/sharing/session/profile не изменялись.

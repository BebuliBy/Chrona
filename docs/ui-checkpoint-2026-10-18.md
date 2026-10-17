# Первый UI-чекпоинт Chrona — вариантБ

Дата:2026-10-18. Пользователь выбралБ (автоматический Shorts плюс ручные привычки/отметки/цели/серии). Этот этап следует описанному процессу **сначала визуальная проверка, потом реальные данные**. Не полноценный готовый локальный релиз.

## Создано

- Новый product-facing module `chrona`, старый `app`/Accessibility spike сохранён без переноса и изменения его данных/разрешений.
- Kotlin/Compose/Material3, VM+StateFlow, чистые DemoState/DemoReducer, централизованная тема, debug package `by.bebuliby.chrona.debug`, label **«Chrona — дизайн»**.
- Четыре раздела: Сегодня, Привычки, История, Настройки; детали Shorts/привычки; добавление/удаление пробной привычки, счётчик/отметка цели, фильтр истории/выбор примерной даты, empty state/reset.
- Две палитры для сравнения — Океан/Лаванда; system/light/dark. Это draft, не окончательно выбранный дизайн.
- На всех экранах постоянный banner **«Дизайн-превью · демоданные»**. Время Shorts/интервалы/серии условны. Изменения/настройки только в памяти VM и сбрасываются с новым процессом; fake fixtures не записываются в настоящий учёт.
- Дневная цель и число серии — **демопримеры**, не принятый production-алгоритм. Периодичность/пропуски/ретроактивные отметки ещё согласуются. Напоминания не включены автоматически.
- Никаких collector/network/registration/bot/друзей/экспорта/storage в этой сборке. Экран честно показывает «Отслеживание ещё не подключено»; прежняя диагностика Shorts — отдельный APK.

## Проверки

| Проверка | Фактическое состояние |
|---|---|
| dev/исходный repo | Чистый до правок, HEAD=origin/dev=e62e245; чужих правок не было |
| Dependency provenance | Google Maven BOM2025.08.01 прочитан: UI/runtime/foundation1.9.0,Material3 1.3.2. В сборке Compose compiler2.2.10/AGP9.1.1/JBR25,Activity1.11.0,Lifecycle2.9.2; Gradle успешно разрешил зависимости из штатных repos |
| Unit | **20/20**: старые8spike + новые12DemoReducer,0failures/errors; это не UI/runtime/реальная история |
| Lint | `:chrona:lintDebug` успешен, **0errors/8warnings**:7уведомлений о более новых версиях зависимостей и1о redundant v26 folder. Не подавлены и не выданы за zero-warning |
| Build | `:chrona:assembleDebug` и `:chrona:assembleDebugAndroidTest` успешны;3instrumentation tests компилируются, но **ещё не запускались** |
| APK permissions | Нет INTERNET/Accessibility/storage/microphone. Только собственная signature permission `...DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` из AndroidX |
| Install | Ровно один авторизованный физический телефон; `adb -d install -r ...chrona-debug.apk` →Success |
| Launch | Собственный am start →Statusok; телефон в этот момент**Dozing**, поэтому видимый интерфейс/Compose rendering пока не подтверждены |
| UI instrumentation/screenshots | **Ожидается пользовательское «Открыл Chrona» на разблокированном экране**. Тесты не обходят lock: перед каждым проверяют PowerManager/Keyguard и падают с пояснением при закрытом экране. Снимки только Compose-root своего UI, не всего телефона |

Команда первой сборки:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" ./gradlew --no-daemon --console=plain :app:testDebugUnitTest :chrona:testDebugUnitTest :chrona:assembleDebug :chrona:assembleDebugAndroidTest
```

Установлено:

```bash
"$HOME/Android/Sdk/platform-tools/adb" -d install -r chrona/build/outputs/apk/debug/chrona-debug.apk
"$HOME/Android/Sdk/platform-tools/adb" -d shell am start -W -n by.bebuliby.chrona.debug/by.bebuliby.chrona.MainActivity
```

Для следующей проверки после явного user unlock/confirmation:

```bash
env JAVA_HOME="$HOME/.local/opt/android-studio/jbr" ANDROID_HOME="$HOME/Android/Sdk" ./gradlew --no-daemon --console=plain :chrona:connectedDebugAndroidTest
```

`-d` использовать только с одним авторизованным физическим телефоном; serial не сохранять в публичные docs. Никаких `settings put secure`, wake/unlock keyevents и авто-включения Accessibility. Новому UI service вообще не нужен.

## Предупреждения и ограничения

- Сохранился SDK XMLv4 parser warning; build успешен. Google path native library не stripped, packaged as-is — warning, не повод ставить NDK/драйверы/OS packages.
- На lint-итерации добавлены явные data-extraction exclusions для cloud/device transfer и monochrome icon, приведены к теме системные status/navigation bar icons. Попытка перенести icon в базовый mipmap-anydpi по lint-рекомендации привела к `mipmap/ic_launcher not found`; восстановлен исходный рабочий v26 path, затем полный rerun build/unit/lint прошёл. Точная причина resource-link сбоя при переносе не установлена, warning о redundant v26 оставлен честно; глобальные tools/SDK не менялись.
- Более новый BOM2026.09.00 был просмотрен, но для минимального совместимого UI выбрана более консервативная связка; latest не является требованием.
- Нет real DB/DataStore/миграций/upgrade tests, настоящего streak engine/отслеживания/фоновой надёжности; real release identity/minSdk/signing до настоящих данных ещё принять. Release key не создавался.
- `@Preview` подготовлены/скомпилированы, но Android Studio Preview не открывали. Большой шрифт/повороты/разные устройства и обе темы визуально пока не проверены. Не утверждать «UI корректен» только по build.
- Дизайн-сборку можно обновлять поверх самой себя с той же debug identity/key, но не путать это с готовой migration-стратегией production. Existing diagnostic APK не переустановлен.

## Что сделать пользователю

Подключить USB, разблокировать, открыть **«Chrona — дизайн»**, написать **«Открыл Chrona»**. Не включать Accessibility. После собственных тестов посмотреть tabs/форму/тему/пустой экран и выбрать/исправить визуал. Утверждение дизайна не означает автоматическую готовность продукта; после него следующий согласованный этап — реальные данные/правила/хранилище.

### Последующий ручной отзыв

Пользователь уже посмотрел UI и написал, что основа дизайна нравится. Прислал подробные изменения: bottomToday/History/Habits/Friends, profile menu/settings/photo, friend find/list/authorized expand, единые cards/red-whiteYT/actual-goal/input overgoal,7описанных эмоций/анимации и week/month/year/cumulative statistics. Они сохранены в requirements, неоднозначная математика пока открыта. Первоначальную просьбу об открытии не повторять как блокер.

Этот отзыв — факт ручного пользовательского просмотра, **не** автоматически пройденные3UI-tests/скриншоты и не уже внедрённая новая версия UI. В этом последующем этапе менялась только документация; code/APK/permissions/данные телефона не менялись. Запрос на backend разделён на local data/tracking план; реального второго агента/Room/collector пока нет.

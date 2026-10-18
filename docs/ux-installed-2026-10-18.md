# Установленная UX/auto версия — 2026-10-18

**0.3.2-ux/code4**, прежний debug package/signature, Room3 без migration/purge.
Новые прямые требования в requirements/DEC-021 актуальнее исторических one-shot/
partial-blocking sections. Два developer разделили UI/core, root интегрировал.

## Включено

- Технические статусы/легенды/tutorial/coverage-простыни/gaps list убраны.
  Интервалы сохранены: локальное HH:mm:ss, duration ч/мин/с без нулевых частей.
  Ненулевое меньше секунды — `<1 с`, raw precision сохранена.
- «Изменить лимит» Shorts доступно независимо от unknown actual/отсутствия goal,
  ввод минут→raw ms. Измеренный actual только системой. Старый core уже позволял
  goal; проблема доступности решена заметной кнопкой, не отменой source guards.
- Today markers в неделе/месяце, smaller one-line settings title/profile width.
- Server editor/URL скрыты; legacy user configure отклоняется. Build-time default
  из `CHRONA_API_BASE_URL`/`chronaApiBaseUrl`, не literal host в source/Git.
  Bootstrap только unconfigured профилю, existing target/session/pending не заменяет.
- Автосинхронизация default-on по target/account, DataStore toggle. Internal exact
  snapshot — retry strategy, не popup consent. WorkManager2.10.1: unique connected
  on-demand10s и periodic15min; process signals10s/loop30s/backoff до120s.
  Binding/frozen requests/idempotency/ACK/source guards сохранены. Sharing отдельно.
- Recorded Shorts counter оценивается сразу; partial coverage не блокирует7эмоций.
  Empty automatic counter0, missing manual Unknown; прошлый день берёт свой DayKey.
  Raw gaps остаются внутри, нулевая эмоция/нетgoal — нейтральный UI fallback.
- Жёлто-золотые собственные Canvas faces, мягкое появление/покачивание/моргание с
  учётом animations-off; не Telegram assets и не обещание точной копии.

## Фактически выполнено

До последних auto/observed правок:149Androidunit/SQLite5/lint0err17warn; isolated
emulator21Compose:20PASS/1FAIL (`actionableErrorsStayVisibleWithoutRawDebugText`,
text assertion). Эти цифры **не** final-auto acceptance. После просьбы владельца
все дополнительные tests/lint/reruns остановлены, старые policy-tests не правились
ради green. Финально только `:chrona:assembleDebug` **BUILD SUCCESSFUL1m18s**.

Final APK SHA256 `f60b7de0941ed0dae30f28148531fe14de1d87983dcb42259d8eb362c7dbd0e9`.
По прямому «давай, устанавливай» desktop: **install-r Success3→4**, own launch
**Status ok/resumed=true**. Без uninstall/clear/-d/-g/reset/force-stop/чтения vault/DB.
Содержимое session/history не извлекалось ради заявления полной сохранности.

Перед обновлением на code3: Home/return metadata PASS, владелец подтвердил обычный
ролик10s→Shorts20s, lock/unlock и floating exclusion, затем offline→online sync.
Это owner-assisted подтверждения без точных независимых замеров, не все-device SLA.

## Остатки

Новая auto/animation математика имеет compile/install proof, не отдельный runtime
E2E. Background Android best effort/Doze/force-stop ограничения сохраняются.
Backend friend projections могут вернуть прежние partial/Unavailable; backend в
этом этапе не менялся. Hard conflicts/старые manual409 остаются с сохранённой
очередью, не обойдены. Year/streak/goal recurrence, multi-device ambiguity,
zero-limit curve, friend ACL/restore/off-host backup/signing остаются отдельными.

Детали реализации: [UI](ux-ui-2026-10-18.md), [core](ux-core-2026-10-18.md),
[auto](auto-sync-2026-10-18.md), [faces](yellow-emotions-2026-10-18.md).

# Notices:5секунд — UI

2026-10-19, `chrona-notice-five-seconds-20261019`, child developer → archi.
MAIN bea052f/code7 указан root. Scope только timer/mapping/presentation UI;
источник session/client/VM notices параллельно исправляет core.

## Исходное наблюдение и правка

- В ChronaApp были два delay3_000: UiNotice по ID и обычная ошибка update-check
  по message. Оба находились выше выбора welcome/ordinary route.
- Оба используют общий timeout5_000. UiNotice effect по-прежнему keyed notice.id,
  не state/recompose: обычные обновления счётчиков и смена welcome route не
  начинают5секунд заново.
- Один последний expired ID и один notice ID страницы входа — bounded saveable
  presentation state, **не** растущий blacklist/hashset. Последний истёкший ID
  скрывается сразу и при повторном emission отправляет существующий DismissNotice.
- При закрытии AccountEntry ранее показанный там notice не переносится старым
  toast в ordinary UI; новый notice с новым ID не скрывается. LOGIN_REQUIRED не
  дублируется баннером поверх страницы, которая уже предлагает обязательный вход.
- Mapping дополнен только explicit routine success/no-change/cancel кодами;
  нет broad suffix/pattern suppression, OFFLINE/real auth/storage/conflict и
  неизвестные реальные ошибки остаются видимыми. UI не объявляет сессию здоровой
  только потому, что last-known профиль существует.
- MandatoryUpdateScreen/required gate/installer error/retry policy не менялись.
  Timeout обычного check-failure баннера не очищает update.message или required.
  DismissNotice — только уведомление, не ACK конфликта, изменения или sharing consent.

## Файлы

- `chrona/src/main/java/by/bebuliby/chrona/ui/ChronaApp.kt`
- `chrona/src/main/java/by/bebuliby/chrona/ui/UserNotices.kt`
- этот report и собственный role run.

Core обязан убрать исходный client notice по тому же ID и отсечь stale generation
после успешного login/logout/операций. Один UI expired ID намеренно не является
полной историей всех прошлых уведомлений: древние generations — ответственность
source/core fix, не бесконечная память renderer.

**Не запускались** tests/lint/build/Git/phone/server/agents/model checks. Tests не
менялись; compile/runtime не заявляются PASS. Root выполняет один assemble после
стыковки. Domain/data/session/VM/backend/update installer/sharing не редактировались.

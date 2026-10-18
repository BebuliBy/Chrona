# Первый phone checkpoint Chrona — 2026-10-18

Основание: владелец прямо подтвердил «Телефон готов» после просьбы USB/включить/разблокировать. Chain archi→desktop, ID chrona-phone-install-20261018. Это проверка установки/старта, **не успешный login или полный runtime acceptance**.

## Выполнено

- Ровно один authorized SM-A376E, Android16/API36, arm64-v8a; перед replace Awake/keyguard off подтверждены metadata. Программного unlock/wakeup/permission enable не было.
- Точный готовый `chrona/build/outputs/apk/debug/chrona-debug.apk`: package by.bebuliby.chrona.debug, code2/name0.3.0-local/labelChrona/min26/target36. Aapt/apksigner verify PASS, ABI подходит. Подпись совместима по фактически успешному PackageManager replace; private keys не читались.
- `adb -s <heldserial> install -r <exactAPK>` → **Success**. Старый тот же пакет code1/name0.2.0-ui →code2. Никаких uninstall/clear/-d.
- Явный ownMainActivity launch **Statusok**, cold TotalTime2237ms; через3s процесс жив, затем own topResumedActivity/window focus подтверждены. Другие приложения не закрывались/не исследовались.
- Только targeted package metadata подтвердили Tailscale установлен. Не запрашивались все приложения/сеть/VPN-конфиги.

Serial/private origin/token/profile/rawdata не в Git. Screenshots/UI-деревья/личные DB/Keystore/vault/чужие окна не читались, crashbuffer не потребовался. Установка без удаления не является проверкой каждой исторической записи/миграции; UI rendering/native Room/Keystore/collector/battery/phone API ещё не приняты.

## Следующий owner flow

1. Посмотреть открывшуюся Chrona; при ошибке сообщить только её текст, не credentials.
2. Tailscale телефона должен быть подключён к тому же разрешённому tailnet. Наличие пакета не доказывает доступности API.
3. В Chrona через профиль→Настройки→**Подключение к серверу** ввести проверенный полный private HTTPS baseURL с `/chrona/`, нажать **Сохранить адрес**. Адрес coordinator получил read-only из server-private `/etc/chrona/client-origin`; в этот doc/repo не копируется. Владелец также может получить его своим terminal `ssh -t server 'sudo -- cat /etc/chrona/client-origin'`.
4. Нажать **Войти через Telegram**, открыть generated link, начать только собственный request и подтвердить лично; вернуться в Chrona/при необходимости **Проверить статус входа**. Не придумывать requestId, bare /start не login.
5. Сообщить координатору результат/ошибку. Первый sync — отдельный явный consent и следующая проверка; login сам не разрешает upload.

На этом checkpoint owner ещё не подтвердил rendering, baseURL не вводился агентом, phone API не запрашивался, Telegram не открывался/не подтверждался, instrumentation не запускались. APK/source прежние, rebuild/restarts/High/tool checks не выполнялись.

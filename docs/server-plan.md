# Chrona: аудит и минимальный план сервера

2026-10-18, 11:00–11:02 Europe/Moscow. ID `chrona-server-20261018`, chain archi → sysadmin. **Только аудит и предложение; внедрение не выполнено.** Источник: разрешённый SSH read-only, без sudo. Адреса и секреты здесь отсутствуют. Новое поручение координатора актуальнее старых отметок «SSH ещё не выполнялся» в проектной документации; продуктовые требования не изменены.

## Проверенные факты

- Debian13, Linux6.12.111+deb13-amd64, x86_64; AMD E1-6010, 2CPU, AVX есть, AVX2 нет. Нужны baseline amd64 образы/бинарники, не x86-64-v3.
- RAM10924MiB, available9569MiB; swap11225MiB, использовано0. На общей FS `/`, `/opt`, `/srv`: свободно413GiB, использовано5%.
- Europe/Moscow, NTP synchronized.
- Docker CLI29.8.2, Compose5.6.0. **Версия running engine и контейнеры не подтверждены:** Docker API permission denied. Socket root:docker0660; текущий пользователь не в docker group. Не добавляли его в группу и не обходили запрет.
- docker/ssh/tailscaled/smbd/spbpu-calendar active; failed units нет. Это не функциональная приёмка Nextcloud/calendar.
- В host namespace TCP и UDP10808 не слушаются. Порты в закрытых контейнерных namespace без Docker inspect не исследованы.
- Home0700 принадлежит текущему пользователю и writable; `/opt` root0755, не writable; `/opt/nextcloud` и `/srv/nextcloud` root0750. `/srv` user-owned, но совместный путь существующих данных: не использовать его вслепую.
- В доступной выборке home и `/opt`, глубина≤2, не найдены Chrona/bot/Xray dirs или compose-файлы. Обнаружены calendar dir и старые admin backups; не читались/не запускались. **Это не доказательство отсутствия других ботов:** закрытые/глубокие каталоги недоступны/вне scope.
- curl/python3/tar/sha256sum доступны. SSH использовал существующую auth, known hosts не изменялись.

## Минимальная целевая схема (предложение)

1. Независимый Compose project `chrona-proxy`: один Xray, endpoint `socks5h://xray-proxy:10808`, отдельно от lifecycle Chrona. Версия и digest закрепляются после проверки официального артефакта и baseline CPU.
2. Закрытая consumer-сеть Docker `chrona-proxy-clients` с `internal: true`; Xray также на отдельной egress bridge. Bot подключён к consumer-сети и внутренней app-сети, **без прямой egress-сети**. Это исключает автоматический direct fallback worker; проверить DNS/маршрут фактически. API при необходимости получает собственный согласованный ingress/egress, не раздаёт доступ к proxy другим сетям.
3. Xray слушает10808 на container interface; **никаких host ports**, host networking, privileged, NET_ADMIN, TUN, host default route/DNS изменений. В Xray нет direct fallback для Telegram. Optional host localhost bind — отдельное решение, сейчас не нужен. Сеть доверенная, не public open proxy; новые consumers допускаются явно, при необходимости SOCKS auth.
4. Отдельный Compose project `chrona`, worker bot + API + собственное хранилище согласно developer contract. Не использовать Nextcloud DB/volumes/network. Внутренние service ports не публиковать. Домен/TLS/API exposure — отдельный gate после контракта и порто-аудита.
5. Только нужный client outbound из активного laptop VPN config, без переноса всего профиля/SSH keys. Private передача — отдельный следующий разрешённый этап. Secrets вне repo/build-context: каталоги0700, файлы0600, read-only mount; доступ nonroot container UID согласовать при staging. Compose file secrets не шифруют диск. Секреты не в argv/Env/URL-логах/чате.
6. Read-only rootfs, drop ALL capabilities, no-new-privileges, nonroot где поддерживается образом, tmpfs для временных файлов; writable только собственный durable state/DB. Без docker.sock/host-root mounts. Первые консервативные пределы: proxy256MiB/0.5CPU; bot256MiB/0.5CPU; уточнить API/DB после контракта, не занимать весь слабый CPU. Логи с ротацией и redaction, без тел сообщений и токенов.

## Bot и recovery: контракт для developer

- Новый бот создаёт владелец через BotFather; username и token не угадывать. Токен приватным файлом, не в Git/APK. До этого Telegram приёмка заблокирована, mock её не заменяет.
- Предложение≈5с: long polling `getUpdates(timeout=5)`, transport read timeout с запасом, без дополнительного5с sleep после успешного ответа. Явная SOCKS5 remote-DNS поддержка библиотеки. При ошибках bounded exponential backoff+jitter, уважать429 retry_after; при потере proxy нет direct fallback.
- Единственный polling consumer с durable lock/lease. Реплики API возможны отдельно, worker не масштабировать простым replicas>1.
- Durable inbox/update_id dedup: offset продвигается только после устойчивого принятия update/transaction; идемпотентные business operations, outbox для ответов и retry. Не обещать exactly-once Telegram send при сетевом ambiguous result. Не использовать drop_pending_updates без отдельного решения владельца.
- Restart policy `unless-stopped` — предложение для собственных контейнеров. Readiness отдельно от liveness: proxy listener не доказывает egress, worker process не доказывает polling. Нужны метрики last successful poll, backlog, retry и DB readiness; payload не логировать.
- Docker healthcheck только помечает unhealthy, не рестартит. Зависимости Compose не восстанавливают живой зависший worker; нужны transport deadlines, loop watchdog/ограниченный fail-fast с корректным сохранением state и сигнал проблемы. Нет restart storm при простой потере интернета.

## Точные gates и следующий объём

**G0 — выполнено:** read-only SSH + локальные plan/run. Сейчас обычным user можно продолжить безопасные метаданные и подготовить обезличенные файлы в выделенном local worktree. Серверные writes, key transfer и контейнерные старты пока не разрешены поручением; отсутствие sudo не расширяет scope.

**G1 — согласовать отдельно read-only privilege audit:** без пароля в чате, через разрешённый владельцем механизм sudo:

- `sudo -n docker version --format '{{.Server.Version}}'`;
- `sudo -n docker ps -a --format '{{.Names}}\t{{.Image}}\t{{.Status}}\t{{.Ports}}'`;
- selective `docker inspect --format` только Name/State(status,health)/image/restart/mount source-destination-mode/network names/port bindings/Compose project-working_dir-config_files labels. **Не Env, не Config.Cmd, не полный inspect/compose config/logs.** Если health отсутствует, шаблон обрабатывает это без полного fallback dump;
- `sudo -n docker network ls` и selective inspect IPAM/subnet/internal только нужных сетей, чтобы избежать конфликтов новых сетей;
- stat/имена active compose paths, существующих bot paths и backup metadata, найденных по этим labels. Не содержимое env/данных. После этого зафиксировать baseline доступности старых контейнеров.

Добавление в docker group/rootless Docker/пакеты для этого не нужны. Docker API практически root-эквивалентен, поэтому user-group shortcut не предлагать вместо согласования.

**G2 — root видит план и принимает layout/точные изменения:** предпочтительно собственные `/opt/chrona-proxy`, `/opt/chrona`, `/var/lib/chrona`, `/etc/chrona/secrets`, `/var/backups/chrona` (финальные UID/paths после контракта). Отдельно разрешить sudo только на создание этих каталогов с owner/mode, staging проверенных Compose/образов, создание двух собственных projects/networks/volumes и запуск/остановку только их. Никаких apt upgrades, usermod, firewall/Tailscale/SSH/systemd edits, reboot и остановки чужих сервисов. Если что-либо из этого потребуется — новый точный запрос, не автоматическое расширение.

**G3 — backup до writes и secrets:** проверка отсутствия конфликтов путей/project/network имён и symlinks. Если новый layout — сохранить pre-state manifest (версии, сети, существующие контейнеры); если найден существующий относящийся config — отдельная owner-only timestamp копия с правами/checksum и проверкой состава. Не трогать чужие backup. Backup Chrona data через согласованный DB consistent dump/backup API, не tar живой DB; protected secrets backup отдельно. Destination/retention/off-host encryption и восстановительный ключ согласовать, без объявления локальной копии полноценным DR. Пока нет новой Chrona data, чужую DB ради staging не останавливать.

**G4 — входы:** desktop metadata активного VPN, приватный разрешённый перенос минимального профиля, BotFather/token от владельца, developer API/DB/migration/bot contract и image/entrypoint/health/secrets schema. Без них не создавать фиктивный production deploy.

## Приёмка и откат после разрешённого внедрения

1. Validate Compose без вывода resolved secrets (`config --quiet`) и Xray syntax test; pinned image manifests/CPU smoke. Проверить nonroot/ro mounts/ресурсные пределы.
2. Xray healthy + реальный HTTPS к Telegram через SOCKS remote DNS; getMe/getWebhookInfo/getUpdates только с редактированным выводом. Webhook пуст, единственный poller; менять webhook отдельно согласовать при конфликте. New bot test message owner→response: нормальный poll≤примерно5с без искусственной задержки, измерить end-to-end без обещания жёсткого SLA.
3. Внешне proxy не доступен, host10808 отсутствует, обычный worker direct egress блокирован, черезproxy работает. Проверить authorised second test consumer, затем убрать только его.
4. Согласованный сбой **только собственных** proxy/worker: reconnect после восстановления, durable offsets/идемпотентность после worker restart, DB migration и isolated restore test. Host internet/SSH не обрывать, reboot не нужен; boot recovery до обычной загрузки не заявлять проверенным.
5. Повторить baseline старых containers/services/портов и доступность прежнего HTTPS разрешённым безопасным endpoint. Не заходить в личные данные/occ, не читать calendar payload.
6. Откат: остановить только новые worker/API и proxy (proxy — лишь если нет иных подключённых пользователей), вернуть предыдущее image/config; миграции только по developer rollback strategy/restore в отдельный volume. Volumes/backups/secrets **сохранять**, без `down -v`, `prune` и удаления чужих сетей. Удаление новых объектов — только точечное согласование после проверки consumers. Если layout новый, выключенный project достаточен для неразрушительного отката.

Статус: аудит завершён в рамках user прав; G1–G4 не пройдены. Контейнеры/Nextcloud функционально не проверены, token/config не читались/не переносились, сервер не изменён. Следующий запрос координатору — точное G1 разрешение и developer contract, не общее «sudo всё».

## Локальная реализация deliverable — продолжение chrona-infra-20261018

**Актуализация chrona-deploy-20261018 / final root refresh80:** root artifact/source `/home/niki/android-tracker/backend` обновлён после owninfra/runtime/DB backup; **только API --no-deps**, SQL прежний. Новый image/current details в deployment docs. API realPG/health200/unauth401/auth503/noidentity/restart PASS; existing HTTPS `/chrona` health200/GETme401/**PUTdiscoverability без session401**/auth503 PASS, NextcloudrootTLS до/после и mappings unchanged. Нет новых production fixtures/accounts, authenticated social/bot flow не имитировался. Worker off/token absent; private origin `/etc/chrona/client-origin` root0600 не выводился в repo/run. Предыдущие proxy/failure/isolatedrestore проверки не повторяли/не переобъявляли новыми, schema unchanged. Phone-tailnet/realbot/authsync E2E owner-pending. [Полный актуальный статус](server-deployment-2026-10-18.md); ниже исторический итог предыдущих этапов.

По отдельному поручению координатора созданы **реальные файлы**, не только план: [infra/README.md](../infra/README.md), три Compose YAML (proxy/app/isolated restore), runtime-only backend Dockerfile, sanitized metadata/outbound examples, private Xray generator, guarded target-local ops CLI (init-data/preflight/deploy/stop/consistent backup/isolated restore), SOCKS remote-DNS HTTPS probe и local validation/unit tests. Все scripts dry-run по умолчанию, без SSH/sudo/install/pull/скрытого запуска при help/test. Target paths/project names фиксированы и защищены от symlinks/adoption чужих ресурсов; backup/restore не удаляют данные.

Backend `api`/`bot` modes и CHRONA_* file/env contract пока adapter proposal. Deploy заблокирован до developer handoff/approval; DB17-alpine UID70/data ownership, JVM/Xray exact images требуют target baseline-amd64 smoke. Official Xray v26.3.27 release metadata и upstream Dockerfile read-only подтверждены публично, **container digest не подтверждён**, required placeholder намеренный. Нельзя использовать release ZIP hash вместо OCI digest.

Источник VPN по handoff desktop: активный user xray.service, VLESS TCP/REALITY; профиль здесь не читался/не передавался. Generator умеет отбирать только client outbound из private input. BotFather/token остаются owner blocker. `--approved` flag не выдаёт разрешение, а требует его наличия ранее.

Локальные Python/unit/PyYAML checks прошли; local Docker Compose plugin отсутствует, `docker compose config --quiet` **не выполнен**. Никакой runtime готовности/restore success/реального Telegram/CPU smoke не заявляется. Сервер с первого аудита повторно не подключался, sudo/serverwrites/keytransfer/контейнерные старты не выполнялись. Отдельный run: `roles/sysadmin/runs/chrona-infra-20261018.md` в памяти команды.

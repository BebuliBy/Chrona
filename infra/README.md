# Chrona infra: локальные исполняемые заготовки

2026-10-18. **Root backend80 API и один bot worker healthy, Telegram auth enabled после owner token install/getMe/webhook verification.** DB/proxy/Serve без изменений, loopback18080 + private HTTPS `/chrona`, Nextcloudroot сохранён. Image `sha256:4d820186b4f15068348e8c9a6b4d9c01099e99285fbd47434c41442c0c3b09b6`, SQL прежний. Verified username только server-private metadata, token не в чат/Git/argv/Env. Runtime activation: [server-deployment](../docs/server-deployment-2026-10-18.md). Real owner login/message/phone E2E ещё не выполнен; ниже bootstrap defaults/historical checks, не указание выключить активного бота.

### Активация chrona-bot-activation-20261018 выполнена

Token metadata0600/uid10001/gid10001/parent0700 PASS. GetMe+getWebhookInfo реально через SOCKS/TLS, webhook empty/pending0; только после проверки private username/fresh marker и empty SQL queue/lease стартовал один worker --no-deps/native ready, затем APIauth true --no-deps. Metadata/config/DB backups сохранены.7 SQL samples/36s: poll success продвигался, max age7781ms, одна lease, persisted monotonic offset, restart0; inbox/outbox/delivery/identity creations0. Worker internal networks only/no hostports. Никаких автоматических тестовых сообщений и fake login. Retry/backoff параметры проверены по code80, новая fault/429 injection не делалась.

Enabled-auth smoke **не вызывает auth-begin POST**: safe GET health/unauthenticated reads/PUT guard только, без challenge creation. `disable-telegram` — fail-closed rollback own worker/API flag с сохранением offset/inbox/outbox; не webhook delete/drop. GetMe/webhook/config/activation команды ниже уже выполнены, **не повторять без review**. Token value никогда не выводить. Обычный `/start` без payload в backend80 noop; настоящий видимый тест начинается самим owner из Chrona (Telegram link со своим реальным requestId, явное подтверждение, возврат). No real auth/messages E2E claim до owner шага.

Финальная activation-проверка: **33/33 local tests PASS**, server preflight/Compose PASS, bot+API healthy, enabled-safe HTTPS200/unauth401 и NextcloudrootTLS/mappings/legacy PASS. Unit counts30 и auth503/worker-off ниже — история bootstrap, текущий state указан в начале. Native retryerror/429 fault injection в этой активации не выполнялась, live polling/SQLlease/offset и readiness проверены без rawupdates/чатов/сообщений от агента.

## Состав и границы

- `compose.proxy.yml`: reusable project `chrona-proxy`, Xray nonroot65532, закрытая стабильная сеть `chrona-proxy-clients`, отдельный `chrona-proxy-egress`, контейнерный10808 **без host ports**.
- `compose.app.yml`: API/bot/migrate из одного image, **точный обновлённый developer runtime contract**: `DB_URL`, `DB_USER`, `SECRETS_PATH`, `TELEGRAM_AUTH_ENABLED`, `TELEGRAM_PROXY`, `BIND_ADDRESS`, `PORT`, `JAVA_OPTS`; nonroot10001/native health. API и migrate **не требуют BOT_USERNAME**, API явно false. Worker profile `bot` выключен, требует реальный private username+token. API уже healthy на127.0.0.1:18080, fresh free-port check был выполнен. У API дополнительная **собственная** ingress bridge для host-loopback publishing; worker/DB остаются только на своих internal сетях. API/migrate token не монтируют. Optional server-only `telegram.env` используется только после owner setup; примеры не содержат реального username.
- `compose.restore.yml`: отдельный project/network/data path/volume, никаких app/proxy/production DB подключений.
- `backend/Dockerfile`: runtime-only packaging проверенного developer `build/install/chrona-backend` (только bin launcher + lib JARs в `distribution/`). Точный entrypoint `/opt/chrona/bin/chrona-backend api|bot|migrate|healthcheck` как в backend/Dockerfile. `JVM_IMAGE` — pinned official17-jre-jammy; никакой замены launcher на fatjar. Контекст только reviewed distribution+Dockerfile, не repo/secrets/reports/cache. Build/pull реально выполнены на сервере, image ID в deployment checkpoint. Для локально собранного backend допустим immutable `sha256:<image-content-ID>`, не выдавать его за registry RepoDigest.
- `scripts/ops.py`: deploy/stop, consistent own-DB pg_dump, isolated restore; **без SSH/sudo**, dry-run по умолчанию. Только точные собственные projects/paths; symlink/owner/path guards, exclusive backup creation, no automatic deletion/retention, no prune/down-v. `stop-proxy` намеренно блокируется до отдельного живого consumer review.
- `scripts/xray_config.py`: из private outbound/full config выбирает только outbounds[0], VLESS TCP/REALITY. Создаёт свой SOCKS10808 и один VPN outbound; не переносит laptop routing/DNS/inbounds/logs. Запрещает server privateKey, dependent proxySettings/sockopt/mux; при неожиданном профиле fail closed, нужен review. Значения/исключения с содержимым не выводит. Нет direct freedom fallback. Логи Xray выключены для защиты endpoint/client identifiers.
- `scripts/probe.py`: SOCKS5 remote-DNS → TLS-verified Telegram HTTPS без token, запускать позже **в consumer namespace**. Это egress probe, не bot auth/functional acceptance. Default dry-run без сети.

## Подтверждение источников / image pinning

Публично read-only проверены 2026-10-18:

- https://api.github.com/repos/XTLS/Xray-core/releases/tags/v26.3.27 — stable release существует, не draft/prerelease. Linux64 release ZIP SHA256 `23cd9af937744d97776ee35ecad4972cf4b2109d1e0fe6be9930467608f7c8ae` по GitHub assets metadata. **Это ZIP hash, НЕ container image digest. ZIP не скачивался/не исполнялся.**
- https://raw.githubusercontent.com/XTLS/Xray-core/v26.3.27/.github/docker/Dockerfile — официальный entrypoint `/usr/local/bin/xray`, distroless nonroot65532, declared config/log VOLUMEs; Compose перекрывает их tmpfs, свой конфиг отдельным ro bind.

OCI index и выбранный linux/amd64 manifest **фактически проверены** через registry GET с проверкой content SHA256. Результаты: `verified-images-2026-10-18.json`; Xray/Postgres pins в metadata example, JRE pin отдельно. Затем pinned images реально pulled и CPU-smoked на сервере, PG/JDBC/API работают на данном CPU. Никакие anonymous registry auth tokens не выводились. Подпись/SBOM/бинарное происхождение не исследовались. Registry platform amd64 сама по себе не доказывает отсутствие AVX2 instructions. `--pull never` исключает скрытое обновление при обычном deploy; повторные base pulls для API update не требовались.

## Контракт сети и масштабирования

Bot подключён **только** к internal `chrona-app` и internal `chrona-proxy-clients`; у него нет внешней bridge. Xray одновременно consumer+egress, не network gateway/host TUN. На Docker networking это целевая deny-direct граница, которую нужно доказать отрицательным runtime test. Internal-сеть не гарантирует отсутствие DNS-запросов через embedded resolver: library должна передавать Telegram hostname SOCKS5 remote DNS, а endpoint VPN может разрешаться самим Xray через egress. Запрещён direct fallback в worker.

Другой consumer: явно согласовать владельца/назначение → подключить к **external** сети `chrona-proxy-clients` (в Compose external означает уже существующая, не публичная) → задать `socks5h://xray-proxy:10808` в реальную поддерживающую SOCKS библиотеку → проверить TLS/remote DNS и отсутствие direct fallback. Не подключать недоверенные контейнеры: noauth SOCKS доступен всем членам сети. Сам proxy может достигать иных containers этой доверенной сети. Если требуется разделение tenants — новые сети/auth/ACL отдельным решением. Не делать `HTTP_PROXY=http://...:10808`, это SOCKS, не HTTP proxy.

Один bot poller с SQL lease, durable inbox/update_id offset/dedup/outbox, retry_after/backoff+jitter и bounded transport timeout. Предлагается `getUpdates(timeout=5)` без дополнительного5с sleep. Дедупликация бизнес-эффектов обязательна; exactly-once Telegram send при ambiguous network result не обещается. API масштабируется отдельно после контракта ingress/stateless sessions/migrations; не задавать replicas>1 для worker. Хранилище offset/outbox в SQL, а не rootfs. Нельзя автоматически удалять pending updates.

## Staging / эксплуатация — разрешено и выполнено для DB/proxy/migrations

1. Сначала отдельно разрешённый selective sudo Docker audit из server-plan. Проверить baseline legacy, конфликты имён projects/networks/volumes, mounts, подсети, наличие других bot pollers. **Никаких изменений групп, apt, чужих сервисов.** Сохранить redacted manifest и backup ранее существующих относящихся конфигов, если такие найдутся. Наши CLI не делают этого approval за пользователя.
2. Утвердить layout: root-owned `/opt/chrona/infra` с reviewed code; `/etc/chrona/runtime.env` root0600 (только nonsecret metadata); `/etc/chrona/secrets` root0700; `/var/backups/chrona` root0700. Скрипт НЕ устанавливает себя и не принимает произвольный base-dir; staging выполняет разрешённый оператор по конкретному плану. Нельзя размещать secrets в repo/worktree/Env. Встроенный `init-data --execute --approved` создаёт **только новые** `/var/lib/chrona` root0700 и `/var/lib/chrona/postgres` uid70 mode0700; при существующем base отказывает. Чужие ownership не переписывает. Непривилегированный Postgres не может инициализировать root-owned fresh Docker volume, поэтому volume local driver bind указывает именно на предварительно подготовленный path. Restore создаёт отдельный новый `/var/lib/chrona/restore-postgres` uid70.
3. Минимальный VPN client outbound **фактически** прочитан только программой и передан через SSH stdin → generated isolated server file0600 uid65532 под0700 parent, без local secret copy/stdout. Не переносились SSH keys/весь VPN directory/laptop inbound/routes. Laptop service/source не менялись. Standalone generator file-input требует0600; deployment driver использовал private stream без дублирующего outbound файла на сервере.
4. Generator по умолчанию ничего не читает/пишет. Пример **будущего** target-local вызова от root после разрешения: `python3 /opt/chrona/infra/scripts/xray_config.py --input /etc/chrona/secrets/outbound.json --output /etc/chrona/secrets/xray.json --execute --approved`. Вход root0600 в root0700. После syntax review оператор назначает **только output file** uid65532:65532,0600 для ro bind; parent остаётся root0700. Не печатать config. Существующий output не перезаписывается — timestamp backup/review/замена отдельно.
5. BotFather только владельцем, token приватным файлом `/etc/chrona/secrets/telegram-token` uid10001:10001,0600; **не отправлять токен в чат**. После установки reviewed script: `ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/install_bot_token.py --execute'`. Sudo и token вводятся интерактивно/скрыто, token не аргумент команды и не history; script отказывается перезаписывать существующий файл. Bot автоматически не запускает. DB password генерировать криптостойко только на сервере: два одинаковых отдельных private файла `db-password` uid70:70 и `app-db-password` uid10001:10001,0600; root0700 parent. Bootstrap postgres role `chrona` с DDL как в developer v1; отдельный least-privilege runtime role остаётся hardening follow-up, не обращаться к Nextcloud credentials.
6. Developer contract: JVM17/api/bot/migrate/healthcheck/SQL/nativehealth; **актуальный root checkpoint80/80 source tests** по handoff developer (social discoverability/confirmed display flow, previous nonempty view fix сохранён). Sysadmin live PG/API health/401/503/noURL/noidentity/restart PASS; через прежний HTTPS `/chrona` также **PUTdiscoverability без session401**, NextcloudrootTLS/mappings keep PASS. SQL прежний, production fixtures/accounts не создавались. Authenticated social/view/bot E2E pending; API startup не блокируется BotFather.
7. Проверить источники/digests/CPU и безопасно stage images отдельно по согласованию. Никакой image build/pull не происходит через `--help`/tests/dry-run. Перенести metadata в target root0600, затем preflight и Xray syntax test; ни один marker не выставлять вместо реальной проверки.

Flag `--approved` — защита от случайности, **не механизм выдачи полномочий**; текущее scope разрешено пользователем. Не запускать scripts из изменяемого user-worktree с sudo: сначала reviewed root-owned staging. Для sudo baseline безопасный helper: `python3 -B infra/scripts/remote_baseline.py --execute --prompt` из терминала владельца (hidden getpass, no file), либо однократный external0600 input, никогда credential literal в tool command. Helper не изменяет сервер.

## Поздние команды на target (сейчас НЕ выполнены)

Без `--execute` все actions — безопасный dry-run даже без файлов/daemon:

```sh
python3 /opt/chrona/infra/scripts/ops.py deploy-proxy
python3 /opt/chrona/infra/scripts/ops.py deploy-app
python3 /opt/chrona/infra/scripts/ops.py backup
```

После отдельных approvals, корректного layout и CPU/image/contract gates:

```sh
python3 /opt/chrona/infra/scripts/ops.py preflight --execute --approved
python3 /opt/chrona/infra/scripts/ops.py xray-test --execute --approved
python3 /opt/chrona/infra/scripts/ops.py deploy-proxy --execute --approved
python3 /opt/chrona/infra/scripts/ops.py deploy-db --execute --approved
python3 /opt/chrona/infra/scripts/ops.py migrate --execute --approved
python3 /opt/chrona/infra/scripts/ops.py deploy-app --execute --approved
python3 /opt/chrona/infra/scripts/ops.py backup --execute --approved
python3 /opt/chrona/infra/scripts/ops.py restore --archive /var/backups/chrona/APPROVED_TIMESTAMP.dump --execute --approved
python3 /opt/chrona/infra/scripts/ops.py stop-app --execute --approved
```

Command execution prints no full Compose config/Env/private payload or raw subprocess stderr. Failed backup preserves `.partial`; never call it valid. Script requires action-specific pinned metadata even for stop/backup (preserve approved release metadata for emergency operations); proxy staging needs only Xray pin, backup/restore only Postgres pin, app/all-preflight all three. Backup ignores bot token/contract gate but requires baseline metadata and own resources. Existing own networks must really be internal, existing volumes must point at exact reviewed own paths. Do not bypass checks by guessing marker values.

## Backup / restore / rollback

`backup`: SQL-consistent `pg_dump -Fc --no-owner --no-acl` **only own** `chrona` DB, archive0600 under root0700, exclusive timestamp name, no replacement; `pg_restore --list` structural check + SHA256. Does not read application rows into model/stdout. DDL migrations must not run concurrently. This backs up one DB (including bot durable state if developer uses it), not role/global objects/config/secrets. Keep reviewed compose, exact image refs and separately protected secrets/role recovery procedure. Destination/retention/off-host encryption/recovery-key owner still **UNRESOLVED**. Local dump same disk is not disaster recovery. No unattended retention deletion implemented.

`restore`: checksum first, refuse existing `chrona-restore-data` volume or restore path; new isolated internal network, same pinned Postgres17, no production mounts/network/ports. Start own restore DB with `--wait`, restore transaction with exit-on-error, SQL SELECT1, then stop only restore DB; preserve all data/volumes for inspection. SQL restore success is **not** application semantics acceptance: developer must validate migration/schema version/row invariants/access rules/durable offset on isolated data. Do not start a real bot against restored token/state. Rerun requires explicit reviewed cleanup/archive of only previous restore resources, never automatic deletion.

Rollback: stop only new Chrona project, retain volumes/dumps, restore previous verified image/config after migration compatibility review; destructive/down migrations not assumed safe. Reusable proxy can be stopped **only** after current network consumer review (including other projects), not as part of ordinary Chrona rollback. `stop-proxy` CLI deliberately fails closed. No down-v/prune/other networks/legacy data deletion. Existing SQL state stays untouched during configuration rollback. Full old DB restore targets a new own path/volume, not overwrite production blindly.

## Readiness / критерии (фактические результаты в deployment checkpoint)

- Xray config-test validates syntax; proxy HTTPS probe validates route/TLS, neither proves BotFather auth/polling. Distroless не содержит shell/curl — fake healthcheck не добавлен.
- DB pg_isready readiness does not validate migrations. Native API/bot healthcheck использует ready endpoint; bot PORT8081 задан явно. Docker unhealthy does not auto-restart; depends_on only startup ordering, no future health orchestration. Worker имеет deadlines/watchdog, reconnect и persisted state по developer checkpoint, но real runtime ещё не проверен. `unless-stopped` restores approved containers after daemon start, but boot/reboot recovery сейчас не проверено.
- На целевом CPU: exact image versions, UID/files/DB init, Xray syntax, no host10808, network membership, remoteDNS egress and **negative direct-egress test from bot namespace**, no host routes changed.
- Real owner test: getMe/webhook state/polling through proxy, no active webhook/second poller; controlled message-response and approximate5s polling evidence, no message payload in report. Owner token is a blocker until provided privately.
- Controlled failure only own proxy/worker: bounded retries, restore connection, durable offset/dedup/outbox, no duplicate business operation. Do not disconnect host network/SSH or reboot.
- Baseline legacy containers/ports/SSH/Tailscale/calendar and safe HTTPS endpoint preserved; no user personal payload read. Isolated restore application checks, off-host restore still separate pending gates.

## Локальные проверки (без daemon, пакетов и реальных credentials)

```sh
python3 -B infra/scripts/validate.py
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest discover -s infra/tests -v
python3 -B infra/scripts/ops.py --help
python3 -B infra/scripts/ops.py deploy-app
python3 -B infra/scripts/xray_config.py --input /missing/input --output /missing/output
python3 -B infra/scripts/probe.py
```

Для `docker compose config --quiet` использовать **только synthetic local env** и явный файл `--env-file /dev/null`; никогда не выводить resolved config. Проверка парсинга не доказывает наличие images, mount permissions или runtime functionality.

Фактический локальный статус 2026-10-18: **30/30 tests PASS**, AST/PyYAML PASS; local Compose plugin отсутствует → SKIP только local config. **Server** Compose5.6.0 config, pinned-image pulls/CPU smoke, Xray syntax/real Telegram HTTPS remoteDNS/direct-deny/restart recovery, PG17.11/JDBC migration/concurrency/reconnect, consistent backup/isolated SQL restore **PASS**. Checkpoint74 API native health/HTTP200/401/disabled-auth503/noURL/noidentity/restart/JDBC и legacy regression **PASS**. Private tailnet HTTPS prefix rewrite/CA/200/401/503 и прежний NextcloudrootTLS **PASS**. Telegram/app/phone E2E ещё не выполнены. Пакеты не устанавливались; worker/token helpers не вызывались. Target scripts root-owned, deployment auth только private stdin.

Own PostgreSQL/restore named volumes используют `nocopy: true`, чтобы Docker image directory mode не менял предварительно подготовленные uid70/0700 backing paths. Уже созданные top-level own data paths проверены/приведены0700 после root-only permissions snapshot; PGDATA и файлы рекурсивно не менялись. Strict server preflight permissions/resources/config PASS; эти исправления не затрагивают Nextcloud volumes.

### После BotFather (только владелец; сейчас token отсутствует)

```sh
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/install_bot_token.py --execute'
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/configure_bot.py --execute'
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/ops.py enable-telegram --execute --approved'
```

Первая команда hidden-input/exclusive0600 token install; вторая optional **getMe через SOCKS remote DNS/TLS**, real username → root0600 `/etc/chrona/telegram.env`, без вывода значений; третья backup runtime → flagtrue → один worker readiness → enabled API. Ошибка активации возвращает metadata/останавливает свой worker/восстанавливает API config. **Эти token/getMe/activation paths не запускались** и требуют real проверки после нового owner token; no fake bot.

### Private HTTPS phone base URL — реально настроен

Root разрешил только новый `/chrona` на существующем Tailscale Serve443. `serve.py baseline` сохранил actual JSON root0600 в ownbackup, подтвердил existingrootTLS/noFunnel/nochronaconflict. `apply` использовал только standard --set-path (не reset/set-config), `check` реально проверил trusted-CA HTTPS `/chrona/health/ready`200, `/chrona/v1/me`401 и authbegin503/noidentity; **Nextcloudroot и все прежние mappings неизменны**. Prefix rewrite прошёл; rollback не понадобился. Политика только tailnet, не Funnel/publicinternet.

Origin только server `/etc/chrona/client-origin` root0600 под root0700, не в Git/APK/run. Владелец может вывести себе в терминал:

```sh
ssh -t server 'sudo -- cat /etc/chrona/client-origin'
```

Использовать полное значение (включая `/chrona/`) в Chrona HTTPS baseurl setting. Телефону нужен tailnet/policy доступ; device UI/network path пока не проверен. Без BotFather login всё ещё503, не обещать auth/sync E2E. Собственный route-only rollback (если отдельно нужен): `ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/serve.py rollback --execute'`; helper не сбрасывает Serve и отказывается перетирать concurrent unknown mappings. Serve/hostname/backup JSON приватные, в отчётах только обезличенная матрица результатов.

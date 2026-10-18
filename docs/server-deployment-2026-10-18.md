# Chrona deployment checkpoint — 2026-10-18

ID основной `chrona-deploy-20261018`, активация `chrona-bot-activation-20261018`, chain archi → sysadmin, без делегаций/Git. **Текущее состояние: backend80 API + один bot worker healthy, Telegram auth enabled; private HTTPS `/chrona` и Nextcloudroot сохранены. Реальный owner login/messages E2E ещё не выполнялся.**

## Актуальная активация после owner hidden token install

Владелец сообщил только успешный результат hidden installer; token не передавался в чат. Первое серверное действие — **только stat metadata**: file exists/nonempty, uid:gid10001:10001,0600, root0700 parent. Значение token моделью не читалось/не выводилось; его читают только explicit runtime helper/worker для TLS-аутентификации. Credentials для sudo только private stdin, без literals/argv/Env/logs/files.

До запуска: pre-state root0600 `/var/backups/chrona/bot-activation-prestate-20261018.json`, API auth false/no worker, expected backend80 image, PG/proxy IDs и identity counts, Nextcloudroot trustedTLS и Serve mappings PASS. Перед правками scripts — owninfra archive; перед активацией — consistent DB backup и root0600 runtime metadata backup `runtime-pre-telegram-*.env`.

`configure_bot.py --execute` реально выполнил **getMe и getWebhookInfo через SOCKS remote DNS/TLS**. Verified username только в `/etc/chrona/telegram.env` root0600; publicidentifier возвращён родителю/владельцу, в этот repo/run не записан. Webhook URL пуст, pending_update_count0. Root0600 `/etc/chrona/bot-identity-verified.json` содержит только verification flags/timestamp. **Никаких deleteWebhook/drop_pending_updates/getUpdates preview/sendMessage тестов.** При webhook/pending/invalid identity helper fail-closed до worker/auth. Fresh marker + empty SQL inbox/outbox + inactive lease + отсутствие local worker проверяются перед enable, чтобы не запускать второй worker/не рассылать старую очередь.

`ops.py enable-telegram --execute --approved`:
- сохранил прежнюю runtime metadata;
- выставил true, запустил ровно один own bot profile **--no-deps**, дождался native readiness с реальным successful poll;
- только после этого пересоздал API **--no-deps** с enabled auth и проверил readiness;
- **не** пересоздавал PG/proxy/legacy и не менял backend image/Serve. Backend80 immutable ID прежний `sha256:4d820186b4f15068348e8c9a6b4d9c01099e99285fbd47434c41442c0c3b09b6`.

Реальные проверки:7 SQL samples за36s, success timestamp продвигался, максимальный age7781ms, одна стабильная lease, lease future, next_offset хранится в SQL/монотонен. Один Compose worker healthy, restartcount0; APIhealthy, actual flags true+verified name present (только boolean check, не Env dump), тот же image. Worker **только** chrona-app+chrona-proxy-clients internal networks, без hostports/direct egress. Inbox/outbox/delivered/retry jobs0, accounts/challenges/sessions counts не изменились — **сообщения не отправлялись, login не имитировался**. No local second poller/conflict symptoms observed; абсолютное отсутствие внешнего процесса с копией token не обещается.

`getUpdates(timeout=5,limit=50)`/durable offset transaction/lease45s и retry0.5..30s+jitter/429 retry_after source80 проверены; native poll progression действительно наблюдалось через proxy. 5s — long-poll parameter, **не** жёсткий end-to-end SLA. Error/retry/429 ветку новой аварией в этой активации **не провоцировали**, crash/reboot durability не тестировали; SQL persistence не выдаётся за такую проверку. Предыдущие proxy outage/reconnect tests остаются отдельным pre-activation evidence.

Нынешний безопасный HTTPS smoke **не делает auth-begin POST**, когда auth enabled: health200, unauthenticated GETme/PUTdiscoverability401, identity counts unchanged; NextcloudrootTLS/Serve mappings unchanged. Bootstrap API/Serve smoke обновлены с этой защитой (regression test), чтобы прежний503-тест случайно не создал login challenge. Bootstrap image-refresh также отказывается молча отключать уже enabled auth.

Rollback не понадобился. При enable failure helper возвращает old metadata/stop own bot/API config; при обнаруженном stale/conflicting poll state наблюдатель fail-closed использует `ops.py disable-telegram --execute --approved`: backup, stop только own worker, auth false, recreate API --no-deps. SQLoffset/inbox/outbox сохраняются, webhook/updates не удаляются. Readiness healthcheck сам по себе Docker restart не делает; worker watchdog остаётся backend responsibility. Fresh verification/review требуется перед повторной активацией, не запускать второй poller с этим token.

Финальный activation result: **33/33 local tests PASS** (включая guard против synthetic auth-begin при enabled mode), server permission/resource/Compose preflight PASS, API и bot native healthy; enabled-safe HTTPS200/unauth401, existing NextcloudrootTLS/Serve mappings/legacy container IDs/images/services PASS. PG/proxy IDs и backend80 image неизменны. `getMe/getWebhookInfo`/activation реально выполнены; 503-only bootstrap sections ниже исторические. Новые source changes только infra helpers/tests/runbook, без backend/Android/High/Git/sharedmemory.

### Точный следующий owner test

Владелец может сам открыть verified bot и отправить `/start` позже; **bare `/start` в backend80 нормализуется в noop и приветствия не обещает** (source transport принимает `/start <requestId>`). Для видимого настоящего login test: телефон с tailnet доступом и правильным private HTTPS base URL → нажать вход через Telegram **в Chrona** → открыть сформированную ссылку `/start <реальный requestId>` → подтвердить только собственный начатый запрос → вернуться в Chrona. Не сочинять UUID/start payload и не подтверждать чужие запросы. Actor/sysadmin ни одно сообщение/подтверждение вместо владельца не отправлял. После owner действия отдельная приёмка app↔bot/account/sync/friends ещё нужна.

Ниже сохранена история bootstrap/pre-token checkpoints; их `authdisabled503`/`worker off` — прошлые состояния, **не актуальные после этой активации**.

## Последний refresh — root backend80

Единственный источник последнего refresh: **`/home/niki/android-tracker/backend/build/install/chrona-backend` и root `backend/src/main`**, не прежний `/tmp` checkpoint74. По handoff integrationdeveloper — clean80/80 PASS: own discoverable в GETme, authenticated/idempotent PUT `/v1/me/discoverability`, bot discover on/off и sanitized display name при подтверждённом login; private default/no auto-grants. Новая функциональность infra не добавлялась, только source path/image tag и security smoke PUT instrumentation.

Перед update: existing Nextcloudroot trusted-TLS/status и Serve mapping compare PASS, owninfra/runtime+consistentDB backup. Перенесены только dist/allowlisted main-source/build context, без reports/cache/secrets. SQLversion/checksum прежние (V001/version1, existing discoverable column), проверены против livePG до update. Обновлён **только API --no-deps**, без DB/proxy/Serve changes. **Текущий immutable image** `sha256:4d820186b4f15068348e8c9a6b4d9c01099e99285fbd47434c41442c0c3b09b6`.

Реально PASS после update: PG17.11/JDBC/native API healthy, loopback health200/unauth401/authdisabled503/noURL/noidentitycounts, APIrestart/JDBCreconnect/same migration. Через существующий trusted-CA HTTPS `/chrona`: health200, GETme401, **PUTdiscoverability без сессии401** с valid `enabled` JSON/Idempotency-Key (не фиктивный account), authbegin503 без loginURL/account/challenge/session creation. NextcloudrootTLS/status до/после и все Serve mappings сохранены; old container IDs/images/status/services unchanged. Worker off/token absent. Authenticated social flow/confirmed login/botcommands на реальном пользователе **не тестировались**, до owner BotFather нет auth E2E. Старые failure/isolated restore checks не повторяли: schema unchanged, результаты предыдущего этапа ниже не объявляются новой приёмкой.

Owner retrieval origin и hidden token install commands ниже остаются действующими; hostname только в server-private `/etc/chrona/client-origin` root0600, в repo/run его нет. Phone tailnet/UI и real bot/sync/friends E2E pending. Остальные разделы ниже сохраняют проверенную историю предыдущих checkpoints, **текущий image — checkpoint80 выше**.

## Разрешение / фактическая граница

Пользователь явно разрешил sudo и текущий Chrona layout/deploy/private transfer; root дополнительно разрешил **только свой `/chrona` path** существующего Tailscale Serve443 с сохранением root/прочих mappings. G1/G2 permission gates сняты. Не разрешены чужие остановки/данные, system upgrade, reboot, usermod, firewall/global routes/DNS, другие Tailscale mappings/настройки/Funnel. Ничего из этого не выполнялось; разрешённый узкий Serve path добавлен и проверен ниже.

Первоначальный read-only SSH `sudo -n true` потребовал аутентификацию, exit1. После уточнения координатора **защищённая stdin-аутентификация фактически прошла**, далее privileged SSH использован в разрешённом scope. Credential values не передавались в argv/Env/tool literals, не печатались и не сохранялись. Auth helper поддерживает `--password-stdin`; authentication не является текущим blocker.

Использован `infra/scripts/remote_baseline.py`:

- По умолчанию dry-run, без SSH/credential reads.
- Для терминала владельца: `python3 -B /tmp/opencode/chrona-infra/infra/scripts/remote_baseline.py --execute --prompt`. Hidden terminal prompt, secret только в process memory → stdin `sudo -S -p ''`, не shell args/Env; read-only baseline, ничего не устанавливает/не меняет.
- Основной фактически использованный канал `--password-stdin`: authorized secret только в process memory → SSH/sudo stdin, без локальных credential files. Hidden prompt/one-use external file остаются optional инструментами, повторный ввод владельца для этого deploy не потребовался. Credentials/их отношения не документируются.
- Выборочный baseline не читает Env/Cmd/Health.Log: только Docker engine/Compose, container status/health/image/restart/mounts, три Compose path labels, network IPAM/internal и own path conflicts/services/10808+18080 sockets. Сырые metadata с инфраструктурными адресами не переносить в публичную документацию.

Selective baseline выполнен до изменений: Docker engine29.8.2/Compose5.6.0; четыре legacy Nextcloud containers running, DB/Redis healthy; пять services active; own paths/network/volumes отсутствовали; host10808 и18080 свободны. Затем root-only prestate manifest сохранён **до runtime writes**. SSH auth keys не читали.

## Достигнутый implementation/runtime checkpoint

1. Прочитан точный developer runtime contract `docs/backend-2026-10-18.md` из backend worktree, Main.kt и backend Dockerfile. Developer сообщил46 tests PASS на H2; это не реальный PostgreSQL.
2. Собственные infra-файлы приведены к distribution launcher `/opt/chrona/bin/chrona-backend api|bot|migrate|healthcheck`, JVM17, UID10001, SECRETS_PATH `/run/secrets/chrona`, точным DB_*/TELEGRAM_PROXY/BOT_USERNAME/BIND_ADDRESS/PORT/JAVA_OPTS. Старые CHRONA_* env и fatjar template устранены. Distribution context включает только launcher/lib JARs, не reports/cache/test fixtures/secret values.
3. Profiles разделены: DB работает отдельно; API `api`, worker `bot`, миграции `maintenance`. Worker без token не запускался/не получает restart loop. **API реально healthy на127.0.0.1:18080→container8080**, свежий free-port check до старта выполнен. Bot8081/DB5432/proxy10808 на host не публикуются. BIND_ADDRESS=0.0.0.0 относится только контейнеру. Для API отдельно создан только own ingress bridge `chrona-api-ingress` с default host binding127.0.0.1; DB и worker к нему не подключены. Worker по-прежнему только на двух internal сетях, без direct egress.
4. Три official registry image references проверены реальными HTTPS manifest GETs; index и selected baseline linux/amd64 body hashes совпали. Без Docker daemon/pull/download image layers. Anonymous registry tokens только в process memory, не в выводе. Результаты сохранены в `infra/verified-images-2026-10-18.json`.

| Образ | Проверенный amd64 manifest digest |
|---|---|
| ghcr.io/xtls/xray-core:26.3.27 | `sha256:4198aa816f04eeacde6470f4f07947eb4f701a1bba3657e366636681eaae5855` |
| eclipse-temurin:17-jre-jammy | `sha256:30a8176ab22afd141ad9cd1948ad23bdb9fb7ddae666bad4a1d188d9f479d585` |
| postgres:17-alpine | `sha256:aa90e97ee862e558111d34cfb8b2c4bec768c2b039fb791341686928560263b3` |

Все три pinned images **фактически pulled** и прошли native version smoke на AMD E1-6010 безAVX2. Первый image до auth fix `sha256:39ff6ed6b621939d83dde5d65fb6e10f41a2279a5e3fb7b4735efed5c7dfd8e8`; предыдущий auth-disabled image `sha256:833f8ee73c8c01b6d2296a68cf920c47b307b9f25ace46d76e892173dff0b262`. Предыдущий checkpoint74: `sha256:a06ea2a5e4249c996693113e85b6f6a3917c3910fd58b376ea01b5992b63c5ca`; актуальный root80 image указан в первом разделе. Все — local content IDs, не registry digests; прежние images сохранены. Без серверной компиляции/OS-пакетов/base-image upgrades. Реальный PG — **17.11**. JDBC migration/concurrency/reconnect проверки реальны, не нагрузочный benchmark.

## Выполнено на сервере / layout / backup

- Новые root-owned paths: `/opt/chrona/infra`, `/opt/chrona-proxy` (reserved), `/etc/chrona/secrets`0700, `/var/lib/chrona`0700, `/var/backups/chrona`0700. Перед созданием проверены absence/symlinks/owners и collisions own networks/volumes. Pre-state `/var/backups/chrona/prestate-20261018.json`0600 с IDs/images/status legacy containers и baseline services/networks; без credentials/Env/payload.
- DB password криптостойко создан **только на сервере**, без вывода. Два отдельных файла0600 для uid70 PostgreSQL и uid10001 JVM; parent root0700. Postgres volume `chrona-db-data` — local bind `/var/lib/chrona/postgres` uid70/0700, отдельно от Nextcloud.
- Только минимальный VLESS client outbound прочитан программой из разрешённого laptop source и передан через SSH stdin без локального secret copy/stdout. На сервере generated isolated `xray.json`0600 uid65532, parent0700; laptop inbound/routes/service/source не менялись, SSH keys/server privateKey не переносились.
- Xray config test и **все три server Compose config --quiet PASS**. Proxy работает на internal client network + отдельном egress bridge, без host10808/TUN/default-route изменения.
- Own DB healthy, real JDBC initial migration PASS, migration version1 ровно одна; два concurrent JVM migrations PASS. Controlled restart **только собственной DB**, затем JDBC reconnect/migrate PASS.
- Реальный HTTPS/TLS HEAD api.telegram.org через SOCKS5 **ATYP=domain/remote DNS PASS** из собственного временного consumer namespace, без bot token. Прямой TCP443 из consumer namespace запрещён. Финальная повторная проверка **на обеих internal сетях, как worker, PASS**, включая fail-closed outage и reconnect. Сопутствующие channels/network metadata наружу не публиковались.
- Controlled stop/start **только нового proxy**: outage fail-closed, после start реальный TLS через тот же endpoint восстановился. Host10808 listener отсутствует. Собственный labelled test container удалён; чужие containers/networks/volumes не удалялись.
- Consistent own-DB `pg_dump -Fc`, структурная проверка архива и SHA256 PASS; completed dump **25343 bytes**. Изолированный restore в собственные `chrona-restore-data`/internal network/отдельный path PASS. Сверены migration checksum/version, seeded bot_state offset, accounts count и public-table count source/restore. Это пустая по пользователям DB с initial state, **не** доказательство восстановления реальной Telegram/account истории. Restore DB после проверки остановлена, data/volume/dump сохранены, ничего не перетиралось.
- Существующие четыре Nextcloud containers сохранили IDs/images/running state; пять legacy services active; read-only Nextcloud HTTP `/status.php` подтвердил installed=true/maintenance=false/needsDbUpgrade=false. На первом этапе HTTPS отдельно не проверялся; **в финальном Serve этапе existing root trusted-TLS проверен до/после PASS**. Calendar payload/user data не читались. Не выполнялись upgrade/reboot/usermod/ручные firewall/default routes/посторонние Tailscale edits; только разрешённый own Serve path.

### Найденная и исправленная infra ошибка

Первый migrate container не стартовал: YAML flow-list `tmpfs` был некавычен, запятые разбились на отдельные mount entries (`invalid mount path`). DB/proxy уже работали; backend/JDBC ещё не запускался, PG tables0. Исправлен **только own** Compose quoting, перед обновлением сохранён root-only timestamp infra tar backup. Добавлен regression test абсолютных tmpfs paths. Повторный real migrate прошёл. Local PyYAML syntax сама по себе это semantic failure не обнаруживала — runtime failure не скрыт.

## API blocker из предыдущего checkpoint — исправлен и проверен

Изначальный backend требовал BOT_USERNAME и не мог честно стартовать API до BotFather. Developer предоставил исправленный installDist и отчёт clean52/52 PASS. Sysadmin прочитал обновлённый runtime contract/source; backend source не менял. Теперь `TELEGRAM_AUTH_ENABLED=false`, BOT_USERNAME в API/migrate отсутствует, token не монтируется в API. Реального/фиктивного бота для этого не создавали.

Перед обновлением: timestamp root-only infra archive, runtime metadata backup и новый consistent own-DB dump. Новый distribution и allowlisted `backend/src/main`/build metadata скопированы для audit/rebuild без tests/reports/cache/secrets; source не входит в Docker build context (runtime-only allowlist). Source V001 checksum сравнен с реальной DB, **не изменён**. Новый backend content image ID: `sha256:833f8ee73c8c01b6d2296a68cf920c47b307b9f25ace46d76e892173dff0b262` (предыдущий image сохранён). Metadata updated root0600, images/host packages больше не обновлялись.

Реальная приёмка новой API с PostgreSQL17.11:

- `/health/live`, `/health/ready` →200, Docker native healthcheck healthy.
- `/v1/me`, `/v1/sync/pull`, `/v1/friends` без credentials →401 `UNAUTHENTICATED`.
- POST `/v1/auth/telegram/challenges`: синтетический valid UUID/deviceLabel и случайный valid-format S256 challenge, без личных данных/реального login. Ответ503 `TELEGRAM_NOT_CONFIGURED`, нет telegramStartUrl/t.me/accessToken/refreshToken. Account/challenge/session counts **не изменились**; SQL rate-limit metadata от тестового запроса допускается.
- Только loopback18080, подтверждены actual HTTP и exact Docker host port binding127.0.0.1; API token mount отсутствует, bot container не создан.
- Controlled restart **только API** → ready200/JDBC reconnect PASS; schema version/checksum и identity counts сохранены.
- После запуска legacy container IDs/images/running state/services неизменны, safe Nextcloud HTTP status PASS. Network-fault/isolated restore повторно не выполнялись: SQL migration прежняя, предыдущая restore-проверка остаётся применимой к schema, но не является новым app-level restore test.

Две infra-особенности не скрыты: initial source-check сравнивал hex digest с backend Base64URL digest и остановил refresh **до schema/image changes**; encoding исправлен по Wire.kt, real checksum comparison PASS. Затем API native healthy, но host TCP18080 refused на единственной internal bridge: добавлен **только API** own ingress bridge с loopback-only publication; после собственного infra backup/recreate actual HTTP PASS. Никаких ручных firewall/default-route/Serve/Nextcloud изменений.

Финальный strict preflight также обнаружил Docker volume copy-up mode1777 на **только top-level own** PostgreSQL backing directories (uid70, enclosing root0700, actual PGDATA/pgdata private). До минимального исправления записан root0600 snapshot permissions; только эти два own top-level paths установлены0700, без recursive chown/chmod/данных/остановки DB. В Compose добавлен volume `nocopy: true` для будущей инициализации, чтобы image directory mode не перезаписывал подготовленные права. Финальный private-path/resource-ownership/Compose preflight **PASS**, API/PG healthy, legacy checks PASS. Telegram token absent подтверждён только existence check, getMe/activation не вызывались.

## Token после BotFather

Owner создаёт новый bot/username через BotFather самостоятельно. Не присылать token в чат/Git. Подготовлен `install_bot_token.py`, dry-run default, exclusive create, hidden TTY getpass, owner10001:10001/0600 под root0700, без Telegram requests/автозапуска bot. После reviewed root-owned staging на server:

```sh
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/install_bot_token.py --execute'
```

Ввести sudo authentication и затем новый BotFather token в скрытые prompts. Token не попадает в argv/history. Уже существующий файл не перезаписывается. После установки владельцу/root отдельно проверить BOT_USERNAME metadata, включить ровно один worker profile, затем реальные getMe/getUpdates/poll/reconnect/confirmation checks. Пока token отсутствует — worker **не running/healthy**.

Подготовлен безопасный **не запускавшийся** optional `configure_bot.py`: только после token install отдельный --execute создаёт собственный labelled временный consumer, выполняет getMe через SOCKS remote DNS/TLS, валидирует is_bot/username, сохраняет **реальный** BOT_USERNAME в server-only `/etc/chrona/telegram.env` root0600 без вывода username/token/Telegram JSON. Raw token URL/errors не логируются. Existing metadata не перезаписывается; consumer cleanup только своего container. Default dry-run не читает token и не делает network. Backend сам getMe/identity discovery не делает — это отдельный infra verification helper.

Будущий owner workflow, **сейчас не выполнен**:

```sh
# 1. Владелец создаёт нового бота в BotFather; token — только hidden prompt.
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/install_bot_token.py --execute'
# 2. Optional safe getMe: private username metadata; не запускает worker/auth.
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/configure_bot.py --execute'
# 3. После успешной проверки — ровно один worker, затем enabled API.
ssh -t server 'sudo -- python3 /opt/chrona/infra/scripts/ops.py enable-telegram --execute --approved'
```

Последняя команда сохраняет runtime metadata backup, включает true, ждёт native worker readiness (реальный polling), только потом пересоздаёт API с enabled auth. При failure возвращает прежнюю metadata, останавливает только собственный worker и возвращает API config. Этот activation path **локально проверен синтаксически/dry-run, но не runtime**, пока token отсутствует. Token/username values не в Git; env example только false/defaults. Реальное app→Telegram→app подтверждение обязательно отдельной приёмкой.

## Финальный checkpoint74 / private HTTPS phone origin

Root явно разрешил **только собственный `/chrona`** на существующем Tailscale Serve443 при сохранении всех старых mappings. До изменений прочитан обновлённый developer checkpoint74/74 и runtime example: API/disabled-auth/SQL контракт прежний; nonempty activity-view теперь возвращает200 с ComputedUnavailable для нерешённых вычислений вместо blanket409. Это source/test результат developer; sysadmin **не создавал production account/data fixtures**, поэтому authenticated nonempty view на livePG отдельно не тестировался.

Сохранены owninfra/runtime и consistent DB backup, staged новый installDist+allowlisted source; SQL V001 checksum сверён с livePG и не изменён. Обновлён **только API** с --no-deps (DB/proxy/legacy не пересоздавались), новый image ID указан выше. Повторно PASS: realPG migration/native health, loopback health200, unauthenticated401, authbegin503/noURL/noidentity, APIrestart/JDBC/same migration, legacyIDs/images/status/services/Nextcloud status. Worker off/token absent. Source backend/Android не изменялись.

### Serve baseline и применение

- Санитизированный baseline: один existing private HTTPS443 endpoint, существующий path `/`, `/chrona` отсутствовал, Funnel=false. Standard CLI --set-path/--https поддержаны. Nextcloud `/status.php` **до** изменения прошёл настоящий HTTPS/CA/hostname validation, installed=true/maintenance=false/noDBupgrade.
- Actual Serve JSON сохранён **root0600** в `/var/backups/chrona/serve-before-*.json`; дополнительно root0600 `/etc/chrona/serve-change.json` хранит private endpoint/точный backup path. Auth/private-key/token fields и URL credentials/query перед backup отвергаются. Tailscale auth/cert private keys не читались. Private hostname нигде в repo/run не записан.
- Выполнен только standard CLI `tailscale serve --bg --https=443 --set-path=/chrona http://127.0.0.1:18080`. **Без reset/set-config/Funnel/publicinternet/firewall/DNS/default-route**. Все исходные mappings сравнивались структурно до/после удаления только собственного handler; false/null Funnel serialization допускается как одна tailnet-only policy, true не допускается.
- Реальная trusted-CA HTTPS приёмка **через prefix**, не предположение rewrite: `/chrona/health/ready`200 ready; `/chrona/v1/me`401 UNAUTHENTICATED; valid-format synthetic POST `/chrona/v1/auth/telegram/challenges`503 TELEGRAM_NOT_CONFIGURED, без t.me/login/access/refresh, account/challenge/session counts не изменились. Root Nextcloud `/status.php` после изменения с тем же TLS и version/maintenance checks PASS. Повторный serve-check PASS.
- Отдельный публичный ingress не создан: endpoint только tailnet, Funnel=false. Existing Nextcloudroot/прочие mappings сохранены. Path-specific rollback helper подготовлен (`serve.py rollback --execute`) и удаляет только `/chrona` стандартной CLI, сверяет mapping baseline; full Serve reset/rewrite не применяется. **Rollback не понадобился и runtime не вызывался**, prefix rewrite прошёл.

### Получение origin и настройка телефона

Origin хранится **только на сервере**, `/etc/chrona/client-origin`, root0600 под root0700; содержит полный HTTPS base URL с `/chrona/`. Адрес не вшит в APK/Git/run. Владелец может показать его себе в терминале:

```sh
ssh -t server 'sudo -- cat /etc/chrona/client-origin'
```

Полученное значение внести в HTTPS base URL setting Chrona. Телефон должен быть подключён к нужному tailnet и иметь разрешённый policy доступ к серверу; это **не публичный API**, друг без Tailscale доступа не получает. Проверка CA/rewrite выполнена с сервера, **не** подменяет реальный network/UI тест на физическом телефоне. Android source/baseurlsetting/PKCE/realclient/sync/friends интегрирует root/developer, sysadmin их не менял. Пока нет нового owner-бота, login закономерно503; full auth/message/sync/friend E2E не заявлены готовыми.

## Проверки и незавершённое

Локальный `python3 -B infra/scripts/validate.py`: **30/30 tests PASS**, AST/PyYAML/security checks PASS; local Compose plugin отсутствует → local config SKIP, **на сервере Compose config реально PASS**. Help/dry-runs, включая Serve/bot configure/activation defaults, не открывают сеть/credentials; deployment.py делает approved actions только с --execute/private stdin. Реальный HTTPS prefix/Nextcloud TLS smoke также PASS, отдельно от unit tests.

**Ещё не выполнены:** owner BotFather/getMe/getUpdates/poll/confirmation/retry/durable delivery checks; phone-tailnet/UI connection; authenticated nonempty view и app-level recovery с реальными users; reboot recovery; публичный non-tailnet ingress; off-host backup/retention/encryption. Worker не существует как running container. PG/API sync/auth/friends E2E не заявляются готовыми. Private tailnet HTTPS ingress `/chrona` уже реально настроен/проверен, не путать его с публичным доступом.

Следующий шаг: owner BotFather → hidden token → verified private username → controlled worker/API auth activation и реальные Telegram E2E; параллельно owner retrieval private origin → phone tailnet/baseurl smoke. Неразрушительный rollback текущего root80: вернуть предыдущий checkpoint74 image `a06e…` из runtime backup и пересоздать **только API --no-deps**, schema unchanged; DB/proxy/data/backups и existingServe сохранить. Rollback не выполнялся. Если отдельно требуется убрать ingress — только свой `/chrona` через path-specific rollback, не root/прочие mappings. Proxy reusable: не останавливать без consumer review. Никаких down-v/prune/удаления чужих сетей. Destination/retention/off-host encryption unresolved.

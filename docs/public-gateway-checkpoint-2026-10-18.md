# Standalone Public Gateway + Chrona0.3.1

2026-10-18. Пользователь прямо поручил Funnel **отдельным reusable инструментом**, Chrona — первый consumer. Root распараллелил sysadmin/developer. Это ограниченный публичный пилот, не production security/SLA guarantee.

## Независимость

Source **`/home/niki/public-gateway`**, runtime **`/opt/public-gateway`**, private policy/definitions/registry/state `/etc/public-gateway`, backups `/var/backups/public-gateway`. Standalone Python3/stdlib/native Tailscale handlers, без app/SQL/bot/VPN imports. Новый Git/repository не создавался; source в home и на сервере, не внутри Chrona. Durable copy11files,10nonREADME SHA совпали со staging; README русский,14local tests PASS.

CLI register/validate/plan/apply/status/remove/origin/export-origin — dry-run по умолчанию, root0700/0600, loopback upstream allowlist, unique root-owner/explicit delegated prefixes. Не присваивает foreign/foreground/занятыйпорт/unknownhandler. ETag/CAS diff строгоown8443, backupдоизменения. Removeодногонеудаляетдругих; lastremoveтолько8443, неprivate443. NativeLocalAPIversion-sensitive, reviewedTailscale1.102.5;upgradeтребуетreview. Providerнеобещаетarbitrarydomains/ports/bandwidth/SLA.

В apprepo только [definition](../infra/public-consumer.json), [integration](../infra/public-gateway-integration.md) иprivateguard/tests. GenericPythonSourceнеимпортироватьвприложение. Дляследующегосервисаrootразрешаетupstream,root-ownerделегируетнепересекающийсяprefix, затемnewconsumerregister/validate/plan/apply. Двухмаршрутныйremoveпроверенlocalfake,второйреальныйсервиснепубликовался. Sharedhostname/port — общийweborigin; pathprefixнеbrowser sandbox.

## Реальные проверки сервера

- OnlyFunnel8443/rootAPI; private443/Nextcloudroot+/chrona иothermappings сохранены. NoFunnel443/reset/newproxycontainer/package/router/firewall/globalVPN/manualDNS edits. Xray10808outboundдругойинструмент, неingress. API/bot/PG/proxy healthy,непересоздавались.
- External черезexistingSOCKS/publicDoH: DNSglobalIPs, TLS/SNI/CAconnectionименноpublicrelayIPне100tailnet, health200/protected401. Nextcloudstatus/login/DAVна8443→404, Hostsubstitutionнеоткрываетprivate443, внешний443отклоняетTLS. ВнутреннийNextcloudTLSиprivateAPIpathPASS.
- NativeinitialCLI+actualidempotentETagCASapplyPASS. Generic14local/server, appguards34PASS. Actualsource/registry/Serve/originbackupsсохранены.
- Одинпозднийexternalrepeat завершилсяошибкойнеустановленнойпричины;stage/class/statusdiagnosticsдобавленыбезURL/body/credentials. СледующийполныйrepeatPASSбезизмененияServe/рестартов. ПовторныйуспехнеSLA.
- Canonical `/etc/chrona/client-origin` экспортированпослеexternalPASSсoldbackup/явнойсменойscope: **public8443rootбез/chrona/**. Literalhostname/originнеsource/репо. НаphonesTailscaleнеобязателен; nativeдостижимостьпокане проверена.

## Client fixes и установка

ОбщийstrictHTTPSbaseURLguardдляUI/core/vault/transportсroot/customport/safeprefix,безcredentials/query/fragment/encodingtraversal/redirect/TLSbypass. Prefixдобавляетсяровнораз; сменаtargetсбрасываетsession/pending, не переноситdata/accountмолча. One-shotRoomscopeфиксируетexactopIDs/target/account/profile;doubletap/retry/restartнерасширяютsnapshot, newrecordsждутновогосогласия. ACKrevision/frozenbody/consentsuccessатомарны, rawнеудаляется, dependencyвнеснимкаfailclosed. Schema3/MIGRATION2_3сохраняет1/2JSON/raw/outbox/frozen,выключаетlegacyunboundedflagсreconfirm,нетdestructivefallback. [Fix details](client-fixes-2026-10-18.md).

Full main/test APK/unit/lint PASS: **125Androidunit(117Chrona+8cachedunchangedspike),SQLite5,lint0err17warn**. Backend80sourceнеизменён/непереобъявленновымпрогоном. Code3/0.3.1-fixinstalled-r2→3безuninstall/clear/downgrade, ownlaunch/resumedPASS. История/privateDBнечиталасьдлядоказательствасохранностивсехзаписей.

## Phone probe

ОдинPublicConnectionSmokeTestcompiledдляrealMainActivity/безfixtures/privateDB/vault/profileinspection. RuntimeURLarg, DNSpublicsanity, **anonymousGET/v1/me→401**, ownUIConfigureServer/«Сервернастроен»/loginbuttonenabled, **безнажатияlogin/sync/messages**. Ошибкиsanitizeбезtree/URL/body. ПослеAwake/unlockedtestвременносохраняетсвойforegroundwindowkeep-screen-on/restorefinally, безglobaltimeout/wakeup/unlock/VPNchanges.

Desktoppreflightнеподтвердилunlocked/interactivescreen: **OWNER_UNLOCK_REQUIRED**, testAPKнеустановлен, instrumentationне запускалась, **0device tests**.14bodiescompiled(13UI+1native), не14ran. Network/configsave/nativeKeystorePASSпоканет. Послеownerunlock/Tailscaleoffrootзапуститровнооднуclass-пробу; потомownerсамначнёт/подтвердитgeneratedTelegramlogin. [Probe](phone-public-smoke-2026-10-18.md).

Неудалятьapp/DB/неповторятьtokeninstall: worker/authужеактивны. СтарыйboundtargetможетдатьACCOUNT_BINDING_CONFLICT—требуетсяявныйreview,неclear-data/automerge. Остальныеyear/streak/coverage/avatar/invites/defaults/release иполнаяphone/TG/sharingE2Eприёмкаостаютсяв[integration](integration-2026-10-18.md).

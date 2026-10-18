package by.bebuliby.chrona.backend

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

data class RuntimeConfig(val secretsPath: Path, val dbUrl: String, val dbUser: String, val botUsername: String?,
                         val bind: String, val port: Int, val proxy: String?, val telegramAuthEnabled: Boolean) {
    companion object {
        fun from(env: Map<String,String>, mode: String): RuntimeConfig {
            require(mode in setOf("api","bot","migrate")) { "MODE_REQUIRED" }
            require(env.keys.none { it in setOf("BOT_TOKEN","TELEGRAM_TOKEN","DB_PASSWORD","TELEGRAM_BOT_TOKEN") }) { "SECRET_VALUES_IN_ENV_FORBIDDEN" }
            val path = Path.of(env["SECRETS_PATH"] ?: error("SECRETS_PATH_REQUIRED"))
            require(path.isAbsolute) { "ABSOLUTE_SECRETS_PATH_REQUIRED" }
            val url = env["DB_URL"] ?: error("DB_URL_REQUIRED")
            require(url.startsWith("jdbc:postgresql:") && !url.contains(Regex("(?i)(password|sslpassword|user)=|://[^/]*@"))) { "POSTGRES_URL_WITHOUT_CREDENTIALS_REQUIRED" }
            val bind = env["BIND_ADDRESS"] ?: "127.0.0.1"
            val port = (env["PORT"] ?: if (mode == "bot") "8081" else "8080").toInt()
            require(port in 1024..65535)
            val proxy = env["TELEGRAM_PROXY"]
            if (mode == "bot") require(!proxy.isNullOrBlank()) { "TELEGRAM_PROXY_REQUIRED" }
            val botUsername = env["BOT_USERNAME"]?.trim()?.takeIf { it.isNotEmpty() }
            // A missing bot must not prevent API/SQL startup. Migration never depends on Telegram.
            val authEnabled = if (mode == "migrate") false else env["TELEGRAM_AUTH_ENABLED"]?.let {
                it.toBooleanStrictOrNull() ?: error("TELEGRAM_AUTH_ENABLED_MUST_BE_TRUE_OR_FALSE")
            } ?: (botUsername != null)
            if (mode == "bot" || authEnabled)
                require(botUsername != null && botUsername.matches(Regex("[A-Za-z0-9_]{5,32}"))) { "VALID_BOT_USERNAME_REQUIRED" }
            return RuntimeConfig(path,url,env["DB_USER"] ?: "chrona",botUsername,bind,port,proxy,authEnabled)
        }
    }
    fun readSecret(name: String): String {
        val file = secretsPath.resolve(name)
        require(Files.size(file) in 1..4096) { "SECRET_FILE_SIZE_INVALID" }
        return Files.readString(file).trim().also { require(it.isNotEmpty()) { "EMPTY_SECRET_FILE" } }
    }
}

fun main(args: Array<String>) {
    // Do not print exception messages: driver/HTTP exceptions can contain credentials/URLs.
    try { run(args) } catch (_: Throwable) { System.err.println("CHRONA_START_OR_RUNTIME_FAILED (details intentionally redacted)"); exitProcess(1) }
}
private fun run(args: Array<String>) {
    require(args.size == 1) { "ONE_MODE_ARGUMENT_REQUIRED" }
    val mode = args.single()
    if (mode == "healthcheck") {
        val port = (System.getenv("PORT") ?: "8080").toInt()
        require(port in 1024..65535)
        val connection = java.net.URI("http://127.0.0.1:$port/health/ready").toURL().openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 2000; connection.readTimeout = 2000
            connection.instanceFollowRedirects = false
            if (connection.responseCode != 200) exitProcess(1)
        } finally { connection.disconnect() }
        return
    }
    val config = RuntimeConfig.from(System.getenv(),mode)
    val db = Database(config.dbUrl,config.dbUser,config.readSecret("db-password"))
    db.migrate()
    if (mode == "migrate") return
    val b = Backend(Identity(db,Clock.systemUTC(),config.botUsername,config.telegramAuthEnabled))
    val worker = if (mode == "bot") BotWorker(b.identity,b.social,
        SocksTelegramTransport.production(config.readSecret("telegram-token"),config.proxy!!)) else null
    if (worker != null) {
        check(worker.acquire()) { "BOT_LEASE_BUSY" }
        val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r,"chrona-watchdog").apply { isDaemon = true } }
        watchdog.scheduleAtFixedRate({ if (worker.stalled()) Runtime.getRuntime().halt(70) },10,10,TimeUnit.SECONDS)
        Thread({
            try {
                while (!Thread.currentThread().isInterrupted) {
                    worker.progress()
                    worker.processInbox()
                    repeat(10) { worker.deliverOne() }
                    var delay = worker.pollOnce()
                    while (delay > 0) {
                        val step = minOf(delay,5000)
                        Thread.sleep(step); worker.progress(); delay -= step
                    }
                }
            } catch (_: Throwable) { System.err.println("CHRONA_WORKER_STOPPED"); exitProcess(70) }
        },"chrona-bot").start()
        Runtime.getRuntime().addShutdownHook(Thread { try { worker.release() } catch (_: Exception) { } })
    }
    embeddedServer(Netty,host=config.bind,port=config.port) { chrona(b,mode,worker) }.start(wait=true)
}

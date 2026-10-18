package by.bebuliby.chrona.backend

import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class Database(private val url: String, private val user: String = "", private val password: String = "") {
    private fun connect(): Connection = if (url.startsWith("jdbc:postgresql:")) {
        DriverManager.getConnection(url, java.util.Properties().apply {
            setProperty("user", user); setProperty("password", password)
            setProperty("connectTimeout", "5"); setProperty("socketTimeout", "10"); setProperty("tcpKeepAlive", "true")
        })
    } else DriverManager.getConnection(url, user, password)

    fun migrate() {
        connect().use { c ->
            c.autoCommit = false
            try {
                // Serialize first startup across api/bot processes in PostgreSQL.
                if (c.metaData.databaseProductName == "PostgreSQL") c.query("SELECT pg_advisory_xact_lock(184731901)") { true }
                c.exec("CREATE TABLE IF NOT EXISTS schema_migrations (version INTEGER PRIMARY KEY, checksum VARCHAR(64) NOT NULL)")
                val sql = requireNotNull(javaClass.getResourceAsStream("/db/V001__foundation.sql")).bufferedReader().use { it.readText() }
                val checksum = hash(sql)
                val stored = c.query("SELECT checksum FROM schema_migrations WHERE version=1") { it.getString(1) }.firstOrNull()
                if (stored == null) {
                    sql.split(';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { c.exec(it) }
                    c.exec("INSERT INTO schema_migrations VALUES (1, ?)", checksum)
                } else check(stored == checksum) { "MIGRATION_CHECKSUM_MISMATCH" }
                c.commit()
            } catch (e: Exception) { c.rollback(); throw e }
        }
    }

    // Deliberately coarse DB-backed serialization for the small v1 monolith.
    // All business transactions share this lock across API AND bot processes.
    // No external IO inside a transaction. Replace only with equivalent concurrency tests.
    fun <T> tx(block: (Connection) -> T): T = connect().use { c ->
        c.autoCommit = false
        try {
            c.query("SELECT revision FROM tx_guard WHERE id=1 FOR UPDATE") { it.getLong(1) }
            val result = block(c)
            c.commit()
            result
        } catch (e: Exception) { c.rollback(); throw e }
    }
    fun ready(): Boolean = try { tx { it.query("SELECT version FROM schema_migrations") { r -> r.getInt(1) }.contains(1) } } catch (_: Exception) { false }
}

fun Connection.exec(sql: String, vararg args: Any?): Int = prepareStatement(sql).use { s ->
    s.queryTimeout = 10
    args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
    s.executeUpdate()
}
fun <T> Connection.query(sql: String, vararg args: Any?, read: (ResultSet) -> T): List<T> = prepareStatement(sql).use { s ->
    s.queryTimeout = 10
    args.forEachIndexed { i, a -> s.setObject(i + 1, a) }
    s.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
}
fun Connection.revision(): String {
    exec("UPDATE tx_guard SET revision=revision+1 WHERE id=1")
    return query("SELECT revision FROM tx_guard WHERE id=1") { it.getLong(1).toString() }.single()
}
fun Connection.rate(bucket: String, now: Long, limit: Int, window: Long = 60_000) {
    val current = query("SELECT window_ms, hits FROM rate_limits WHERE bucket=?", bucket) { it.getLong(1) to it.getInt(2) }.firstOrNull()
    if (current == null) exec("INSERT INTO rate_limits VALUES (?, ?, 1)", bucket, now)
    else if (now - current.first >= window) exec("UPDATE rate_limits SET window_ms=?, hits=1 WHERE bucket=?", now, bucket)
    else {
        requireWire(current.second < limit, "RATE_LIMITED", 429)
        exec("UPDATE rate_limits SET hits=hits+1 WHERE bucket=?", bucket)
    }
}

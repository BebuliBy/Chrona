package by.bebuliby.chrona.backend

import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.*

class TransportTest {
    @Test fun `application bytecode target is Java 17 and watchdog uses monotonic elapsed time`() {
        val stream = javaClass.getResourceAsStream("/by/bebuliby/chrona/backend/MainKt.class")!!
        java.io.DataInputStream(stream).use { input ->
            assertEquals(0xCAFEBABE.toInt(),input.readInt()); input.readUnsignedShort()
            assertEquals(61,input.readUnsignedShort())
        }
        val f = Fixture(); val worker = BotWorker(f.identity,f.social,FakeTelegram()) { 0 }
        assertFalse(worker.stalled(worker.lastProgressNanos + 59_000_000_000L))
        assertTrue(worker.stalled(worker.lastProgressNanos + 61_000_000_000L))
        f.clock.advance(-86400_000L)
        assertTrue(worker.stalled(worker.lastProgressNanos + 61_000_000_000L))
    }
    @Test fun `actual OkHttp SOCKS handshake sends unresolved remote DNS hostname via fake proxy`() {
        val server = ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))
        val seen = CompletableFuture<String>()
        val worker = thread(isDaemon=true) {
            try {
                server.use { s -> s.accept().use { sock ->
                    sock.soTimeout = 5000
                    val input = sock.getInputStream(); val output = sock.getOutputStream()
                    check(input.read() == 5); val methods = input.read(); check(input.readNBytes(methods).size == methods)
                    output.write(byteArrayOf(5,0)); output.flush()
                    check(input.read() == 5); check(input.read() == 1); check(input.read() == 0)
                    check(input.read() == 3) { "Expected DOMAIN ATYP, not local DNS IP" }
                    val nameLength = input.read(); val name = input.readNBytes(nameLength).toString(Charsets.US_ASCII)
                    val port = input.read() * 256 + input.read(); check(port == 80)
                    seen.complete(name)
                    output.write(byteArrayOf(5,0,0,1,127,0,0,1,0,80)); output.flush()
                    var headers = ""
                    while (!headers.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); headers += b.toChar(); check(headers.length < 8192) }
                    val length = headers.lines().first { it.startsWith("Content-Length:",true) }.substringAfter(':').trim().toInt()
                    check(input.readNBytes(length).size == length)
                    val payload = "{\"ok\":true,\"result\":[]}"
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${payload.length}\r\nConnection: close\r\n\r\n" + payload).toByteArray())
                    output.flush()
                } }
            } catch (e: Throwable) { seen.completeExceptionally(e) }
        }
        try {
            val response = SocksTelegramTransport.fakeProxyTest(server.localPort).call("getUpdates",obj("timeout" to num(5)))
            assertEquals("[]",response.toString()); assertEquals("telegram.invalid",seen.get(5,TimeUnit.SECONDS))
        } finally { server.close(); worker.join(5000) }
    }
    @Test fun `proxy required no direct fallback on closed proxy and errors contain no token URLs`() {
        val fake = "00000:FAKE_TEST_TOKEN_NOT_A_REAL_SECRET"
        for (url in listOf("http://127.0.0.1:10808","socks5://127.0.0.1:10808","socks5h://user:pass@127.0.0.1:10808","socks5h://127.0.0.1:10808/evil"))
            assertFailsWith<IllegalArgumentException> { SocksTelegramTransport.production(fake,url) }
        val port = ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val f = assertFailsWith<TelegramFailure> { SocksTelegramTransport.production(fake,"socks5h://127.0.0.1:$port").call("getUpdates",obj("timeout" to num(5))) }
        assertFalse(f.toString().contains(fake)); assertFalse(f.toString().contains("https://")); assertNull(f.cause)
    }
    @Test fun `configuration contains file paths not secret env values and no H2 production fallback`() {
        val env = mapOf("SECRETS_PATH" to "/run/secrets/chrona","DB_URL" to "jdbc:postgresql://db:5432/chrona","BOT_USERNAME" to "ChronaFakeTestBot","TELEGRAM_PROXY" to "socks5h://proxy:10808")
        assertEquals("127.0.0.1",RuntimeConfig.from(env,"api").bind)
        assertEquals(8081,RuntimeConfig.from(env,"bot").port)
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(env + ("BOT_TOKEN" to "FAKE_FORBIDDEN_ENV"),"bot") }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(env + ("DB_URL" to "jdbc:h2:mem:forbidden"),"api") }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(env + ("DB_URL" to "jdbc:postgresql://db:5432/chrona?password=FAKE_FORBIDDEN_ENV"),"api") }
        assertFailsWith<IllegalArgumentException> { RuntimeConfig.from(env - "TELEGRAM_PROXY","bot") }
    }
}

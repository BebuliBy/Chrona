package by.bebuliby.chrona.backend

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.io.readByteArray
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Backend(val identity: Identity) {
    val activities = Activities(identity)
    val social = Social(identity,activities)
}

private suspend fun ApplicationCall.body(): JsonObject {
    val bytes = receiveChannel().readRemaining(1_048_577).readByteArray()
    requireWire(bytes.size <= 1_048_576)
    requireWire(request.contentType().match(ContentType.Application.Json))
    return parseObject(bytes.toString(Charsets.UTF_8))
}
private suspend fun ApplicationCall.json(value: JsonElement, status: Int = 200) = respondText(canonical(value), ContentType.Application.Json, HttpStatusCode.fromValue(status))
private fun ApplicationCall.principal(b: Backend): Principal {
    val auth = request.headers[HttpHeaders.Authorization] ?: fail(401,"UNAUTHENTICATED")
    requireWire(auth.startsWith("Bearer ") && auth.length == 50, "UNAUTHENTICATED", 401)
    return b.identity.authenticate(auth.substring(7))
}
private fun ApplicationCall.key() = request.headers["Idempotency-Key"]?.let(::uuid) ?: fail(400,"VALIDATION")
private fun ApplicationCall.param(key: String) = parameters[key] ?: fail(400,"VALIDATION")

fun Application.chrona(b: Backend, mode: String = "api", worker: BotWorker? = null) {
    val authRateLimit = createRouteScopedPlugin("ChronaAuthRateLimit") {
        onCall { call -> withContext(Dispatchers.IO) {
            b.identity.db.tx { it.rate("auth:${hash(call.request.local.remoteHost)}",b.identity.now(),120) }
        } }
    }
    install(StatusPages) {
        exception<Fault> { call, f -> call.json(obj("error" to obj("code" to str(f.code), "message" to str(f.code), "requestId" to str(id()))),f.status) }
        exception<Throwable> { call, _ ->
            // No exception causes/body/headers/Telegram URLs in logs or responses.
            call.json(obj("error" to obj("code" to str("NOT_READY"), "message" to str("Request could not be completed"), "requestId" to str(id()))),503)
        }
    }
    routing {
        get("/health/live") { call.json(obj("status" to str("live"))) }
        get("/health/ready") {
            val ready = withContext(Dispatchers.IO) { if (mode == "bot") worker?.ready() == true else b.identity.db.ready() }
            call.json(obj("status" to str(if (ready) "ready" else "not_ready")),if (ready) 200 else 503)
        }
        if (mode != "api") return@routing
        route("/v1") {
            route("/auth") {
                install(authRateLimit)
                post("/telegram/challenges") {
                    val body = call.body()
                    val result = withContext(Dispatchers.IO) { b.identity.createChallenge(body,call.request.local.remoteHost) }
                    call.json(result,201)
                }
                post("/telegram/challenges/{requestId}/status") { val body = call.body(); call.json(withContext(Dispatchers.IO) { b.identity.status(call.param("requestId"),body) }) }
                post("/telegram/challenges/{requestId}/exchange") { val body = call.body(); call.json(withContext(Dispatchers.IO) { b.identity.exchange(call.param("requestId"),body) }) }
                post("/telegram/challenges/{requestId}/cancel") { val body = call.body(); call.json(withContext(Dispatchers.IO) { b.identity.cancel(call.param("requestId"),body) }) }
                post("/refresh") { val body = call.body(); call.json(withContext(Dispatchers.IO) { b.identity.refresh(body) }) }
            }
            get("/me") { call.json(withContext(Dispatchers.IO) { b.identity.me(call.principal(b)) }) }
            put("/me/discoverability") {
                val body = call.body()
                call.json(withContext(Dispatchers.IO) { b.identity.configureDiscoverability(call.principal(b),call.key(),body) })
            }
            delete("/sessions/current") { call.json(withContext(Dispatchers.IO) { b.identity.revoke(call.principal(b),call.key()) }) }
            post("/sync/push") { val body = call.body(); call.json(withContext(Dispatchers.IO) { b.activities.push(call.principal(b),call.key(),body) }) }
            get("/sync/pull") {
                val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: fail(400,"VALIDATION") } ?: 100
                call.json(withContext(Dispatchers.IO) { b.activities.pull(call.principal(b),call.request.queryParameters["cursor"],limit) })
            }
            get("/users/search") { call.json(withContext(Dispatchers.IO) { b.social.search(call.principal(b),call.request.queryParameters["q"] ?: "",call.request.queryParameters["cursor"]) }) }
            get("/friends") { call.json(withContext(Dispatchers.IO) { b.social.friends(call.principal(b)) }) }
            get("/friend-requests") { call.json(withContext(Dispatchers.IO) { b.social.requests(call.principal(b)) }) }
            post("/friend-requests") {
                val body = call.body(); body.only("targetAccountId","inviteCode")
                if (body.containsKey("inviteCode")) fail(501,"NOT_IMPLEMENTED")
                call.json(withContext(Dispatchers.IO) {
                    val p = call.principal(b)
                    b.social.mutate(p,call.key(),"friend-requests",body) { b.social.request(it,p.accountId,body.text("targetAccountId")) }
                })
            }
            post("/friend-requests/{id}/respond") {
                val body = call.body(); body.only("accept")
                call.json(withContext(Dispatchers.IO) {
                    val p = call.principal(b); val request = call.param("id")
                    b.social.mutate(p,call.key(),"friend-requests/$request/respond",body) { b.social.respond(it,p.accountId,request,body.booleanAt("accept")) }
                })
            }
            delete("/friends/{accountId}") {
                call.json(withContext(Dispatchers.IO) {
                    val p = call.principal(b); val friend = call.param("accountId")
                    b.social.mutate(p,call.key(),"friends/$friend/delete",obj()) { b.social.deleteFriend(it,p.accountId,friend) }
                })
            }
            get("/friends/{accountId}/sharing") { call.json(withContext(Dispatchers.IO) { b.social.getGrant(call.principal(b),call.param("accountId")) }) }
            put("/friends/{accountId}/sharing") {
                val body = call.body()
                call.json(withContext(Dispatchers.IO) {
                    val p = call.principal(b); val friend = call.param("accountId")
                    b.social.mutate(p,call.key(),"friends/$friend/sharing",body) { b.social.putGrant(it,p.accountId,friend,body) }
                })
            }
            get("/owners/{accountId}/activity-view") {
                val q = call.request.queryParameters
                val range = ViewRange.parse(q["fromInclusive"] ?: "",q["toExclusive"] ?: "",q["zoneId"] ?: "")
                call.json(withContext(Dispatchers.IO) { b.social.activityView(call.principal(b),call.param("accountId"),range) })
            }
            get("/sharing/defaults") { withContext(Dispatchers.IO) { call.principal(b) }; fail(409,"POLICY_UNRESOLVED") }
            put("/sharing/defaults") { withContext(Dispatchers.IO) { call.principal(b) }; call.key(); fail(409,"POLICY_UNRESOLVED") }
            post("/friend-invites") { withContext(Dispatchers.IO) { call.principal(b) }; call.key(); fail(501,"NOT_IMPLEMENTED") }
        }
        route("/v1/{rest...}") { handle { fail(501,"NOT_IMPLEMENTED") } }
    }
}

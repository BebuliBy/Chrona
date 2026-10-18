package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import kotlin.test.*

class SocialTest {
    @Test fun `discovery opt in only friend acceptance target only and no automatic grants`() {
        val f = Fixture(); val a = f.login(101).principal; val b = f.login(202).principal; val evil = f.login(303).principal
        assertTrue(f.social.search(a,"FAKE",null).arrayAt("items").isEmpty())
        assertFault("NOT_FOUND") { f.db.tx { f.social.request(it,a.accountId,b.accountId) } }
        f.db.tx { it.exec("UPDATE accounts SET discoverable=TRUE WHERE id=?",b.accountId) }
        val search = f.social.search(a,"FAKE",null)
        assertEquals(listOf(b.accountId),search.arrayAt("items").map { it.jsonObject.text("accountId") })
        assertFalse(search.toString().contains("telegram_id"))
        assertFault("VALIDATION") { f.db.tx { f.social.request(it,a.accountId,a.accountId) } }
        val request = f.db.tx { f.social.request(it,a.accountId,b.accountId) }.text("requestId")
        assertFault("FORBIDDEN") { f.db.tx { f.social.respond(it,evil.accountId,request,true) } }
        assertFault("FORBIDDEN") { f.db.tx { f.social.respond(it,a.accountId,request,true) } }
        assertFault("NOT_FOUND") { f.social.activityView(b,a.accountId,f.range()) }
        f.db.tx { f.social.respond(it,b.accountId,request,true) }
        assertEquals(1,f.social.friends(a).arrayAt("items").size)
        assertEquals(1,f.social.friends(b).arrayAt("items").size)
        assertFault("POLICY_UNRESOLVED") { f.social.activityView(b,a.accountId,f.range()) }
        assertEquals(0,f.db.tx { it.query("SELECT owner_id FROM grants") { true }.size })
    }
    @Test fun `reject is not friendship and explicit unresolved scope never grants access`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal
        f.db.tx { it.exec("UPDATE accounts SET discoverable=TRUE WHERE id=?",b.accountId) }
        val request = f.db.tx { f.social.request(it,a.accountId,b.accountId) }.text("requestId")
        f.db.tx { f.social.respond(it,b.accountId,request,false) }
        assertTrue(f.social.friends(a).arrayAt("items").isEmpty())
        assertFault("FORBIDDEN") { f.grant(a,b,listOf(f.auto(a).id)) }
        f.accept(a,b)
        val automaticId = f.auto(a).id
        assertFault("POLICY_UNRESOLVED") { f.db.tx { f.social.putGrant(it,a.accountId,b.accountId,obj(
            "habitIds" to arr(listOf(str(automaticId))),"historyScope" to obj("kind" to str("unresolved")),"expectedRevision" to JsonNull)) } }
    }
    @Test fun `ACL filters hidden names IDs raw intervals entries counts and metadata before view`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val visible = f.auto(a).id
        val hidden = f.manual(a,f.habit(title="HIDDEN_PRIVATE_ACTIVITY")).first
        f.single(a,f.op("manual_entry",f.entry(hidden.text("id"),value=str("999999"))))
        val interval = f.interval(a,visible)
        f.single(a,f.op("activity_interval",interval))
        f.grant(a,b,listOf(visible))
        val friendFacts = f.db.tx { f.social.visibleFacts(it,b.accountId,a.accountId,f.range()) }
        val ownerFacts = f.db.tx { f.social.visibleFacts(it,a.accountId,a.accountId,f.range()) }
        assertEquals(1,friendFacts.habits.size); assertEquals(2,ownerFacts.habits.size)
        assertTrue(friendFacts.entries.isEmpty()); assertEquals(1,ownerFacts.entries.size)
        assertEquals(ownerFacts.intervals,friendFacts.intervals)
        val serialized = friendFacts.toString()
        assertFalse(serialized.contains(hidden.text("id"))); assertFalse(serialized.contains("HIDDEN_PRIVATE_ACTIVITY"))
        assertFalse(serialized.contains("999999")); assertFalse(serialized.contains(a.deviceId)); assertFalse(serialized.contains(interval.text("bootSessionId")))
        assertEquals(setOf("id","habitId","startUtc","endUtc","durationMillis","quality"),friendFacts.intervals.single().keys)
        val friendView = f.social.activityView(b,a.accountId,f.range())
        val ownerView = f.social.activityView(a,a.accountId,f.range())
        assertEquals(1,friendView.arrayAt("cells").size); assertEquals(2,ownerView.arrayAt("cells").size)
        assertEquals("schedule_unresolved",friendView.objectAt("summary").objectAt("scorePercent").text("reason"))
        assertFalse(friendView.toString().contains(hidden.text("id")))
        assertEquals("partial",friendView.arrayAt("cells").single().jsonObject.objectAt("actual").text("state"))
    }
    @Test fun `temporal ACL includes only in scope intervals and fails closed on partial boundary`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val auto = f.auto(a).id
        val early = f.interval(a,auto,start="2026-10-18T01:00:00Z",end="2026-10-18T01:00:01Z")
        val late = f.interval(a,auto,start="2026-10-18T10:00:00Z",end="2026-10-18T10:00:01Z")
        f.push(a,f.op("activity_interval",early),f.op("activity_interval",late))
        val g = f.grant(a,b,listOf(auto),from="2026-10-18T09:00:00Z",to="2026-10-18T12:00:00Z")
        val facts = f.db.tx { f.social.visibleFacts(it,b.accountId,a.accountId,f.range()) }
        assertEquals(listOf(late.text("id")),facts.intervals.map { it.text("id") })
        f.grant(a,b,listOf(auto),from="2026-10-18T10:00:00.500Z",to="2026-10-18T12:00:00Z",rev=g.text("revision"))
        assertFault("POLICY_UNRESOLVED") { f.db.tx { f.social.visibleFacts(it,b.accountId,a.accountId,f.range()) } }
    }
    @Test fun `revision conflicts cross owner grant rejection and revocation are durable`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        assertFault("NOT_FOUND") { f.grant(a,b,listOf(f.auto(b).id)) }
        val g = f.grant(a,b,listOf(f.auto(a).id))
        assertFault("REVISION_CONFLICT") { f.grant(a,b,emptyList()) }
        f.grant(a,b,emptyList(),rev=g.text("revision"))
        val empty = f.social.activityView(b,a.accountId,f.range())
        assertTrue(empty.arrayAt("habits").isEmpty()); assertTrue(empty.arrayAt("intervals").isEmpty())
        assertEquals("not_planned",empty.objectAt("summary").objectAt("scorePercent").text("reason"))
        f.db.tx { f.social.deleteFriend(it,a.accountId,b.accountId) }
        assertFault("NOT_FOUND") { f.social.activityView(b,a.accountId,f.range()) }
        assertEquals(0,f.db.tx { it.query("SELECT owner_id FROM grants") { true }.size })
    }
}

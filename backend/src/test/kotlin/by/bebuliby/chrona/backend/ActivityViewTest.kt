package by.bebuliby.chrona.backend

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.*

class ActivityViewTest {
    private fun JsonObject.cell(habit: String, date: String = "2026-10-18") = arrayAt("cells").map { it.jsonObject }
        .single { it.text("habitId") == habit && it.objectAt("day").text("date") == date }
    private fun JsonObject.curve(habit: String?): JsonObject = objectAt("summary").arrayAt("curves").map { it.jsonObject }
        .single { it.optionalText("habitId") == habit }
    private fun JsonObject.value(field: String) = objectAt(field).text("value").toBigDecimal()
    private fun assertDecimal(expected: String, actual: BigDecimal) = assertEquals(0,expected.toBigDecimal().compareTo(actual))

    @Test fun `open gap DTO always includes explicit nullable end without fabricated closure`() {
        val f = Fixture(); val p = f.login().principal
        val gap = obj("id" to str(id()),"habitId" to str(f.auto(p).id),"startUtc" to str("2026-10-18T11:00:00Z"),"reason" to str("user_disabled"))
        assertEquals("accepted",f.single(p,f.op("observation_gap",gap)).text("status"))
        val dto = f.social.activityView(p,p.accountId,f.range()).arrayAt("gaps").single().jsonObject
        assertEquals(setOf("id","habitId","startUtc","endUtc","reason"),dto.keys)
        assertEquals(JsonNull,dto["endUtc"])
    }

    @Test fun `fully known granted cells aggregate without overgoal compensation or hidden automatic taint`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val first = f.manual(a).first.text("id"); val second = f.manual(a).first.text("id")
        f.push(a,f.op("goal_revision",f.goal(first,"100")),f.op("goal_revision",f.goal(second,"100")),
            f.op("manual_entry",f.entry(first,value=str("200"))),f.op("manual_entry",f.entry(second,value=str("0"))))
        f.grant(a,b,listOf(first,second))
        val view = f.social.activityView(b,a.accountId,f.range())
        assertDecimal("50",view.objectAt("summary").value("scorePercent"))
        assertEquals(2L,view.objectAt("summary").longAt("knownCells"))
        assertEquals("stars",view.cell(first).objectAt("emotion").text("value")); assertEquals("tears",view.cell(second).objectAt("emotion").text("value"))
        assertDecimal("0",view.curve(null).arrayAt("points").single().jsonObject.value("cumulative"))
        assertEquals("200",view.cell(first).objectAt("actual").text("value"))
    }

    @Test fun `wire emotions preserve exact harmful boundary and tiny deficit does not become full score`() {
        val f = Fixture(); val p = f.login().principal
        val atMost = f.manual(p,JsonObject(f.habit() + ("direction" to str("at_most")))).first.text("id")
        val least = f.manual(p).first.text("id")
        f.push(p,f.op("goal_revision",f.goal(atMost,"100")),f.op("manual_entry",f.entry(atMost,value=str("150"))),
            f.op("goal_revision",f.goal(least,"1")),f.op("manual_entry",f.entry(least,value=str("0.99999999999999999999999999999999999999999999999"))))
        val view = f.social.activityView(p,p.accountId,f.range())
        assertEquals("neutral",view.cell(atMost).objectAt("emotion").text("value")); assertDecimal("0.5",view.cell(atMost).value("score"))
        assertEquals("neutral",view.cell(least).objectAt("emotion").text("value")); assertTrue(view.cell(least).value("score") < BigDecimal.ONE)
    }

    @Test fun `nonempty owner and friend snapshots have full stable DTO and exact manual calculations`() {
        val f = Fixture(); val owner = f.login().principal; val friend = f.login(202).principal; f.accept(owner,friend)
        val h = f.manual(owner).first.text("id"); val goal = f.goal(h)
        f.push(owner,f.op("goal_revision",goal),f.op("manual_entry",f.entry(h,value=str("15.125"))))
        f.grant(owner,friend,listOf(h))
        val own = f.social.activityView(owner,owner.accountId,f.range()); val other = f.social.activityView(friend,owner.accountId,f.range())
        assertEquals(setOf("owner","range","habits","cells","summary","intervals","gaps"),other.keys)
        val cell = other.cell(h)
        assertEquals(setOf("habitId","day","planStatus","goal","actual","usagePercent","score","emotion","streak","canEditActual","canEditGoal"),cell.keys)
        assertEquals(setOf("id","title","unit","direction","collectionMode","createdAt","archivedAt","revision"),other.arrayAt("habits").single().jsonObject.keys)
        assertEquals(setOf("id","habitId","amount","requestedFrom","recordedAt","revision"),cell.objectAt("goal").keys)
        assertEquals("planned",cell.text("planStatus")); assertEquals("known",cell.objectAt("actual").text("state"))
        assertEquals("15.125",cell.objectAt("actual").text("value")); assertEquals("manual",cell.objectAt("actual").text("source"))
        assertDecimal("151.25",cell.value("usagePercent")); assertDecimal("1",cell.value("score"))
        assertEquals("big_smile",cell.objectAt("emotion").text("value"))
        assertEquals("streak_rules_unresolved",cell.objectAt("streak").text("reason"))
        assertFalse(cell.booleanAt("canEditActual")); assertFalse(cell.booleanAt("canEditGoal"))
        assertTrue(own.cell(h).booleanAt("canEditActual")); assertTrue(own.cell(h).booleanAt("canEditGoal"))
        assertEquals(JsonObject(own.cell(h) - setOf("canEditActual","canEditGoal")),JsonObject(cell - setOf("canEditActual","canEditGoal")))
        assertEquals(1L,other.objectAt("summary").longAt("plannedCells")); assertEquals(1L,other.objectAt("summary").longAt("knownCells"))
        assertDecimal("100",other.objectAt("summary").value("scorePercent"))
        assertDecimal("5.125",other.curve(h).arrayAt("points").single().jsonObject.value("cumulative"))
        assertDecimal("0.5125",other.curve(null).arrayAt("points").single().jsonObject.value("cumulative"))
        // Owner's unplanned automatic activity prevents a complete aggregate, not the manual cell.
        assertEquals("schedule_unresolved",own.objectAt("summary").objectAt("scorePercent").text("reason"))
    }

    @Test fun `partial Shorts and gaps are returned beside ready manual fields not global 409`() {
        val f = Fixture(); val p = f.login().principal; val auto = f.auto(p).id; val h = f.manual(p).first.text("id")
        val interval = f.interval(p)
        val gap = obj("id" to str(id()),"habitId" to str(auto),"startUtc" to str("2026-10-18T11:00:00Z"),"endUtc" to JsonNull,"reason" to str("permission_lost"))
        f.push(p,f.op("goal_revision",f.goal(auto,"0")),f.op("goal_revision",f.goal(h)),f.op("manual_entry",f.entry(h)),
            f.op("activity_interval",interval),f.op("observation_gap",gap))
        val view = f.social.activityView(p,p.accountId,f.range()); val shorts = view.cell(auto)
        assertEquals("partial",shorts.objectAt("actual").text("state")); assertEquals("1000",shorts.objectAt("actual").text("observedValue"))
        for (field in listOf("score","usagePercent","emotion")) assertEquals("partial_coverage",shorts.objectAt(field).text("reason"))
        assertEquals("ready",view.cell(h).objectAt("score").text("state"))
        assertEquals("partial_coverage",view.objectAt("summary").objectAt("scorePercent").text("reason"))
        assertEquals(2L,view.objectAt("summary").longAt("plannedCells")); assertEquals(1L,view.objectAt("summary").longAt("knownCells"))
        assertEquals(1,view.arrayAt("intervals").size); assertEquals(gap,view.arrayAt("gaps").single())
        assertFalse(view.toString().contains(p.deviceId)); assertFalse(view.toString().contains(interval.text("bootSessionId")))
    }

    @Test fun `zero known manual limit gives q one while unknown automatic zero limit never does`() {
        val f = Fixture(); val p = f.login().principal; val friend = f.login(202).principal; val auto = f.auto(p).id
        val h = f.manual(p,JsonObject(f.habit() + ("direction" to str("at_most")))).first.text("id")
        f.push(p,f.op("goal_revision",f.goal(h,"0")),f.op("manual_entry",f.entry(h,value=str("0"))),f.op("goal_revision",f.goal(auto,"0")))
        f.accept(p,friend); f.grant(p,friend,listOf(h))
        val view = f.social.activityView(friend,p.accountId,f.range()); val cell = view.cell(h)
        assertDecimal("1",cell.value("score")); assertEquals("zero_limit_ratio",cell.objectAt("usagePercent").text("reason"))
        assertEquals("zero_limit_emotion_unresolved",cell.objectAt("emotion").text("reason"))
        assertDecimal("0",view.curve(h).arrayAt("points").single().jsonObject.value("cumulative"))
        assertEquals("zero_limit_curve_unresolved",view.curve(null).arrayAt("points").single().jsonObject.objectAt("cumulative").text("reason"))
        assertDecimal("100",view.objectAt("summary").value("scorePercent"))
        val own = f.social.activityView(p,p.accountId,f.range())
        assertEquals("unknown",own.cell(auto).objectAt("actual").text("state")); assertEquals("unknown_actual",own.cell(auto).objectAt("score").text("reason"))
    }

    @Test fun `same day goal uses append order not UUID timestamp or inheritance across days and zones`() {
        val f = Fixture(); val p = f.login().principal; val h = f.manual(p).first.text("id")
        val first = JsonObject(f.goal(h,"10",entity="ffffffff-ffff-ffff-ffff-ffffffffffff") + ("recordedAt" to str("2026-10-18T13:00:00Z")))
        val last = JsonObject(f.goal(h,"20",entity="00000000-0000-0000-0000-000000000000") + ("recordedAt" to str("2026-10-18T11:00:00Z")))
        f.push(p,f.op("goal_revision",first),f.op("goal_revision",last),f.op("manual_entry",f.entry(h,value=str("10"))))
        val view = f.social.activityView(p,p.accountId,ViewRange.parse("2026-10-18","2026-10-20","UTC"))
        assertEquals(last.text("id"),view.cell(h).objectAt("goal").text("id")); assertDecimal("0.5",view.cell(h).value("score"))
        val tomorrow = view.cell(h,"2026-10-19")
        assertEquals(JsonNull,tomorrow["goal"]); assertEquals("unresolved",tomorrow.text("planStatus")); assertEquals("unknown",tomorrow.objectAt("actual").text("state"))
        assertFalse(view.toString().contains(first.text("id")))
        val otherZone = f.social.activityView(p,p.accountId,ViewRange.parse("2026-10-18","2026-10-19","Europe/Minsk")).cell(h)
        assertEquals(JsonNull,otherZone["goal"]); assertEquals("unknown",otherZone.objectAt("actual").text("state"))
    }

    @Test fun `seven explicit complete days produce individual plus 140 general plus 1 point 4`() {
        val f = Fixture(); var p = f.login().principal; var friend = f.login(202).principal
        val h = f.manual(p).first.text("id"); f.accept(p,friend)
        repeat(7) { index ->
            if (index > 0) { f.clock.advance(86_400_000); p = f.login().principal }
            val date = LocalDate.parse("2026-10-18").plusDays(index.toLong()).toString()
            f.push(p,f.op("goal_revision",f.goal(h,"100",date)),f.op("manual_entry",f.entry(h,value=str("120"),date=date)))
        }
        friend = f.login(202).principal
        f.grant(p,friend,listOf(h),to="2026-10-25T00:00:00Z")
        val view = f.social.activityView(friend,p.accountId,ViewRange.parse("2026-10-18","2026-10-25","UTC"))
        assertEquals(7,view.arrayAt("cells").size); assertEquals(7L,view.objectAt("summary").longAt("knownCells"))
        assertDecimal("140",view.curve(h).arrayAt("points").last().jsonObject.value("cumulative"))
        assertDecimal("1.4",view.curve(null).arrayAt("points").last().jsonObject.value("cumulative"))
        assertDecimal("100",view.objectAt("summary").value("scorePercent"))
        // This test records on each current day; it does not bypass the retained retro-write guard.
    }

    @Test fun `curve cannot resume through unknown scheduled day even when a later day is known`() {
        val f = Fixture(); var p = f.login().principal; val h = f.manual(p).first.text("id")
        f.push(p,f.op("goal_revision",f.goal(h)),f.op("manual_entry",f.entry(h)))
        f.clock.advance(2*86_400_000L); p = f.login().principal
        f.push(p,f.op("goal_revision",f.goal(h,date="2026-10-20")),f.op("manual_entry",f.entry(h,date="2026-10-20")))
        val points = f.social.activityView(p,p.accountId,ViewRange.parse("2026-10-18","2026-10-21","UTC")).curve(h).arrayAt("points")
        assertEquals("ready",points[0].jsonObject.objectAt("cumulative").text("state"))
        assertEquals("schedule_unresolved",points[1].jsonObject.objectAt("cumulative").text("reason"))
        assertEquals("schedule_unresolved",points[2].jsonObject.objectAt("cumulative").text("reason"))
    }

    @Test fun `ACL removes hidden habits IDs goals counts and contributions before all calculations`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val allowed = f.manual(a).first.text("id")
        f.push(a,f.op("goal_revision",f.goal(allowed)),f.op("manual_entry",f.entry(allowed,value=str("10"))))
        f.grant(a,b,listOf(allowed))
        val before = f.social.activityView(b,a.accountId,f.range())
        val hidden = f.manual(a,f.habit(title="HIDDEN_ACTIVITY_SENTINEL")).first.text("id"); val goal = f.goal(hidden,"999999")
        f.push(a,f.op("goal_revision",goal),f.op("manual_entry",f.entry(hidden,value=str("888888"))),f.op("activity_interval",f.interval(a)))
        val after = f.social.activityView(b,a.accountId,f.range())
        assertEquals(before,after)
        for (secret in listOf(hidden,goal.text("id"),"HIDDEN_ACTIVITY_SENTINEL","999999","888888",f.auto(a).id)) assertFalse(after.toString().contains(secret))
        assertEquals(1L,after.objectAt("summary").longAt("plannedCells")); assertEquals(2,after.objectAt("summary").arrayAt("curves").size)
    }

    @Test fun `time window includes only full authorized manual days not goals or values from outside`() {
        val f = Fixture(); var a = f.login().principal; var b = f.login(202).principal; f.accept(a,b)
        val h = f.manual(a).first.text("id"); val outside = f.goal(h,"111111")
        f.push(a,f.op("goal_revision",outside),f.op("manual_entry",f.entry(h,value=str("222222"))))
        f.clock.advance(86_400_000); a = f.login().principal; b = f.login(202).principal
        f.push(a,f.op("goal_revision",f.goal(h,date="2026-10-19")),f.op("manual_entry",f.entry(h,value=str("20"),date="2026-10-19")))
        f.grant(a,b,listOf(h),from="2026-10-19T00:00:00Z",to="2026-10-20T00:00:00Z")
        val view = f.social.activityView(b,a.accountId,ViewRange.parse("2026-10-18","2026-10-20","UTC"))
        assertEquals(1,view.arrayAt("cells").size); assertEquals("2026-10-19",view.arrayAt("cells").single().jsonObject.objectAt("day").text("date"))
        for (privateValue in listOf(outside.text("id"),"111111","222222")) assertFalse(view.toString().contains(privateValue))
        assertEquals(1,view.curve(null).arrayAt("points").size); assertEquals(1L,view.objectAt("summary").longAt("knownCells"))
    }

    @Test fun `part day grants show exact allowed interval but not whole day manual facts or goal`() {
        val f = Fixture(); val a = f.login().principal; val b = f.login(202).principal; f.accept(a,b)
        val auto = f.auto(a).id; val h = f.manual(a).first.text("id"); val goal = f.goal(h,"54321")
        f.push(a,f.op("goal_revision",goal),f.op("manual_entry",f.entry(h,value=str("98765"))),f.op("activity_interval",f.interval(a)))
        f.grant(a,b,listOf(h,auto),from="2026-10-18T09:00:00Z",to="2026-10-18T12:00:00Z")
        val view = f.social.activityView(b,a.accountId,f.range())
        assertEquals(1,view.arrayAt("intervals").size); assertEquals("partial",view.cell(auto).objectAt("actual").text("state"))
        assertEquals("sharing_policy_unresolved",view.cell(h).objectAt("actual").text("reason")); assertEquals(JsonNull,view.cell(h)["goal"])
        for (privateValue in listOf(goal.text("id"),"54321","98765")) assertFalse(view.toString().contains(privateValue))
        assertEquals(0L,view.objectAt("summary").longAt("plannedCells")); assertEquals("schedule_unresolved",view.objectAt("summary").objectAt("scorePercent").text("reason"))
    }

    @Test fun `segment evidence is partial zero not known zero and multidevice raw is not summed`() {
        val f = Fixture(); val a = f.login().principal; val auto = f.auto(a).id
        val raw = f.interval(a)
        val segment = JsonObject((raw - setOf("durationMillis","endReason")) + ("observation" to str("not_shorts")))
        f.push(a,f.op("goal_revision",f.goal(auto,"0")),f.op("observation_segment",segment))
        val partial = f.social.activityView(a,a.accountId,f.range()).cell(auto)
        assertEquals("partial",partial.objectAt("actual").text("state")); assertEquals("0",partial.objectAt("actual").text("observedValue"))
        assertEquals("partial_coverage",partial.objectAt("score").text("reason"))
        val otherDevice = f.login(101).principal
        f.push(otherDevice,f.op("activity_interval",f.interval(otherDevice)))
        val view = f.social.activityView(a,a.accountId,f.range())
        assertEquals("multi_device_unresolved",view.cell(auto).objectAt("actual").text("reason"))
        assertEquals(1,view.arrayAt("intervals").size); assertFalse(view.toString().contains(otherDevice.deviceId))
    }

    @Test fun `clock discontinuity is an exact sanitized timeline record not an invented duration sum`() {
        val f = Fixture(); val p = f.login().principal
        val raw = JsonObject(f.interval(p,start="2026-10-18T11:00:00Z",end="2026-10-18T10:00:00Z") + ("quality" to str("clock_discontinuity")))
        f.single(p,f.op("activity_interval",raw))
        val view = f.social.activityView(p,p.accountId,f.range())
        assertEquals("clock_discontinuity",view.arrayAt("intervals").single().jsonObject.text("quality"))
        assertEquals("day_policy_unresolved",view.cell(f.auto(p).id).objectAt("actual").text("reason"))
    }

    @Test fun `archived history is returned read only and missing manual value is not zero`() {
        val f = Fixture(); val p = f.login().principal; val (habit,rev) = f.manual(p); val h = habit.text("id")
        f.push(p,f.op("goal_revision",f.goal(h)),f.op("habit",JsonObject(habit + ("archivedAt" to str("2026-10-18T10:00:00Z"))),base=rev))
        val cell = f.social.activityView(p,p.accountId,f.range()).cell(h)
        assertEquals("unknown",cell.objectAt("actual").text("state")); assertFalse(cell.objectAt("actual").containsKey("value"))
        assertFalse(cell.booleanAt("canEditActual")); assertFalse(cell.booleanAt("canEditGoal"))
        assertEquals("unknown_actual",cell.objectAt("score").text("reason"))
    }
}

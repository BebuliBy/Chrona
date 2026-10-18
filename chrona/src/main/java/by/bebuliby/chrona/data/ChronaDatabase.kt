package by.bebuliby.chrona.data

import androidx.room.*
import by.bebuliby.chrona.domain.*
import java.time.Instant
import java.time.LocalDate

class RawConverters {
    @TypeConverter fun decimal(value: String?): Decimal? = value?.toBigDecimal()
    @TypeConverter fun decimal(value: Decimal?): String? = value?.toPlainString()
    @TypeConverter fun instant(value: String?): Instant? = value?.let(Instant::parse)
    @TypeConverter fun instant(value: Instant?): String? = value?.toString()
    @TypeConverter fun day(value: String?): DayKey? = value?.split('|', limit = 2)?.let {
        DayKey(LocalDate.parse(it[0]), it[1])
    }
    @TypeConverter fun day(value: DayKey?): String? = value?.let { "${it.date}|${it.zoneId}" }
    @TypeConverter fun unit(value: String?): ValueUnit? = value?.let {
        val separator = it.indexOf(':')
        val codeLength = it.substring(0, separator).toInt()
        val content = it.substring(separator + 1)
        ValueUnit(content.substring(0, codeLength), content.substring(codeLength))
    }
    @TypeConverter fun unit(value: ValueUnit?): String? = value?.let { "${it.code.length}:${it.code}${it.label}" }
}

@Entity(tableName = "habits", primaryKeys = ["id"])
data class HabitRow(@Embedded val value: Habit)

@Entity(tableName = "goals", primaryKeys = ["id"], indices = [Index("habitId")],
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])])
data class GoalRow(@Embedded val value: GoalRevision)

@Entity(tableName = "manual_entries", primaryKeys = ["id"],
    indices = [Index(value = ["habitId", "day"], unique = true)],
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])])
data class ManualRow(@Embedded val value: ManualEntry)

@Entity(tableName = "intervals", primaryKeys = ["id"], indices = [Index("habitId")],
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])])
data class IntervalRow(@Embedded val value: ActivityInterval)

@Entity(tableName = "segments", primaryKeys = ["id"], indices = [Index("habitId")],
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])])
data class SegmentRow(@Embedded val value: ObservationSegment)

@Entity(tableName = "gaps", primaryKeys = ["id"], indices = [Index("habitId")],
    foreignKeys = [ForeignKey(entity = HabitRow::class, parentColumns = ["id"], childColumns = ["habitId"])])
data class GapRow(@Embedded val value: ObservationGap)

/** Local-only resumable state. On recovery close at last confirmed observation, never at now. */
@Entity(tableName = "tracking_checkpoint", primaryKeys = ["id"])
data class CheckpointRow(@Embedded val value: TrackingCheckpoint)

@Entity(tableName = "tracker_health")
data class HealthRow(@PrimaryKey val id: String = "main", @Embedded val value: TrackerHealth)

@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxRow(
    @PrimaryKey val operationId: String,
    val entityType: String,
    val entityId: String,
    val kind: String = "upsert",
    val baseRevision: String?,
    val payload: String,
    val createdAt: Instant,
)

@Entity(tableName = "sync_meta")
data class SyncMetaRow(@PrimaryKey val id: String = "main", val origin: String, val accountId: String,
    val cursor: String? = null, val serverShortsId: String? = null, val uploadEnabled: Boolean = false,
    val lastSuccessAt: Instant? = null, val errorCode: String? = null)

@Entity(tableName = "remote_entities")
data class RemoteRow(@PrimaryKey val key: String, val revision: String, val payload: String)

@Entity(tableName = "applied_changes")
data class AppliedRow(@PrimaryKey val revision: String)

/** Frozen wire body, never bearer tokens. A failed request remains visible, never false-acked. */
@Entity(tableName = "wire_requests", indices = [Index("outboxId")])
data class WireRow(@PrimaryKey val requestId: String, val origin: String, val accountId: String,
    val method: String, val path: String, val body: String, val outboxId: String?, val errorCode: String? = null)

/** Approval metadata only: raw outbox/payloads are never edited to extend a consent scope. */
@Entity(tableName = "upload_scopes")
data class UploadScopeRow(@PrimaryKey val id: String = "main", val scopeId: String, val origin: String,
    val accountId: String, val localProfileId: String)

@Entity(tableName = "approved_uploads", indices = [Index("scopeId")])
data class ApprovedUploadRow(@PrimaryKey val operationId: String, val scopeId: String)

@Dao
interface ChronaDao {
    @Query("SELECT * FROM habits ORDER BY createdAt, id") suspend fun habits(): List<HabitRow>
    @Query("SELECT * FROM habits WHERE id = :id") suspend fun habit(id: String): HabitRow?
    @Upsert suspend fun saveHabit(row: HabitRow)
    @Query("SELECT * FROM goals ORDER BY rowid") suspend fun goals(): List<GoalRow>
    @Insert suspend fun insertGoal(row: GoalRow)
    @Query("SELECT * FROM manual_entries") suspend fun entries(): List<ManualRow>
    @Query("SELECT * FROM manual_entries WHERE habitId = :habitId AND day = :day")
    suspend fun entry(habitId: String, day: DayKey): ManualRow?
    @Upsert suspend fun saveEntry(row: ManualRow)
    @Query("SELECT * FROM intervals ORDER BY startUtc") suspend fun intervals(): List<IntervalRow>
    @Query("SELECT * FROM intervals WHERE id = :id") suspend fun interval(id: String): IntervalRow?
    @Insert suspend fun insertInterval(row: IntervalRow)
    @Query("SELECT * FROM segments ORDER BY startUtc") suspend fun segments(): List<SegmentRow>
    @Query("SELECT * FROM segments WHERE id = :id") suspend fun segment(id: String): SegmentRow?
    @Insert suspend fun insertSegment(row: SegmentRow)
    @Query("SELECT * FROM gaps ORDER BY startUtc") suspend fun gaps(): List<GapRow>
    @Upsert suspend fun saveGap(row: GapRow)
    @Query("SELECT * FROM tracking_checkpoint WHERE id = 'main'") suspend fun checkpoint(): CheckpointRow?
    @Upsert suspend fun saveCheckpoint(row: CheckpointRow)
    @Query("SELECT * FROM tracker_health WHERE id = 'main'") suspend fun health(): HealthRow?
    @Upsert suspend fun saveHealth(row: HealthRow)
    @Insert suspend fun enqueue(row: OutboxRow)
    @Query("SELECT COUNT(*) FROM outbox") suspend fun pendingCount(): Int
    @Query("SELECT * FROM outbox ORDER BY rowid") suspend fun outbox(): List<OutboxRow>
    @Query("DELETE FROM outbox WHERE operationId = :id") suspend fun deleteOutbox(id: String)
    @Query("SELECT * FROM sync_meta WHERE id = 'main'") suspend fun syncMeta(): SyncMetaRow?
    @Upsert suspend fun saveSyncMeta(row: SyncMetaRow)
    @Query("SELECT * FROM remote_entities WHERE `key` = :key") suspend fun remote(key: String): RemoteRow?
    @Upsert suspend fun saveRemote(row: RemoteRow)
    @Query("SELECT COUNT(*) FROM applied_changes WHERE revision = :revision") suspend fun applied(revision: String): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun markApplied(row: AppliedRow)
    @Query("SELECT * FROM wire_requests ORDER BY rowid") suspend fun wireRequests(): List<WireRow>
    @Insert suspend fun insertWire(row: WireRow)
    @Upsert suspend fun saveWire(row: WireRow)
    @Query("DELETE FROM wire_requests WHERE requestId = :id") suspend fun deleteWire(id: String)
    @Query("SELECT * FROM goals WHERE id = :id") suspend fun goal(id: String): GoalRow?
    @Query("UPDATE goals SET revision = :revision WHERE id = :id") suspend fun goalRevision(id: String, revision: String)
    @Query("SELECT * FROM manual_entries WHERE id = :id") suspend fun entryById(id: String): ManualRow?
    @Query("SELECT * FROM gaps WHERE id = :id") suspend fun gap(id: String): GapRow?
    @Query("SELECT * FROM upload_scopes WHERE id = 'main'") suspend fun uploadScope(): UploadScopeRow?
    @Upsert suspend fun saveUploadScope(row: UploadScopeRow)
    @Query("DELETE FROM upload_scopes WHERE id = 'main'") suspend fun deleteUploadScope()
    @Query("SELECT * FROM approved_uploads WHERE scopeId = :scopeId") suspend fun approvals(scopeId: String): List<ApprovedUploadRow>
    @Insert suspend fun insertApprovals(rows: List<ApprovedUploadRow>)
    @Query("DELETE FROM approved_uploads WHERE operationId = :operationId") suspend fun deleteApproval(operationId: String)
    @Query("DELETE FROM approved_uploads WHERE scopeId = :scopeId") suspend fun deleteApprovals(scopeId: String)
}

@Database(entities = [HabitRow::class, GoalRow::class, ManualRow::class, IntervalRow::class,
    SegmentRow::class, GapRow::class, CheckpointRow::class, HealthRow::class, OutboxRow::class,
    SyncMetaRow::class, RemoteRow::class, AppliedRow::class, WireRow::class, UploadScopeRow::class, ApprovedUploadRow::class],
    version = 3, exportSchema = true)
@TypeConverters(RawConverters::class)
abstract class ChronaDatabase : RoomDatabase() {
    abstract fun dao(): ChronaDao
}

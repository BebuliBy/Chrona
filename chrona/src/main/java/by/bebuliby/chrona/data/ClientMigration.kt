package by.bebuliby.chrona.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object ClientMigration {
    val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `sync_meta` (`id` TEXT NOT NULL, `origin` TEXT NOT NULL, `accountId` TEXT NOT NULL, `cursor` TEXT, `serverShortsId` TEXT, `uploadEnabled` INTEGER NOT NULL, `lastSuccessAt` TEXT, `errorCode` TEXT, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `remote_entities` (`key` TEXT NOT NULL, `revision` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`key`))",
        "CREATE TABLE IF NOT EXISTS `applied_changes` (`revision` TEXT NOT NULL, PRIMARY KEY(`revision`))",
        "CREATE TABLE IF NOT EXISTS `wire_requests` (`requestId` TEXT NOT NULL, `origin` TEXT NOT NULL, `accountId` TEXT NOT NULL, `method` TEXT NOT NULL, `path` TEXT NOT NULL, `body` TEXT NOT NULL, `outboxId` TEXT, `errorCode` TEXT, PRIMARY KEY(`requestId`))",
        "CREATE INDEX IF NOT EXISTS `index_wire_requests_outboxId` ON `wire_requests` (`outboxId`)",
    )
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) { statements.forEach(db::execSQL) }
    }
    val scopeStatements = listOf(
        "CREATE TABLE IF NOT EXISTS `upload_scopes` (`id` TEXT NOT NULL, `scopeId` TEXT NOT NULL, `origin` TEXT NOT NULL, `accountId` TEXT NOT NULL, `localProfileId` TEXT NOT NULL, PRIMARY KEY(`id`))",
        "CREATE TABLE IF NOT EXISTS `approved_uploads` (`operationId` TEXT NOT NULL, `scopeId` TEXT NOT NULL, PRIMARY KEY(`operationId`))",
        "CREATE INDEX IF NOT EXISTS `index_approved_uploads_scopeId` ON `approved_uploads` (`scopeId`)",
        "UPDATE sync_meta SET uploadEnabled=0, errorCode=COALESCE(errorCode,'CONSENT_RECONFIRM_REQUIRED') WHERE uploadEnabled=1",
    )
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { scopeStatements.forEach(db::execSQL) }
    }
}

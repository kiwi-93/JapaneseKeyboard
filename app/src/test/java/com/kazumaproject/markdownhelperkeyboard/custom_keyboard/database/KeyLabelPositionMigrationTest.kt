package com.kazumaproject.markdownhelperkeyboard.custom_keyboard.database

import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.kazumaproject.markdownhelperkeyboard.database.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeyLabelPositionMigrationTest {
    @Test fun migratedDatabaseValidatesAndPerKeyOffsetsSurviveSavingAndReloading() = kotlinx.coroutines.runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "key-position-${System.nanoTime()}.db"
        fun open() = androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_49_50).allowMainThreadQueries().build()
        try {
            val initial = open()
            try {
                initial.openHelper.writableDatabase.execSQL("ALTER TABLE key_definitions DROP COLUMN labelVerticalOffsetDp")
                initial.openHelper.writableDatabase.execSQL("PRAGMA user_version = 49")
            } finally { initial.close() }
            val migrated = open()
            val id: Long
            try {
                assertEquals(50, migrated.openHelper.writableDatabase.version)
                val repository = com.kazumaproject.markdownhelperkeyboard.repository.KeyboardRepository(migrated.keyboardLayoutDao())
                val keys = listOf(
                    com.kazumaproject.custom_keyboard.data.KeyData("a", 0, 0, false, keyId = "a", labelVerticalOffsetDp = -5),
                    com.kazumaproject.custom_keyboard.data.KeyData("あ", 0, 1, false, keyId = "kana"))
                id = repository.saveLayout(com.kazumaproject.custom_keyboard.data.KeyboardLayout(
                    keys = keys, flickKeyMaps = emptyMap(), columnCount = 2, rowCount = 1), "test", null)
            } finally { migrated.close() }
            val reopened = open()
            try {
                val repository = com.kazumaproject.markdownhelperkeyboard.repository.KeyboardRepository(reopened.keyboardLayoutDao())
                val keys = repository.getFullLayout(id).first().keys
                assertEquals(listOf(-5, 0), keys.map { it.labelVerticalOffsetDp })
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test
    fun migration49To50_defaultsExistingKeysToZero() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "toggle-input-migration-${System.nanoTime()}.db"
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            "CREATE TABLE key_definitions (keyId INTEGER PRIMARY KEY NOT NULL)"
                        )
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                })
                .build()
        )
        try {
            val db = helper.writableDatabase
            db.execSQL("INSERT INTO key_definitions VALUES (1)")

            AppDatabase.MIGRATION_49_50.migrate(db)

            db.query("SELECT labelVerticalOffsetDp FROM key_definitions WHERE keyId = 1").use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
        } finally {
            helper.close()
            context.deleteDatabase(name)
        }
    }
}

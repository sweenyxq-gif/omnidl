package com.omnidownloader

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.omnidownloader.di.AppModule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DatabaseMigrationTest {
    @Test fun migrationOneToThreePreservesTasksAndAddsRuntimeMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("migration-1-2")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration-1-2")
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("""CREATE TABLE download_tasks (
                            id TEXT NOT NULL PRIMARY KEY, sourceType TEXT NOT NULL, sourceValue TEXT NOT NULL,
                            fileName TEXT NOT NULL, destinationTreeUri TEXT NOT NULL, mimeType TEXT NOT NULL,
                            totalBytes INTEGER NOT NULL, downloadedBytes INTEGER NOT NULL, status TEXT NOT NULL,
                            connections INTEGER NOT NULL, priority INTEGER NOT NULL, sha256 TEXT, wifiOnly INTEGER NOT NULL,
                            speedLimitBytesPerSecond INTEGER NOT NULL, category TEXT NOT NULL, createdAt INTEGER NOT NULL,
                            updatedAt INTEGER NOT NULL, errorCode TEXT, errorMessage TEXT)
                        """.trimIndent())
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        helper.writableDatabase.apply {
            execSQL(
                """INSERT INTO download_tasks
                    (id,sourceType,sourceValue,fileName,destinationTreeUri,mimeType,totalBytes,downloadedBytes,status,connections,priority,sha256,wifiOnly,speedLimitBytesPerSecond,category,createdAt,updatedAt,errorCode,errorMessage)
                    VALUES ('task-1','HTTP','https://example.com/file','file.bin','content://folder','application/octet-stream',100,25,'PAUSED',2,0,NULL,0,0,'OTHER',1,2,NULL,NULL)
                """.trimIndent()
            )
            AppModule.MIGRATION_1_2.migrate(this)
            AppModule.MIGRATION_2_3.migrate(this)
            query("SELECT id,resolvedUrl,etag,lastModified,speedBytesPerSecond,etaSeconds FROM download_tasks").use { cursor ->
                cursor.moveToFirst()
                assertEquals("task-1", cursor.getString(0))
                assertEquals(null, cursor.getString(1))
                assertEquals(null, cursor.getString(2))
                assertEquals(null, cursor.getString(3))
                assertEquals(0L, cursor.getLong(4))
                assertEquals(null, cursor.getString(5))
            }
        }
        helper.close()
        context.deleteDatabase("migration-1-2")
    }
}

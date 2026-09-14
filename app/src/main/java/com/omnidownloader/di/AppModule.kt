package com.omnidownloader.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.omnidownloader.data.database.DownloadDao
import com.omnidownloader.data.database.OmniDatabase
import com.omnidownloader.data.repository.RoomDownloadRepository
import com.omnidownloader.data.userscript.AndroidUserscriptEngine
import com.omnidownloader.data.userscript.UserscriptEngine
import com.omnidownloader.domain.repository.DownloadRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE download_tasks ADD COLUMN resolvedUrl TEXT")
            db.execSQL("ALTER TABLE download_tasks ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE download_tasks ADD COLUMN lastModified TEXT")
        }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE download_tasks ADD COLUMN speedBytesPerSecond INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE download_tasks ADD COLUMN etaSeconds INTEGER")
        }
    }
    @Provides @Singleton fun database(@ApplicationContext context: Context): OmniDatabase =
        Room.databaseBuilder(context, OmniDatabase::class.java, "omni-downloads.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
    @Provides fun dao(db: OmniDatabase): DownloadDao = db.downloadDao()
    @Provides @Singleton fun httpClient(): OkHttpClient {
        val dispatcher = Dispatcher().apply {
            maxRequests = 32
            maxRequestsPerHost = 16
        }
        return OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(ConnectionPool(8, 2, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true).build()
    }
}

@Module @InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds @Singleton abstract fun repository(impl: RoomDownloadRepository): DownloadRepository
    @Binds @Singleton abstract fun userscriptEngine(impl: AndroidUserscriptEngine): UserscriptEngine
}

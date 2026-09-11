package com.omnidownloader.di

import android.content.Context
import androidx.room.Room
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
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module @InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context: Context): OmniDatabase =
        Room.databaseBuilder(context, OmniDatabase::class.java, "omni-downloads.db").build()
    @Provides fun dao(db: OmniDatabase): DownloadDao = db.downloadDao()
    @Provides @Singleton fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).writeTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true).followSslRedirects(true).retryOnConnectionFailure(true).build()
}

@Module @InstallIn(SingletonComponent::class)
abstract class BindingsModule {
    @Binds @Singleton abstract fun repository(impl: RoomDownloadRepository): DownloadRepository
    @Binds @Singleton abstract fun userscriptEngine(impl: AndroidUserscriptEngine): UserscriptEngine
}

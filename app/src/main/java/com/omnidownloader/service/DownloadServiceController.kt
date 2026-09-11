package com.omnidownloader.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DownloadServiceController @Inject constructor(@ApplicationContext private val context: Context) {
    fun ensureRunning() = ContextCompat.startForegroundService(context, Intent(context, DownloadForegroundService::class.java))
}

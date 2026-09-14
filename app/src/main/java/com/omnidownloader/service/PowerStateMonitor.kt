package com.omnidownloader.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PowerStateMonitor @Inject constructor(@ApplicationContext private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    fun isPowerSaveMode(): Boolean = powerManager.isPowerSaveMode

    val powerSaveMode: Flow<Boolean> = callbackFlow {
        fun publish() { trySend(powerManager.isPowerSaveMode) }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = publish()
        }
        context.registerReceiver(receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED))
        publish()
        awaitClose { context.unregisterReceiver(receiver) }
    }.distinctUntilChanged()
}

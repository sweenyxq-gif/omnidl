package com.omnidownloader.data.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [DownloadTaskEntity::class, DownloadSegmentEntity::class, DownloadHeaderEntity::class, DownloadHistoryEntity::class, TorrentStateEntity::class, ResolverMetadataEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class OmniDatabase : RoomDatabase() { abstract fun downloadDao(): DownloadDao }

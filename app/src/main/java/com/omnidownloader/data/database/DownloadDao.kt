package com.omnidownloader.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

data class TaskWithHeaders(
    @Embedded val task: DownloadTaskEntity,
    @Relation(parentColumn = "id", entityColumn = "taskId") val headers: List<DownloadHeaderEntity>,
)

@Dao
interface DownloadDao {
    @Transaction @Query("SELECT * FROM download_tasks ORDER BY priority DESC, createdAt ASC")
    fun observeAll(): Flow<List<TaskWithHeaders>>

    @Transaction @Query("SELECT * FROM download_tasks WHERE id=:id") fun observe(id: String): Flow<TaskWithHeaders?>
    @Transaction @Query("SELECT * FROM download_tasks WHERE id=:id") suspend fun get(id: String): TaskWithHeaders?
    @Query("SELECT * FROM download_tasks WHERE status='WAITING' ORDER BY priority DESC, createdAt ASC LIMIT :limit") suspend fun waiting(limit: Int): List<DownloadTaskEntity>
    @Query("SELECT COUNT(*) FROM download_tasks WHERE status IN ('DOWNLOADING','RESOLVING')") suspend fun activeCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertTask(task: DownloadTaskEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertHeaders(headers: List<DownloadHeaderEntity>)
    @Query("DELETE FROM download_headers WHERE taskId=:taskId") suspend fun deleteHeaders(taskId: String)
    @Transaction suspend fun upsert(task: DownloadTaskEntity, headers: List<DownloadHeaderEntity>) { upsertTask(task); deleteHeaders(task.id); upsertHeaders(headers) }

    @Query("UPDATE download_tasks SET status=:status,errorCode=:errorCode,errorMessage=:errorMessage,updatedAt=:now WHERE id=:id")
    suspend fun setStatus(id: String, status: String, errorCode: String?, errorMessage: String?, now: Long = System.currentTimeMillis())
    @Query("UPDATE download_tasks SET downloadedBytes=:downloaded,totalBytes=:total,speedBytesPerSecond=:speed,etaSeconds=:eta,updatedAt=:now WHERE id=:id")
    suspend fun updateProgress(id: String, downloaded: Long, total: Long, speed: Long, eta: Long?, now: Long = System.currentTimeMillis())
    @Query("UPDATE download_tasks SET resolvedUrl=:resolvedUrl,etag=:etag,lastModified=:lastModified,updatedAt=:now WHERE id=:id")
    suspend fun updateRemoteMetadata(id: String, resolvedUrl: String, etag: String?, lastModified: String?, now: Long = System.currentTimeMillis())
    @Query("UPDATE download_tasks SET status=:next,updatedAt=:now WHERE status IN ('DOWNLOADING','RESOLVING')") suspend fun recover(next: String, now: Long = System.currentTimeMillis())
    @Query("DELETE FROM download_tasks WHERE id=:id") suspend fun deleteTask(id: String)
    @Query("DELETE FROM download_tasks WHERE status='COMPLETED'") suspend fun clearCompleted()

    @Query("SELECT * FROM download_segments WHERE taskId=:taskId ORDER BY segmentIndex") suspend fun segments(taskId: String): List<DownloadSegmentEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSegments(items: List<DownloadSegmentEntity>)
    @Query("UPDATE download_segments SET downloadedBytes=:downloaded,complete=:complete WHERE taskId=:taskId AND segmentIndex=:index") suspend fun updateSegment(taskId: String, index: Int, downloaded: Long, complete: Boolean)
    @Query("DELETE FROM download_segments WHERE taskId=:taskId") suspend fun deleteSegments(taskId: String)
    @Insert suspend fun insertHistory(history: DownloadHistoryEntity)
}

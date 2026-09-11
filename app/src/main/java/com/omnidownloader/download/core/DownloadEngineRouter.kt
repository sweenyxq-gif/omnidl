package com.omnidownloader.download.core

import com.omnidownloader.domain.engine.DownloadEngine
import com.omnidownloader.domain.model.DownloadSource
import com.omnidownloader.download.http.HttpDownloadEngine
import com.omnidownloader.download.torrent.TorrentDownloadEngine
import javax.inject.Inject

class DownloadEngineRouter @Inject constructor(private val http: HttpDownloadEngine, private val torrent: TorrentDownloadEngine) {
    fun engineFor(source: DownloadSource): DownloadEngine? = listOf<DownloadEngine>(http, torrent).firstOrNull { it.supports(source) }
}

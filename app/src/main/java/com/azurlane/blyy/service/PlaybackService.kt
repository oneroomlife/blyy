package com.azurlane.blyy.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.azurlane.blyy.MainActivity
import com.azurlane.blyy.R
import java.io.File

@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var player: ExoPlayer? = null

    private val serviceListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN($state)"
            }
            Log.d(TAG, "Playback state: $stateName")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error: code=${error.errorCode}", error)
        }
    }

    companion object {
        private const val TAG = "PlaybackService"
        private const val NOTIFICATION_ID = 123
        private const val CHANNEL_ID = "playback_channel"
        const val ACTION_PLAY_PAUSE = "com.azurlane.blyy.PLAY_PAUSE"
        const val ACTION_NEXT = "com.azurlane.blyy.NEXT"
        const val ACTION_PREVIOUS = "com.azurlane.blyy.PREVIOUS"

        @Volatile
        private var simpleCache: SimpleCache? = null

        fun getCache(context: Context): SimpleCache = simpleCache ?: synchronized(this) {
            simpleCache ?: SimpleCache(
                File(context.cacheDir, "exo_audio_cache"),
                LeastRecentlyUsedCacheEvictor(256L * 1024 * 1024),
                StandaloneDatabaseProvider(context)
            ).also { simpleCache = it }
        }
    }

    override fun onCreate() {
        super.onCreate()
        initializeSessionAndPlayer()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = getString(R.string.playback_channel_name)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }

        val notificationProvider = DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID)
            .setChannelId(CHANNEL_ID)
            .build()

        setMediaNotificationProvider(notificationProvider)
    }

    private fun initializeSessionAndPlayer() {
        try {
            // 基础 HTTP 数据源工厂 — 设置通用 User-Agent
            val httpDataSourceFactory = DefaultHttpDataSource.Factory()
                .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .setConnectTimeoutMs(20000)
                .setReadTimeoutMs(20000)

            // 使用 ResolvingDataSource 根据音频 URL 域名动态设置 Referer，突破防盗链
            // 舰娘音频 (biligame.com) 和学生音频 (gamekee.com) 需要不同的 Referer
            val resolvingFactory = ResolvingDataSource.Factory(httpDataSourceFactory) { dataSpec ->
                val host = dataSpec.uri.host ?: ""
                val referer = when {
                    host.contains("gamekee") -> "https://www.gamekee.com/"
                    host.contains("biligame") || host.contains("hdslb") -> "https://wiki.biligame.com/"
                    else -> "https://www.google.com/"
                }
                dataSpec.withAdditionalHeaders(mapOf("Referer" to referer))
            }

            // 磁盘缓存 256MB — 避免重复请求防盗链 URL，FLAG_IGNORE_CACHE_ON_ERROR 保证缓存异常时回退网络
            val cacheFactory = CacheDataSource.Factory()
                .setCache(getCache(this))
                .setUpstreamDataSourceFactory(resolvingFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            val dataSourceFactory = DefaultDataSource.Factory(this, cacheFactory)
            val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)

            val p = ExoPlayer.Builder(this)
                .setAudioAttributes(AudioAttributes.DEFAULT, true)
                .setHandleAudioBecomingNoisy(true)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
            p.addListener(serviceListener)
            player = p

            val sessionActivityIntent = Intent(this, MainActivity::class.java)
            val sessionActivity = PendingIntent.getActivity(
                this, 0, sessionActivityIntent, PendingIntent.FLAG_IMMUTABLE
            )

            mediaSession = MediaSession.Builder(this, p)
                .setSessionActivity(sessionActivity)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize player", e)
            player = null
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = player ?: return super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> {
                if (p.isPlaying) {
                    p.pause()
                } else {
                    p.play()
                }
            }
            ACTION_NEXT -> {
                if (p.hasNextMediaItem()) {
                    p.seekToNextMediaItem()
                } else if (p.mediaItemCount > 0) {
                    p.seekTo(0, 0)
                }
                p.play()
            }
            ACTION_PREVIOUS -> {
                if (p.hasPreviousMediaItem()) {
                    p.seekToPreviousMediaItem()
                } else if (p.mediaItemCount > 0) {
                    p.seekTo(p.mediaItemCount - 1, 0)
                }
                p.play()
            }
            else -> {
                intent?.getStringExtra("quote_url")?.let { url ->
                    if (p.isPlaying && url == p.currentMediaItem?.mediaId) {
                        return@let
                    }
                    val mediaItem = MediaItem.fromUri(url)
                    p.apply {
                        stop()
                        clearMediaItems()
                        setMediaItem(mediaItem)
                        repeatMode = Player.REPEAT_MODE_OFF
                        prepare()
                        play()
                    }
                }

                intent?.getStringArrayListExtra("media_items")?.let { urls ->
                    val startIndex = intent.getIntExtra("start_index", 0)
                    val mediaItems = urls.map { MediaItem.fromUri(it) }
                    p.apply {
                        stop()
                        clearMediaItems()
                        setMediaItems(mediaItems, startIndex, 0)
                        repeatMode = Player.REPEAT_MODE_ALL
                        prepare()
                        play()
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run { release() }
        mediaSession = null
        player?.run {
            removeListener(serviceListener)
            release()
        }
        player = null
        super.onDestroy()
    }
}

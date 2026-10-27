package com.mizan.launcher.data

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import com.mizan.launcher.core.NowPlayingLogic
import com.mizan.launcher.domain.NowPlayingItem
import com.mizan.launcher.domain.NowPlayingRepository
import com.mizan.launcher.domain.PlaybackState

/**
 * Media-session now-playing data (MAP §1.1a/§2). Authorized by
 * the passive notification-listener grant; the listener itself
 * (app layer) reads NO notification content and both callbacks
 * stay empty. This layer only queries MediaSessionManager.
 * System-ui and blank titles filtered; deduped by package, first
 * session wins; buffering reads as playing (domain
 * NowPlayingItem.readsAsPlaying). Grant check uses the component
 * name supplied by the app layer via setListenerComponent; until
 * then it checks the enabled-listener setting for any component
 * of this package, fail-open to false.
 */
class NowPlayingRepositoryImpl(ctx: Context) : NowPlayingRepository {
    private val context = ctx.applicationContext

    companion object {
        @Volatile var listenerComponent: ComponentName? = null
    }

    override fun isListenerGranted(): Boolean {
        return try {
            val flat = Settings.Secure.getString(context.contentResolver,
                "enabled_notification_listeners") ?: return false
            val target = listenerComponent
            if (target != null) {
                flat.split(':').any { it.equals(target.flattenToString(), ignoreCase = true) }
            } else {
                flat.split(':').any { it.startsWith(context.packageName + "/") }
            }
        } catch (e: Throwable) { false }
    }

    override fun getSessions(): List<NowPlayingItem> {
        if (!isListenerGranted()) return emptyList()
        return try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return emptyList()
            val controllers: List<MediaController> = try {
                msm.getActiveSessions(listenerComponent)
            } catch (e: Throwable) { emptyList() }
            val raw = controllers.mapNotNull { c ->
                try {
                    val md = c.metadata ?: return@mapNotNull null
                    val title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE)
                    val artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST)
                    val state = c.playbackState?.state ?: return@mapNotNull null
                    val actions = c.playbackState?.actions ?: 0L
                    NowPlayingLogic.RawSession(c.packageName, title, artist, state, actions)
                } catch (e: Throwable) { null }
            }
            val stateByPkg = raw.groupBy { it.pkg }.mapValues { it.value.first().state }
            NowPlayingLogic.cards(raw).map { card ->
                val state = when (stateByPkg[card.pkg]) {
                    NowPlayingLogic.STATE_PLAYING -> PlaybackState.PLAYING
                    NowPlayingLogic.STATE_BUFFERING -> PlaybackState.BUFFERING
                    else -> PlaybackState.PAUSED
                }
                NowPlayingItem(card.pkg, card.title, card.artist, state, card.canToggle)
            }
        } catch (e: Throwable) { emptyList() }
    }

    override fun toggle(item: NowPlayingItem) {
        if (!item.canToggle) return
        try {
            val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
                ?: return
            val c = msm.getActiveSessions(listenerComponent)
                .firstOrNull { it.packageName == item.packageName } ?: return
            val transport = c.transportControls ?: return
            if (item.readsAsPlaying) transport.pause() else transport.play()
        } catch (ignored: Throwable) { }
    }
}

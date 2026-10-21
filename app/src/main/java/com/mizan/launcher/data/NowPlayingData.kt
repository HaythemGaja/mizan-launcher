package com.mizan.launcher.data

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import com.mizan.launcher.NowPlayingListener
import com.mizan.launcher.core.NowPlayingLogic

/**
 * Now Playing data — Android side (v1.4).
 *
 * Reads MediaSessionManager's active sessions through the app's own
 * NotificationListenerService component (that grant is what authorizes
 * session access — there is no other permission for it). Everything is
 * fail-open: no grant, a SecurityException, or no sessions all read as
 * "no cards", and the home row hides silently.
 */
object NowPlayingData {

    /** One card plus the live objects the home screen needs to drive it. */
    data class LiveCard(
        val card: NowPlayingLogic.Card,
        val controller: MediaController,
        val artwork: Bitmap?
    )

    /** True when the user has granted Mizan notification access in system settings. */
    fun isGranted(ctx: Context): Boolean {
        return try {
            val flat = Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ).orEmpty()
            val own = ComponentName(ctx, NowPlayingListener::class.java).flattenToString()
            val short = "${ctx.packageName}/.${NowPlayingListener::class.java.simpleName}"
            flat.split(":").any { it == own || it == short || it.startsWith(ctx.packageName + "/") }
        } catch (e: Exception) {
            false
        }
    }

    /** Sessions that carry a title (before card mapping), for diagnostics. */
    fun sessionCount(ctx: Context): Int = try {
        controllers(ctx).count { controller ->
            !controller.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
        }
    } catch (e: Exception) {
        0
    }

    private fun controllers(ctx: Context): List<MediaController> {
        val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
            ?: return emptyList()
        return try {
            msm.getActiveSessions(ComponentName(ctx, NowPlayingListener::class.java))
        } catch (e: SecurityException) {
            // No notification-access grant → no sessions, silently.
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * The Now Playing cards for the home row: controllers reduced to
     * [NowPlayingLogic.RawSession]s, mapped by the pure logic, then
     * re-associated with the first controller of each package (the one
     * the card controls).
     */
    fun activeCards(ctx: Context): List<LiveCard> {
        return try {
            val list = controllers(ctx)
            val raw = list.map { controller ->
                val md = controller.metadata
                NowPlayingLogic.RawSession(
                    pkg = controller.packageName,
                    title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                    artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                        ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                    state = controller.playbackState?.state
                        ?: android.media.session.PlaybackState.STATE_NONE,
                    actions = controller.playbackState?.actions ?: 0L
                )
            }
            val cards = NowPlayingLogic.cards(raw)
            cards.mapNotNull { card ->
                val controller = list.firstOrNull { it.packageName == card.pkg }
                    ?: return@mapNotNull null
                val artwork = try {
                    controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                        ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                        ?: controller.metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                } catch (e: Exception) {
                    null
                }
                LiveCard(card, controller, artwork)
            }
        } catch (e: Exception) {
            Prefs.noteError(ctx, "nowPlaying", e)
            emptyList()
        }
    }
}

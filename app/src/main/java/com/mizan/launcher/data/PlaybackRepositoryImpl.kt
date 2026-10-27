package com.mizan.launcher.data

import android.content.Context
import com.mizan.launcher.core.PlayerLogic
import com.mizan.launcher.core.WatchMatch
import com.mizan.launcher.domain.PlayItem
import com.mizan.launcher.domain.PlaybackRepository

/**
 * Playback glue (MAP §1.9). Resume is saved on pause/destroy,
 * restored on reopen, hidden below threshold, cleared when
 * finished (near end / ≥95%) — the threshold/clear logic lives
 * in IptvRepositoryImpl; this layer owns the auto-mark gate.
 *
 * Auto-mark (MAP §1.9 / domain contract): fires ONCE on playback
 * ended (even at low position) or at the 90% threshold (89% does
 * NOT fire); never twice, never tokenless, never on a partial
 * watch. Implementation follows the old code exactly:
 * core/WatchMatch.shouldAutoMark, i.e. ended OR pos ≥ 90% of
 * duration. (The task/domain wording "exactly 90%" is the 90%
 * threshold, not a millisecond-exact equality — an exact-equality
 * test would almost never fire at real playback positions.)
 * The fired-mark itself is applied by the UI worker via
 * TrackerRepository (token → episode/movie), because this layer
 * must not invent a mark mapping the token does not carry; the
 * dedupe set here guarantees once-per-item per process.
 */
class PlaybackRepositoryImpl(ctx: Context) : PlaybackRepository {
    private val prefs = PrefsStore(ctx)
    private val iptv = IptvRepositoryImpl(ctx)
    private val tracker = TrackerRepositoryImpl(ctx)
    private val fired = HashSet<String>()

    /** Callback the UI worker sets to apply a fired auto-mark. */
    var onAutoMark: ((PlayItem) -> Unit)? = null

    // Phase 2D queue contract (UI gap (d)): no queue is derivable
    // from a PlayItem alone (it carries an episode id, not its
    // series id), so this returns null and the player reports
    // "No queue neighbour" honestly. Series-detail screens build
    // neighbour lists from IptvRepository.getSeriesEpisodes.
    override fun queueFor(item: PlayItem): com.mizan.launcher.domain.PlayQueue? = null

    override fun saveResume(identityKey: String, positionMs: Long) {
        iptv.setResume(identityKey, positionMs)
    }
    override fun clearResume(identityKey: String) {
        iptv.setResume(identityKey, null)
    }

    override fun maybeAutoMark(item: PlayItem, positionMs: Long, durationMs: Long, ended: Boolean) {
        val token = item.libraryMarkToken ?: return // never tokenless
        val key = "${item.kind}:${item.id}:$token"
        if (!WatchMatch.shouldAutoMark(true, key in fired, ended, positionMs, durationMs)) return
        fired.add(key)
        try {
            onAutoMark?.invoke(item)
        } catch (ignored: Throwable) { }
    }

    override fun getAspect(): String = PlayerLogic.normalizeAspect(prefs.playerAspect())
    override fun setAspect(aspect: String) {
        prefs.setPlayerAspect(PlayerLogic.normalizeAspect(aspect))
    }
    override fun nextSpeed(current: Double): Double =
        PlayerLogic.nextSpeed(current.toFloat()).toDouble()
}

package com.mizan.launcher.ui

import android.widget.ImageView
import com.mizan.launcher.domain.AppsRepository
import com.mizan.launcher.domain.DiagnosticsRepository
import com.mizan.launcher.domain.HomeWatcherProbe
import com.mizan.launcher.domain.GetDiagnosticsUseCase
import com.mizan.launcher.domain.GetForecastUseCase
import com.mizan.launcher.domain.GetForYouUseCase
import com.mizan.launcher.domain.GetHeroUseCase
import com.mizan.launcher.domain.GetMissedUseCase
import com.mizan.launcher.domain.GetNowPlayingUseCase
import com.mizan.launcher.domain.GetTrendingUseCase
import com.mizan.launcher.domain.GetUpNextUseCase
import com.mizan.launcher.domain.IptvRepository
import com.mizan.launcher.domain.LockRepository
import com.mizan.launcher.domain.ManageFoldersUseCase
import com.mizan.launcher.domain.MarkEpisodeUseCase
import com.mizan.launcher.domain.NowPlayingRepository
import com.mizan.launcher.domain.PlayItem
import com.mizan.launcher.domain.PlaybackRepository
import com.mizan.launcher.domain.PlayTitleUseCase
import com.mizan.launcher.domain.SearchTitlesUseCase
import com.mizan.launcher.domain.SettingsRepository
import com.mizan.launcher.domain.TmdbRepository
import com.mizan.launcher.domain.TrackerRepository
import com.mizan.launcher.domain.WeatherRepository

/**
 * UI-layer wiring holder, set by the app layer (integration worker).
 * UI activities never construct repository implementations and never
 * import data/ or app/ classes; when a repository is unset the screens
 * render their empty/error state instead of crashing.
 */
object UiWiring {
    var tracker: TrackerRepository? = null
    var tmdb: TmdbRepository? = null
    var iptv: IptvRepository? = null
    var apps: AppsRepository? = null
    var settings: SettingsRepository? = null
    var weather: WeatherRepository? = null
    var playback: PlaybackRepository? = null
    var nowPlaying: NowPlayingRepository? = null
    var diagnostics: DiagnosticsRepository? = null
    var lock: LockRepository? = null
    var upNext: GetUpNextUseCase? = null
    var missed: GetMissedUseCase? = null
    var hero: GetHeroUseCase? = null
    var forYou: GetForYouUseCase? = null
    var trending: GetTrendingUseCase? = null
    var playTitle: PlayTitleUseCase? = null
    var markEpisode: MarkEpisodeUseCase? = null
    var searchTitles: SearchTitlesUseCase? = null
    var nowPlayingUseCase: GetNowPlayingUseCase? = null
    var forecast: GetForecastUseCase? = null
    var diagnosticsUseCase: GetDiagnosticsUseCase? = null
    var folders: ManageFoldersUseCase? = null

    /** Optional app-layer playback host; if unset, PlayerActivity is launched directly. */
    var onPlay: ((PlayItem) -> Unit)? = null
    /** App-layer home/watcher probe (HomeWatcherProbeImpl); Settings shows its state when set. */
    var homeWatcher: HomeWatcherProbe? = null
    /** App-layer poster loader: (view, url) -> Unit; loads on a worker thread, sets on main. */
    var imageLoader: ((ImageView, String) -> Unit)? = null
    /** App-layer settings export/import (PrefsStore JSON shape); Settings uses these when set. */
    var exportSettings: (() -> String)? = null
    var importSettings: ((String) -> Boolean)? = null
    /** App-layer "Next wallpaper" jump (WallpaperRotator.nextNow); returns the new image URL or null. */
    var nextWallpaper: (() -> String?)? = null
    /** Optional app-layer system-action callbacks (quick settings, app info, uninstall). */
    var onOpenSettings: (() -> Unit)? = null
    var onSpeedUp: (() -> String)? = null
}

package com.music.bitchord.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * Where desktop Dial keeps its things, apart from the BitChord desktop app it
 * grew out of, so the two never read or overwrite each other's sign-in,
 * settings or caches.
 *
 * - Settings: the `com.steeben.dial.desktop` preference node (the registry on
 *   Windows), and `.stats` under it for the listening stats.
 * - Files: `%APPDATA%\Dial` on Windows (`cache`, `analysis`, `logs` inside);
 *   the usual XDG folders, under `dial`, elsewhere.
 */
internal object DesktopStorage {
    const val PREFERENCES_NODE = "com.steeben.dial.desktop"
    const val STATS_NODE = "$PREFERENCES_NODE.stats"

    /** BitChord's node: only ever read, once, to bring the liked songs over. */
    private const val BITCHORD_NODE = "com.music.bitchord.desktop"
    private const val KEY_ADOPTED_BITCHORD_LIKES = "adopted_bitchord_likes"

    private val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    private val home: String get() = System.getProperty("user.home")

    private val windowsRoot: Path by lazy {
        val appData = System.getenv("APPDATA")?.takeIf(String::isNotBlank) ?: "$home/AppData/Roaming"
        Path.of(appData, "Dial")
    }

    private fun xdg(variable: String, fallback: String): Path =
        Path.of(System.getenv(variable)?.takeIf(String::isNotBlank) ?: "$home/$fallback", "dial")

    /** Cached media (Canvas clips and the like). */
    val cacheDir: Path by lazy { if (isWindows) windowsRoot.resolve("cache") else xdg("XDG_CACHE_HOME", ".cache") }

    /** Unpacked analysis resources and cached analyses. */
    val dataDir: Path by lazy { if (isWindows) windowsRoot else xdg("XDG_DATA_HOME", ".local/share") }

    /** The desktop log. */
    val stateDir: Path by lazy { if (isWindows) windowsRoot.resolve("logs") else xdg("XDG_STATE_HOME", ".local/state") }

    fun directory(path: Path): Path = path.also { runCatching { Files.createDirectories(it) } }

    /**
     * On Dial's first start, its liked songs are the ones the BitChord desktop
     * app had. Read-only on BitChord's side; everything else starts clean.
     */
    fun adoptBitChordLikes() {
        val dial = DesktopPersistence()
        if (dial.boolean(KEY_ADOPTED_BITCHORD_LIKES, false)) return
        runCatching {
            if (Preferences.userRoot().nodeExists(BITCHORD_NODE) && dial.likedIds().isEmpty()) {
                val likes = DesktopPersistence(BITCHORD_NODE).likedIds()
                if (likes.isNotEmpty()) {
                    dial.saveLikedIds(likes)
                    DesktopTrackLog.log("storage: brought ${likes.size} liked songs over from BitChord")
                }
            }
        }.onFailure { DesktopTrackLog.log("storage: could not read BitChord's liked songs (${it.message})") }
        dial.saveBoolean(KEY_ADOPTED_BITCHORD_LIKES, true)
    }
}

package com.music.bitchord.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

/**
 * Spotify's sign-in in the listener's own browser, for logins the in-app window cannot do (Facebook
 * and Apple turn embedded browsers away). Dial opens Spotify's login page in the default browser,
 * then watches the browsers on this computer for a new `sp_dc` cookie on spotify.com, the session
 * the phone's sign-in takes too, and adopts the one that browser wrote. Nothing else is read from
 * any browser, and nothing is read before the listener asks for it.
 */
internal object DesktopSpotifyBrowserSignIn {

    private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    private const val POLL_MS = 2_500L
    private const val GIVE_UP_MS = 10 * 60_000L
    private const val ALREADY_SIGNED_IN_MS = 15_000L

    /** The cookie, once a browser has a new one; null if the listener never signed in. */
    suspend fun signIn(): String? = withContext(Dispatchers.IO) {
        val profiles = DesktopBrowserCookies.profiles()
        // What every browser held before: a session it already had is not this sign-in's.
        val before = profiles.associate { it.label to DesktopBrowserCookies.readSpotify(it)?.spDc }
        DesktopTrackLog.log("spotify: opening the sign-in in the default browser; watching ${profiles.size} browser profiles")
        openInBrowser()
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < GIVE_UP_MS) {
            delay(POLL_MS)
            val found = profiles.mapNotNull { profile ->
                DesktopBrowserCookies.readSpotify(profile)?.takeIf { it.spDc != before[profile.label] }
            }.maxByOrNull { it.writtenAt }
            if (found != null) {
                DesktopTrackLog.log("spotify: signed in through ${found.profile.label}")
                return@withContext found.spDc
            }
            // Already signed in there: Spotify sends the browser straight on without writing a new
            // session. After a moment, the newest session a browser holds is the one to use.
            if (System.currentTimeMillis() - started > ALREADY_SIGNED_IN_MS) {
                val held = profiles.mapNotNull(DesktopBrowserCookies::readSpotify).maxByOrNull { it.writtenAt }
                if (held != null) {
                    DesktopTrackLog.log("spotify: using the session ${held.profile.label} already had")
                    return@withContext held.spDc
                }
            }
        }
        DesktopTrackLog.log("spotify: no new Spotify sign-in turned up in a browser")
        null
    }

    /**
     * The session a browser already has, for someone signed in to Spotify there before: the most
     * recently written one. Null when none can be read.
     */
    suspend fun existing(): DesktopBrowserCookies.SpotifySession? = withContext(Dispatchers.IO) {
        DesktopBrowserCookies.profiles().mapNotNull(DesktopBrowserCookies::readSpotify).maxByOrNull { it.writtenAt }
    }

    private fun openInBrowser() {
        val uri = URI(LOGIN_URL)
        val desktop = Desktop.getDesktop().takeIf { Desktop.isDesktopSupported() && it.isSupported(Desktop.Action.BROWSE) }
        if (desktop != null) {
            desktop.browse(uri)
        } else if (DesktopPlatform.isWindows) {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", LOGIN_URL).start()
        } else {
            ProcessBuilder("xdg-open", LOGIN_URL).start()
        }
    }
}

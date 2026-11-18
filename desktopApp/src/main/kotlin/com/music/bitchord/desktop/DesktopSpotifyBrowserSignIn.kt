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

    @Volatile private var lastPayload: String? = null

    /** The web player's token the last sign-in read, once. */
    fun takePayload(): String? = lastPayload.also { lastPayload = null }

    private const val LOGIN_URL = "https://accounts.spotify.com/login?continue=https%3A%2F%2Fopen.spotify.com%2F"
    private const val POLL_MS = 2_500L
    private const val GIVE_UP_MS = 10 * 60_000L
    private const val ALREADY_SIGNED_IN_MS = 15_000L

    /** The cookie, once a browser has a new one; null if the listener never signed in. */
    suspend fun signIn(onWaiting: (String?) -> Unit = {}): String? = withContext(Dispatchers.IO) {
        if (DesktopChromium.executable() != null) return@withContext signInWithDialBrowser(onWaiting)
        signInFromBrowserCookies(onWaiting)
    }

    /**
     * Spotify's login in a real browser window on Dial's own profile: Facebook and Apple accept it,
     * and the session and the web player's token are read straight off it over DevTools, with no
     * cookie file to unlock or decrypt. The window closes once Spotify has signed in.
     */
    private suspend fun signInWithDialBrowser(onWaiting: (String?) -> Unit): String? {
        val session = DesktopChromium.launch(LOGIN_URL, headless = false) ?: return signInFromBrowserCookies(onWaiting)
        DesktopTrackLog.log("spotify: sign-in opened in Dial's own browser window")
        onWaiting("Sign in to Spotify in the browser window Dial opened; it closes by itself when you're done.")
        var signedInAt = 0L
        return try {
            val found = DesktopChromium.watchSpotify(session, timeoutMs = GIVE_UP_MS) { seen ->
                if (seen.spDc != null && signedInAt == 0L) signedInAt = System.currentTimeMillis()
                // Signed in: wait a little for the web player's own token, then go.
                seen.spDc != null && (seen.tokenPayload != null || System.currentTimeMillis() - signedInAt > 20_000) ||
                    !session.process.isAlive
            }
            // Kept for the caller, which adopts it once the cookie is stored (storing one clears the token).
            lastPayload = found.tokenPayload
            DesktopTrackLog.log(
                when {
                    found.spDc == null -> "spotify: the browser window closed without a Spotify sign-in"
                    found.tokenPayload != null -> "spotify: signed in, with the web player's token"
                    else -> "spotify: signed in; the web player's token did not come"
                },
            )
            onWaiting(null)
            found.spDc
        } finally {
            session.close()
        }
    }

    /** The older route: the listener's own browser, and its cookie file read once it lets go of it. */
    private suspend fun signInFromBrowserCookies(onWaiting: (String?) -> Unit): String? = withContext(Dispatchers.IO) {
        val profiles = DesktopBrowserCookies.profiles()
        // What every browser held before: a session it already had is not this sign-in's.
        val before = profiles.associate { it.label to DesktopBrowserCookies.readSpotify(it)?.spDc }
        DesktopTrackLog.log("spotify: opening the sign-in in the default browser; watching ${profiles.size} browser profiles")
        openInBrowser()
        val started = System.currentTimeMillis()
        var lastLocked = emptySet<String>()
        while (System.currentTimeMillis() - started < GIVE_UP_MS) {
            delay(POLL_MS)
            // A running Chromium browser keeps its cookies locked: the listener closes it once
            // signed in, and the session is read the moment it lets go.
            val locked = DesktopBrowserCookies.lockedProfiles.map { it.substringBefore(" · ") }.toSet()
            if (locked != lastLocked) {
                lastLocked = locked
                if (locked.isNotEmpty()) DesktopTrackLog.log("spotify: waiting on ${locked.joinToString()}, whose cookies are locked while it runs")
                onWaiting(
                    locked.takeIf { it.isNotEmpty() }?.let { names ->
                        "Signed in? Close ${names.joinToString(" / ")} so Dial can read the sign-in: it keeps its cookies locked while it runs."
                    },
                )
            }
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
        val opened = runCatching {
            val desktop = Desktop.getDesktop()
            check(Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE))
            desktop.browse(uri)
        }.isSuccess
        if (opened) return
        // Handed to the shell instead, as a double-click on a link would be.
        DesktopTrackLog.log("spotify: the browser could not be opened directly; asking the shell")
        if (DesktopPlatform.isWindows) {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", LOGIN_URL).start()
        } else {
            ProcessBuilder("xdg-open", LOGIN_URL).start()
        }
    }
}

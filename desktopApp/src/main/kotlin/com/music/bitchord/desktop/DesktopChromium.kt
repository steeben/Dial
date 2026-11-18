package com.music.bitchord.desktop

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A Chromium browser installed on this computer, run by Dial with a profile of Dial's own, over
 * the DevTools protocol on 127.0.0.1. The listener's own browser profile is never opened or read.
 *
 * Used for Spotify: its sign-in (Facebook and Apple refuse an embedded browser, never a real one),
 * and the web player token, read off the real web player as the phone reads it off its WebView.
 */
internal object DesktopChromium {

    /** Dial's own browser profile, for Spotify alone. */
    val profileDir: Path get() = DesktopStorage.dataDir.resolve("spotify-browser")

    /** Whether Dial has signed in to Spotify in its own profile at least once. */
    val hasProfile: Boolean get() = Files.isDirectory(profileDir.resolve("Default"))

    /** The first Chromium browser found: Helium, Chrome, Edge, Brave, Vivaldi or Chromium. */
    fun executable(): Path? {
        val home = Paths.get(System.getProperty("user.home"))
        val local = System.getenv("LOCALAPPDATA")?.let(Paths::get) ?: home.resolve("AppData/Local")
        val programs = listOfNotNull(System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")).map(Paths::get)
        val candidates = if (DesktopPlatform.isWindows) {
            listOf(local.resolve("imput/Helium/Application/chrome.exe")) +
                (programs + local).flatMap { root ->
                    listOf(
                        root.resolve("Google/Chrome/Application/chrome.exe"),
                        root.resolve("Microsoft/Edge/Application/msedge.exe"),
                        root.resolve("BraveSoftware/Brave-Browser/Application/brave.exe"),
                        root.resolve("Vivaldi/Application/vivaldi.exe"),
                        root.resolve("Chromium/Application/chrome.exe"),
                    )
                }
        } else if (DesktopPlatform.isMac) {
            listOf(
                "/Applications/Helium.app/Contents/MacOS/Helium",
                "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
                "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                "/Applications/Chromium.app/Contents/MacOS/Chromium",
            ).map(Paths::get)
        } else {
            listOf("helium", "google-chrome", "chromium", "chromium-browser", "brave-browser", "microsoft-edge")
                .flatMap { name -> listOf(Paths.get("/usr/bin/$name"), Paths.get("/usr/local/bin/$name")) }
        }
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }

    /** A browser Dial started, with its DevTools page connection. */
    class Session(val process: Process, val port: Int) {
        fun close() {
            live -= this
            runCatching { process.descendants().forEach { it.destroy() } }
            process.destroy()
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                runCatching { process.descendants().forEach { it.destroyForcibly() } }
                process.destroyForcibly()
            }
        }
    }

    private val live = java.util.concurrent.CopyOnWriteArraySet<Session>()

    init {
        // A browser Dial started goes when Dial does, so none is left holding the profile.
        Runtime.getRuntime().addShutdownHook(Thread({ live.forEach { runCatching { it.close() } } }, "dial-chromium-close"))
    }

    /**
     * Closes a browser still running on Dial's profile from an earlier run (one Dial did not get to
     * close): a second launch on the same profile is handed to it and opens no DevTools port.
     * Asked to close its windows first, so its cookies are saved, then stopped.
     */
    private fun closeStrays() {
        if (!DesktopPlatform.isWindows) return
        val marker = profileDir.fileName.toString()
        val script = "\$ps = Get-CimInstance Win32_Process -Filter \"Name='chrome.exe' OR Name='msedge.exe' OR Name='brave.exe' OR Name='vivaldi.exe'\" | " +
            "Where-Object { \$_.CommandLine -like '*$marker*' }; if (\$ps) { " +
            "\$ps | ForEach-Object { (Get-Process -Id \$_.ProcessId -ErrorAction SilentlyContinue).CloseMainWindow() | Out-Null }; " +
            "Start-Sleep -Seconds 3; \$ps | ForEach-Object { Stop-Process -Id \$_.ProcessId -Force -ErrorAction SilentlyContinue }; 'closed' }"
        runCatching {
            val result = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start()
            val out = result.inputStream.bufferedReader().readText()
            result.waitFor(15, TimeUnit.SECONDS)
            if ("closed" in out) DesktopTrackLog.log("chromium: closed a browser left on Dial's profile")
        }
    }

    /** Starts the browser on [url] with Dial's profile; [headless] keeps it off screen. */
    fun launch(url: String, headless: Boolean): Session? {
        val exe = executable() ?: return null
        Files.createDirectories(profileDir)
        closeStrays()
        val portFile = profileDir.resolve("DevToolsActivePort")
        runCatching { Files.deleteIfExists(portFile) }
        val args = buildList {
            add(exe.toString())
            add("--user-data-dir=${profileDir.toAbsolutePath()}")
            add("--remote-debugging-address=127.0.0.1")
            add("--remote-debugging-port=0")
            add("--no-first-run")
            add("--no-default-browser-check")
            add("--disable-background-networking")
            if (headless) {
                add("--headless=new")
                add("--mute-audio")
                add("--window-size=1280,800")
            } else {
                add("--new-window")
                add("--window-size=520,760")
            }
            add(url)
        }
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        // The browser writes the port it picked to DevToolsActivePort, first line.
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline && process.isAlive) {
            val port = runCatching { Files.readAllLines(portFile).firstOrNull()?.trim()?.toInt() }.getOrNull()
            if (port != null && port > 0) {
                DesktopTrackLog.log("chromium: started ${exe.fileName}${if (headless) " (hidden)" else ""}")
                return Session(process, port).also { live += it }
            }
            Thread.sleep(150)
        }
        DesktopTrackLog.log("chromium: ${exe.fileName} did not open its DevTools port")
        process.destroyForcibly()
        return null
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val http by lazy { HttpClient(CIO) { install(WebSockets) } }

    /** What a page session reports: the Spotify session cookie and the web player token, as they come. */
    class SpotifyFindings(val spDc: String?, val tokenPayload: String?)

    /**
     * Watches the first page of [session] until [done] is satisfied by what it has seen, or
     * [timeoutMs] passes: the `sp_dc` cookie on open.spotify.com (polled) and the body of the web
     * player's own `/api/token` response (as it arrives).
     */
    suspend fun watchSpotify(
        session: Session,
        timeoutMs: Long,
        done: (SpotifyFindings) -> Boolean,
    ): SpotifyFindings {
        var spDc: String? = null
        var token: String? = null
        val pageWs = withTimeoutOrNull(10_000) {
            var found: String? = null
            while (found == null) {
                found = runCatching {
                    val list = json.parseToJsonElement(http.get("http://127.0.0.1:${session.port}/json/list").bodyAsText()).jsonArray
                    list.map { it.jsonObject }.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "page" }
                        ?.get("webSocketDebuggerUrl")?.jsonPrimitive?.contentOrNull
                }.getOrNull()
                if (found == null) delay(200)
            }
            found
        } ?: return SpotifyFindings(null, null)

        withTimeoutOrNull(timeoutMs) {
            http.webSocket(pageWs) {
                val ids = AtomicInteger(0)
                val pending = HashMap<Int, CompletableDeferred<JsonObject>>()
                val tokenRequests = HashSet<String>()
                val outgoing = Channel<String>(Channel.UNLIMITED)
                suspend fun call(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject? {
                    val id = ids.incrementAndGet()
                    val answer = CompletableDeferred<JsonObject>()
                    synchronized(pending) { pending[id] = answer }
                    outgoing.send(buildJsonObject { put("id", id); put("method", method); put("params", params) }.toString())
                    return withTimeoutOrNull(5_000) { answer.await() }
                }
                coroutineScope {
                    val writer = launch { for (text in outgoing) send(Frame.Text(text)) }
                    val reader = launch {
                        for (frame in incoming) {
                            val text = (frame as? Frame.Text)?.readText() ?: continue
                            val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                            message["id"]?.jsonPrimitive?.int?.let { id ->
                                synchronized(pending) { pending.remove(id) }?.complete(message)
                                return@let
                            }
                            val params = message["params"]?.jsonObject ?: continue
                            when (message["method"]?.jsonPrimitive?.contentOrNull) {
                                "Network.responseReceived" -> {
                                    val url = params["response"]?.jsonObject?.get("url")?.jsonPrimitive?.contentOrNull.orEmpty()
                                    if ("/api/token" in url) params["requestId"]?.jsonPrimitive?.contentOrNull?.let { tokenRequests += it }
                                }
                                "Network.loadingFinished" -> {
                                    val requestId = params["requestId"]?.jsonPrimitive?.contentOrNull
                                    if (requestId != null && requestId in tokenRequests) {
                                        launch {
                                            val body = call("Network.getResponseBody", buildJsonObject { put("requestId", requestId) })
                                                ?.get("result")?.jsonObject?.get("body")?.jsonPrimitive?.contentOrNull
                                            if (body != null && "\"accessToken\"" in body && "\"isAnonymous\":true" !in body) token = body
                                        }
                                    }
                                }
                            }
                        }
                    }
                    call("Network.enable")
                    while (true) {
                        val cookies = call(
                            "Network.getCookies",
                            buildJsonObject { put("urls", JsonArray(listOf(JsonPrimitive("https://open.spotify.com/")))) },
                        )?.get("result")?.jsonObject?.get("cookies")?.jsonArray
                        spDc = cookies?.map { it.jsonObject }
                            ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull == "sp_dc" }
                            ?.get("value")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                            ?: spDc
                        if (done(SpotifyFindings(spDc, token))) break
                        delay(1_000)
                    }
                    reader.cancel()
                    writer.cancel()
                    outgoing.close()
                }
            }
        }
        return SpotifyFindings(spDc, token)
    }
}

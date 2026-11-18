package com.music.bitchord.desktop

import com.music.bitchord.data.settings.DialStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * The desktop's [DialStore]: the dial layout as one JSON file, under the same
 * keys the phone keeps it in. A file rather than java.util.prefs, which holds
 * at most 8 KB a value, less than a full dial of pins with their art.
 *
 * Windows: `%APPDATA%\Dial\dial-layout.json`. Elsewhere: `~/.config/Dial/`.
 */
class DesktopDialStore(private val file: Path = defaultFile()) : DialStore {
    private val lock = Any()
    private val values: MutableMap<String, Any> = read()

    override fun contains(key: String) = synchronized(lock) { key in values }
    override fun getString(key: String) = synchronized(lock) { values[key] as? String }
    override fun getBoolean(key: String, default: Boolean) = synchronized(lock) { values[key] as? Boolean ?: default }
    override fun getInt(key: String, default: Int) = synchronized(lock) { values[key] as? Int ?: default }

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String): Set<String> = synchronized(lock) { (values[key] as? Set<String>).orEmpty() }

    override fun edit(block: DialStore.Editor.() -> Unit) {
        val snapshot = synchronized(lock) {
            object : DialStore.Editor {
                override fun putString(key: String, value: String) { values[key] = value }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                override fun putInt(key: String, value: Int) { values[key] = value }
                override fun putStringSet(key: String, value: Set<String>) { values[key] = value.toSet() }
                override fun remove(key: String) { values.remove(key) }
            }.block()
            encode(values)
        }
        write(snapshot)
    }

    private fun read(): MutableMap<String, Any> {
        val text = runCatching { if (Files.isRegularFile(file)) Files.readString(file) else null }.getOrNull()
            ?: return LinkedHashMap()
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return LinkedHashMap()
        val map = LinkedHashMap<String, Any>()
        root.forEach { (key, element) -> decode(element)?.let { map[key] = it } }
        return map
    }

    /** Written beside the file and moved over it, so a crash mid-write leaves the old layout. */
    private fun write(text: String) {
        runCatching {
            Files.createDirectories(file.parent)
            val temp = file.resolveSibling(file.fileName.toString() + ".tmp")
            Files.writeString(temp, text)
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        }.onFailure { DesktopTrackLog.log("DialStore: could not save the dial layout (${it.message})") }
    }

    companion object {
        fun defaultFile(): Path = DesktopStorage.dataDir.resolve("dial-layout.json")

        private val json = Json { prettyPrint = true }

        // Sets are JSON arrays, so they stay apart from strings when read back.
        private fun encode(values: Map<String, Any>): String = json.encodeToString(
            JsonObject.serializer(),
            JsonObject(
                values.mapValues { (_, value) ->
                    when (value) {
                        is String -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        is Int -> JsonPrimitive(value)
                        is Set<*> -> JsonArray(value.map { JsonPrimitive(it.toString()) })
                        else -> JsonPrimitive(value.toString())
                    }
                },
            ),
        )

        private fun decode(element: JsonElement): Any? = when (element) {
            is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.content }.toSet()
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.booleanOrNull != null -> element.booleanOrNull
                element.intOrNull != null -> element.intOrNull
                else -> null
            }
            else -> null
        }
    }
}

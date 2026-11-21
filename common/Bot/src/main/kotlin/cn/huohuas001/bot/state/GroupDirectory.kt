package cn.huohuas001.bot.state

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** OpenID-to-name cache for groups observed through QQ message events. */
object GroupDirectory {
    @Volatile private var names: Map<String, String> = emptyMap()
    @Volatile private var file: File? = null
    private val gson = Gson()
    private val nameLookups = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun requestNameLookup(openId: String, now: Long = System.currentTimeMillis()): Boolean {
        if (openId.isBlank()) return false
        while (true) {
            val previous = nameLookups[openId]
            if (previous != null && now - previous < NAME_LOOKUP_COOLDOWN_MS) return false
            if (previous == null) {
                if (nameLookups.putIfAbsent(openId, now) == null) return true
            } else if (nameLookups.replace(openId, previous, now)) return true
        }
    }

    private const val NAME_LOOKUP_COOLDOWN_MS = 300_000L

    @Synchronized
    fun initialize(directory: File?) {
        val target = directory?.resolve("group-names.json")
        val loaded = if (target?.isFile == true) {
            val root = JsonParser.parseString(target.readText(Charsets.UTF_8)).asJsonObject
            root.entrySet().mapNotNull { (openId, value) ->
                if (value.isJsonPrimitive && value.asJsonPrimitive.isString && value.asString.isNotBlank()) {
                    openId to value.asString
                } else null
            }.toMap()
        } else emptyMap()
        file = target
        names = loaded
    }

    fun forgetNameLookup(openId: String) {
        nameLookups.remove(openId)
    }
    @Synchronized
    fun put(openId: String, name: String) {
        if (openId.isBlank() || name.isBlank() || names[openId] == name) return
        val updated = names.toMutableMap().apply { put(openId, name) }
        val target = file ?: run {
            names = updated
            return
        }
        val directory = target.absoluteFile.parentFile
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "group-names.", ".tmp")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { gson.toJson(updated, it) }
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            names = updated
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun snapshot(): Map<String, String> = names

}

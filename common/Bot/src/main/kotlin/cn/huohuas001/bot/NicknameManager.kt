package cn.huohuas001.bot

import cn.huohuas001.bot.provider.BotShared
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/** Persistent QQ OpenID-to-nickname cache populated from group message events. */
object NicknameManager {
    private val nicknames = ConcurrentHashMap<String, String>()
    @Volatile private var file: File? = null

    fun initialize(directory: File?) {
        file = directory?.resolve("nicknames.dat")
        nicknames.clear()
        file?.takeIf(File::isFile)?.readLines(Charsets.UTF_8)?.forEach { line ->
            val parts = line.split('\t', limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                nicknames[parts[1]] = parts[0]
            }
        }
    }

    @Synchronized
    fun put(openId: String, nickname: String) {
        if (openId.isBlank() || nickname.isBlank() || nicknames.put(openId, nickname) == nickname) return
        val target = file ?: return
        val directory = target.absoluteFile.parentFile
        Files.createDirectories(directory.toPath())
        val temporary = Files.createTempFile(directory.toPath(), "nicknames.", ".tmp")
        try {
            Files.newBufferedWriter(temporary, Charsets.UTF_8).use { writer ->
                nicknames.toSortedMap().forEach { (id, name) ->
                    writer.append(name).append('\t').append(id).append('\n')
                }
            }
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    fun getNickname(openId: String): String? = nicknames[openId]
}

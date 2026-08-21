package cn.huohuas001.bot.state

import cn.huohuas001.bot.datapack.AdministratorAccessMode
import cn.huohuas001.bot.datapack.StoredCommandSettings
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties


/**
 * 读写 command-state.ini。
 *
 * 文件按职责分段并稳定排序，方便服主直接检查和手动维护。首次启动时会兼容导入
 * 旧版 command-state.properties，但不会删除旧文件。
 */
internal class HumanReadableStateFile {
    private var target: File? = null

    fun initialize(dataDirectory: File?): StoredCommandSettings {
        if (dataDirectory == null) {
            target = null
            return StoredCommandSettings()
        }

        target = dataDirectory.resolve("command-state.ini")
        if (target!!.isFile) return readIni(target!!)

        val legacyFile = dataDirectory.resolve("command-state.properties")
        if (!legacyFile.isFile) return StoredCommandSettings()

        val migrated = readLegacyProperties(legacyFile)
        save(migrated)
        return migrated
    }

    @Synchronized
    fun save(snapshot: StoredCommandSettings) {
        val outputFile = target ?: return
        outputFile.parentFile?.mkdirs()
        val temporaryFile = File(outputFile.absolutePath + ".tmp")

        temporaryFile.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.appendLine("# HuHoBot 群命令运行状态")
            writer.appendLine("# 建议仅在服务器停止时手动编辑此文件。")
            writer.appendLine()
            writeUserSection(writer, "administrators", snapshot.administrators)
            writeAuthenticationSection(writer, snapshot.authenticatedUsers)
            writeValueSection(writer, "administrator-modes", snapshot.administratorModes.mapValues { it.value.name })
            writeValueSection(writer, "full-forwarding", snapshot.fullForwarding.mapValues { it.value.toString() })
            writeValueSection(writer, "motd-blocked", snapshot.motdBlocked.mapValues { it.value.toString() })
        }

        try {
            Files.move(
                temporaryFile.toPath(),
                outputFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: Exception) {
            Files.move(temporaryFile.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun readIni(file: File): StoredCommandSettings {
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, MutableMap<String, String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()
        val motdBlocked = linkedMapOf<String, Boolean>()
        var section = ""

        file.readLines(Charsets.UTF_8).forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) return@forEach
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }

            val (groupId, rawValue) = line.split('=', limit = 2).takeIf { it.size == 2 }
                ?: return@forEach
            val value = rawValue.trim()
            when (section) {
                "administrators" -> administrators[groupId.trim()] = parseUsers(value)
                "authenticated-users" -> parseAuthentication(value).takeIf { it.isNotEmpty() }
                    ?.let { authenticatedUsers[groupId.trim()] = it.toMutableMap() }
                "administrator-modes" -> AdministratorAccessMode.entries
                    .firstOrNull { it.name.equals(value, ignoreCase = true) }
                    ?.let { administratorModes[groupId.trim()] = it }

                "full-forwarding" -> value.toBooleanStrictOrNull()
                    ?.let { fullForwarding[groupId.trim()] = it }

                "motd-blocked" -> value.toBooleanStrictOrNull()
                    ?.let { motdBlocked[groupId.trim()] = it }
            }
        }

        return StoredCommandSettings(
            administrators,
            authenticatedUsers,
            administratorModes,
            fullForwarding,
            motdBlocked
        )
    }

    private fun readLegacyProperties(file: File): StoredCommandSettings {
        val properties = Properties()
        FileInputStream(file).use(properties::load)
        val administrators = linkedMapOf<String, Set<String>>()
        val authenticatedUsers = linkedMapOf<String, MutableMap<String, String>>()
        val administratorModes = linkedMapOf<String, AdministratorAccessMode>()
        val fullForwarding = linkedMapOf<String, Boolean>()
        val motdBlocked = linkedMapOf<String, Boolean>()

        properties.stringPropertyNames().forEach { key ->
            val value = properties.getProperty(key).orEmpty()
            when {
                key.startsWith("admins.") -> administrators[key.removePrefix("admins.")] = parseUsers(value)
                key.startsWith("auth.") -> parseAuthentication(value).takeIf { it.isNotEmpty() }
                    ?.let { authenticatedUsers[key.removePrefix("auth.")] = it.toMutableMap() }
                key.startsWith("mode.") -> legacyMode(value)?.let {
                    administratorModes[key.removePrefix("mode.")] = it
                }

                key.startsWith("full.") -> fullForwarding[key.removePrefix("full.")] = value.toBoolean()
                key.startsWith("motd-blocked.") -> motdBlocked[key.removePrefix("motd-blocked.")] = value.toBoolean()
            }
        }
        return StoredCommandSettings(
            administrators,
            authenticatedUsers,
            administratorModes,
            fullForwarding,
            motdBlocked
        )
    }

    private fun legacyMode(value: String): AdministratorAccessMode? = when (value) {
        "onlyQQ" -> AdministratorAccessMode.QQ
        "onlyAdd" -> AdministratorAccessMode.MANUAL
        "both" -> AdministratorAccessMode.BOTH
        else -> null
    }

    private fun parseAuthentication(value: String): Map<String, String> = value.split(';', ',')
        .mapNotNull { entry ->
            val parts = entry.trim().split(':', limit = 2)
            when {
                parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank() ->
                    parts[0].trim() to parts[1].trim()
                parts.size == 1 && parts[0].isNotBlank() ->
                    parts[0].trim() to "unknown"
                else -> null
            }
        }
        .toMap()

    private fun writeAuthenticationSection(
        writer: java.io.BufferedWriter,
        values: Map<String, Map<String, String>>
    ) = writeValueSection(
        writer,
        "authenticated-users",
        values.mapValues { (_, users) -> users.toSortedMap().entries.joinToString("; ") { "${it.key}:${it.value}" } }
    )

    private fun parseUsers(value: String): Set<String> =
        value.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    private fun writeUserSection(
        writer: java.io.BufferedWriter,
        name: String,
        values: Map<String, Set<String>>
    ) = writeValueSection(writer, name, values.mapValues { (_, users) -> users.sorted().joinToString(", ") })

    private fun writeValueSection(
        writer: java.io.BufferedWriter,
        name: String,
        values: Map<String, String>
    ) {
        writer.appendLine("[$name]")
        values.toSortedMap().forEach { (groupId, value) -> writer.appendLine("$groupId = $value") }
        writer.appendLine()
    }
}

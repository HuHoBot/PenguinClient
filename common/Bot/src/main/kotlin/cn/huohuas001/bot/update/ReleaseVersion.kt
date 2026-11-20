package cn.huohuas001.bot.update

/** Numeric components are compared as normalized strings, so their size cannot overflow. */
internal class ReleaseVersion private constructor(
    private val numbers: List<String>,
    private val prerelease: List<String>
) : Comparable<ReleaseVersion> {
    val stable: Boolean get() = prerelease.isEmpty()

    override fun compareTo(other: ReleaseVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val comparison = compareNumber(numbers.getOrElse(index) { "0" }, other.numbers.getOrElse(index) { "0" })
            if (comparison != 0) return comparison
        }
        if (stable || other.stable) return when {
            stable && other.stable -> 0
            stable -> 1
            else -> -1
        }
        for (index in 0 until minOf(prerelease.size, other.prerelease.size)) {
            val left = prerelease[index]
            val right = other.prerelease[index]
            val leftNumeric = left.all { it in '0'..'9' }
            val rightNumeric = right.all { it in '0'..'9' }
            val comparison = when {
                leftNumeric && rightNumeric -> compareNumber(normalizeNumber(left), normalizeNumber(right))
                leftNumeric -> -1
                rightNumeric -> 1
                else -> left.compareTo(right)
            }
            if (comparison != 0) return comparison
        }
        return prerelease.size.compareTo(other.prerelease.size)
    }

    companion object {
        fun parse(value: String): ReleaseVersion? {
            var text = value.trim()
            if (text.startsWith('v') || text.startsWith('V')) text = text.substring(1)
            val buildSeparator = text.indexOf('+')
            if (buildSeparator >= 0) {
                if (!validIdentifiers(text.substring(buildSeparator + 1))) return null
                text = text.substring(0, buildSeparator)
            }
            val prereleaseSeparator = text.indexOf('-')
            val prerelease = if (prereleaseSeparator < 0) emptyList() else {
                val suffix = text.substring(prereleaseSeparator + 1)
                if (!validIdentifiers(suffix)) return null
                text = text.substring(0, prereleaseSeparator)
                suffix.split('.')
            }
            val numbers = text.split('.')
            if (numbers.size < 3 || numbers.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return null
            // SemVer numeric prerelease identifiers cannot contain leading zeroes.
            if (prerelease.any { it.length > 1 && it[0] == '0' && it.all { char -> char in '0'..'9' } }) return null
            return ReleaseVersion(numbers.map(::normalizeNumber), prerelease)
        }

        private fun validIdentifiers(value: String): Boolean =
            value.isNotEmpty() && !value.startsWith('.') && !value.endsWith('.') && !value.contains("..") &&
                value.all { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' || it == '-' || it == '.' }

        private fun normalizeNumber(value: String): String = value.trimStart('0').ifEmpty { "0" }

        private fun compareNumber(left: String, right: String): Int =
            if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)
    }
}

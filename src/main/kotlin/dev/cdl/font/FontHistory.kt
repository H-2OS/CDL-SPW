// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.font

object FontHistory {

    const val CAPACITY = 10

    private const val RECORD_SEPARATOR = '\n'

    fun serialize(families: List<String>): String {
        val seen = LinkedHashSet<String>(families.size)
        for (family in families) {
            val clean = sanitize(family)

            if (clean.isNotEmpty()) seen += clean

            if (seen.size >= CAPACITY) break
        }
        if (seen.isEmpty()) return ""
        return seen.joinToString(RECORD_SEPARATOR.toString()) { escape(it) }
    }

    fun parse(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val result = ArrayList<String>(CAPACITY)
            val seen = HashSet<String>(CAPACITY)
            for (record in raw.split(RECORD_SEPARATOR)) {
                val family = sanitize(unescape(record))
                if (family.isEmpty()) continue
                if (!seen.add(family)) continue
                result += family
                if (result.size >= CAPACITY) break
            }
            result
        } catch (t: Throwable) {

            emptyList()
        }
    }

    fun record(families: List<String>, used: String): List<String> {
        val clean = sanitize(used)
        if (clean.isEmpty()) return parse(serialize(families))

        val existing = parse(serialize(families))
        val reordered = ArrayList<String>(existing.size + 1)
        reordered += clean
        for (family in existing) {
            if (family != clean) reordered += family
        }
        return parse(serialize(reordered))
    }

    private fun sanitize(family: String): String {
        val builder = StringBuilder(family.length)
        for (ch in family) {

            if (ch.code < 0x20 || ch.code == 0x7F) continue
            builder.append(ch)
        }
        return builder.toString().trim()
    }

    private fun escape(record: String): String {
        val builder = StringBuilder(record.length)
        for (ch in record) {
            when (ch) {
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun unescape(record: String): String {
        if (!record.contains('\\')) return record
        val builder = StringBuilder(record.length)
        var index = 0
        while (index < record.length) {
            val ch = record[index]
            if (ch != '\\' || index == record.length - 1) {
                builder.append(ch)
                index++
                continue
            }
            when (val next = record[index + 1]) {
                '\\' -> builder.append('\\')
                'n' -> builder.append('\n')
                'r' -> builder.append('\r')

                else -> {
                    builder.append(ch)
                    builder.append(next)
                }
            }
            index += 2
        }
        return builder.toString()
    }
}

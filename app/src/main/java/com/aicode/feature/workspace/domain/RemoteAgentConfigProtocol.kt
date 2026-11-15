package com.aicode.feature.workspace.domain

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

internal data class RemoteAgentConfigFingerprint(val sha256: String, val size: Int)

internal object RemoteAgentConfigProtocol {
    const val MAX_FILES = 4096
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    const val MAX_PATH_BYTES = 16 * 1024
    const val MAX_WIRE_BYTES = 48 * 1024 * 1024
    const val MAX_MANIFEST_BYTES = 8 * 1024 * 1024
    const val MAX_REQUEST_BYTES = 2 * 1024 * 1024
    private val hashPattern = Regex("[0-9a-f]{64}")
    private val jsonDecimal = Regex("0|[1-9][0-9]*")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    fun strictText(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        throw IOException("Invalid UTF-8 in remote configuration", e)
    }

    fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(limit, 8192))
        val buffer = ByteArray(8192)
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Remote configuration read interrupted")
            val count = input.read(buffer)
            if (count < 0) return output.toByteArray()
            if (output.size().toLong() + count > limit) throw IOException("Remote configuration exceeds byte limit")
            output.write(buffer, 0, count)
        }
    }

    fun manifest(bytes: ByteArray, kind: AgentConfigKind): Map<String, RemoteAgentConfigFingerprint> {
        if (bytes.size > MAX_MANIFEST_BYTES) throw IOException("Remote manifest exceeds byte limit")
        val lines = lines(bytes, "AICODE_CONFIG_1 M")
        val candidates = linkedMapOf<String, RemoteAgentConfigFingerprint>()
        var total = 0L
        for (line in lines) {
            val fields = line.split('\t')
            if (fields.size != 4 || fields[0] != "F" || !hashPattern.matches(fields[2])) {
                throw IOException("Malformed remote configuration manifest")
            }
            val path = decodePath(fields[1])
            if (!eligible(path, kind)) throw IOException("Unexpected remote configuration path")
            val size = decimal(fields[3])
            if (size > MAX_FILE_BYTES || candidates.size >= MAX_FILES) throw IOException("Remote configuration exceeds file limit")
            total += size
            if (total > MAX_TOTAL_BYTES || candidates.put(path, RemoteAgentConfigFingerprint(fields[2], size)) != null) {
                throw IOException("Invalid remote configuration manifest entries")
            }
        }
        if (kind == AgentConfigKind.AGENTS) return candidates.toSortedMap()
        return candidates.keys.groupBy { it.substringBeforeLast('/', "") }
            .values.map { paths ->
                paths.minWith(compareBy<String> { if (it.substringAfterLast('/').equals("SKILL.md", true)) 0 else 1 }.thenBy { it })
            }.sorted().associateWith { candidates.getValue(it) }
    }

    fun bodies(bytes: ByteArray, expected: Map<String, RemoteAgentConfigFingerprint>): Map<String, String> {
        if (bytes.size > MAX_WIRE_BYTES || expected.size > MAX_FILES) throw IOException("Remote configuration exceeds batch limit")
        val result = linkedMapOf<String, String>()
        var total = 0L
        for (line in lines(bytes, "AICODE_CONFIG_1 B")) {
            val fields = line.split('\t')
            if (fields.size != 3 || fields[0] != "B") throw IOException("Malformed remote configuration body")
            val path = decodePath(fields[1])
            val fingerprint = expected[path] ?: throw IOException("Unrequested remote configuration body")
            if (result.containsKey(path)) throw IOException("Duplicate remote configuration body")
            val body = decode64(fields[2], MAX_FILE_BYTES)
            total += body.size
            if (total > MAX_TOTAL_BYTES || body.size != fingerprint.size || sha256(body) != fingerprint.sha256) {
                throw IOException("Remote configuration changed during batch read")
            }
            result[path] = strictText(body)
        }
        if (result.keys != expected.keys) throw IOException("Incomplete remote configuration batch")
        return result
    }

    fun request(paths: Collection<String>): ByteArray {
        if (paths.size > MAX_FILES) throw IOException("Remote configuration exceeds file limit")
        val text = buildString {
            for (path in paths) {
                validatePath(path)
                append(Base64.getEncoder().encodeToString(path.toByteArray(Charsets.UTF_8))).append('\n')
            }
            append("END\n")
        }
        return text.toByteArray(Charsets.US_ASCII).also {
            if (it.size > MAX_REQUEST_BYTES) throw IOException("Remote configuration request exceeds byte limit")
        }
    }

    fun manifestCommand(root: String, kind: AgentConfigKind): String {
        validateRoot(root)
        val pattern = if (kind == AgentConfigKind.SKILLS) {
            "\\( -iname 'SKILL.md' -o -iname 'CLAUDE.md' \\)"
        } else "-iname '*.md'"
        val depth = if (kind == AgentConfigKind.SKILLS) 4 else 1
        val worker = """
            set -eu
            for p do
                if [ -L "${'$'}p" ] && [ ! -f "${'$'}p" ]; then continue; fi
                encoded=${'$'}(printf '%s' "${'$'}{p#./}" | base64 | tr -d '\n')
                printf '%s\n' "${'$'}encoded"
            done
        """.trimIndent()
        val script = """
            set -eu
            root=${'$'}1
            printf 'AICODE_CONFIG_1 M\n'
            if [ ! -e "${'$'}root" ] && [ ! -L "${'$'}root" ]; then
                probe=${'$'}root
                while [ ! -e "${'$'}probe" ] && [ ! -L "${'$'}probe" ]; do
                    parent=${'$'}{probe%/*}
                    [ -n "${'$'}parent" ] || parent=/
                    [ "${'$'}parent" != "${'$'}probe" ] || exit 23
                    probe=${'$'}parent
                done
                [ -d "${'$'}probe" ] && [ -r "${'$'}probe" ] && [ -x "${'$'}probe" ] || exit 23
                printf 'END\n'
                exit 0
            fi
            [ -d "${'$'}root" ] && [ -r "${'$'}root" ] && [ -x "${'$'}root" ] || exit 23
            cd "${'$'}root"
            {
                find . -maxdepth $depth \( -type f -o -type l \) $pattern -exec sh -c ${quote(worker)} aicode-config {} + || exit 30
                printf 'END\n'
            } | (
                count=0
                total=0
                ended=0
                while IFS= read -r encoded; do
                    if [ "${'$'}encoded" = END ]; then ended=1; break; fi
                    count=${'$'}((count + 1))
                    [ "${'$'}count" -le $MAX_FILES ] || exit 24
                    p=${'$'}(printf '%s' "${'$'}encoded" | base64 -d || exit 25; printf '.')
                    p=./${'$'}{p%.}
                    [ -f "${'$'}p" ] && [ -r "${'$'}p" ] || exit 21
                    size=${'$'}(wc -c < "${'$'}p")
                    size=${'$'}((size + 0))
                    total=${'$'}((total + size))
                    [ "${'$'}size" -le $MAX_FILE_BYTES ] && [ "${'$'}total" -le $MAX_TOTAL_BYTES ] || exit 22
                    hash=${'$'}(sha256sum < "${'$'}p")
                    hash=${'$'}{hash%% *}
                    printf 'F\t%s\t%s\t%s\n' "${'$'}encoded" "${'$'}hash" "${'$'}size"
                done
                [ "${'$'}ended" -eq 1 ] || exit 28
                printf 'END\n'
            )
        """.trimIndent()
        return "sh -c ${quote(script)} aicode-config ${quote(root)}"
    }

    fun bodiesCommand(root: String): String {
        validateRoot(root)
        val script = """
            set -eu
            cd "${'$'}1"
            count=0
            total=0
            ended=0
            printf 'AICODE_CONFIG_1 B\n'
            while IFS= read -r encoded; do
                if [ "${'$'}encoded" = END ]; then ended=1; break; fi
                count=${'$'}((count + 1))
                [ "${'$'}count" -le $MAX_FILES ] || exit 24
                path=${'$'}(printf '%s' "${'$'}encoded" | base64 -d || exit 25; printf '.')
                path=${'$'}{path%.}
                case "${'$'}path" in ''|/*|..|../*|*/../*|*/..|./*|*/./*|*/.|*//*) exit 25;; esac
                path=./${'$'}path
                [ -f "${'$'}path" ] && [ -r "${'$'}path" ] || exit 26
                size=${'$'}(wc -c < "${'$'}path")
                size=${'$'}((size + 0))
                total=${'$'}((total + size))
                [ "${'$'}size" -le $MAX_FILE_BYTES ] && [ "${'$'}total" -le $MAX_TOTAL_BYTES ] || exit 27
                printf 'B\t%s\t' "${'$'}encoded"
                head -c ${MAX_FILE_BYTES + 1} < "${'$'}path" | base64 | tr -d '\n'
                printf '\n'
            done
            [ "${'$'}ended" -eq 1 ] || exit 28
            if IFS= read -r extra; then exit 29; fi
            printf 'END\n'
        """.trimIndent()
        return "sh -c ${quote(script)} aicode-config ${quote(root)}"
    }

    private fun lines(bytes: ByteArray, header: String): List<String> {
        val text = strictText(bytes)
        if (!text.endsWith('\n')) throw IOException("Truncated remote configuration protocol")
        val lines = text.dropLast(1).split('\n')
        if (lines.size < 2 || lines.first() != header || lines.last() != "END" || lines.size - 2 > MAX_FILES) {
            throw IOException("Malformed remote configuration protocol")
        }
        return lines.subList(1, lines.lastIndex)
    }

    private fun decodePath(encoded: String): String = strictText(decode64(encoded, MAX_PATH_BYTES)).also(::validatePath)

    private fun decode64(encoded: String, maxBytes: Int): ByteArray {
        if (encoded.length > (maxBytes.toLong() + 2) / 3 * 4) throw IOException("Remote configuration field exceeds byte limit")
        val bytes = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid base64 in remote configuration protocol", e)
        }
        if (bytes.size > maxBytes || Base64.getEncoder().encodeToString(bytes) != encoded) {
            throw IOException("Invalid base64 in remote configuration protocol")
        }
        return bytes
    }

    private fun decimal(value: String): Int {
        if (!jsonDecimal.matches(value)) throw IOException("Invalid remote file size")
        return value.toIntOrNull() ?: throw IOException("Remote file size exceeds limit")
    }

    private fun eligible(path: String, kind: AgentConfigKind): Boolean {
        val parts = path.split('/')
        return when (kind) {
            AgentConfigKind.SKILLS -> parts.size <= 4 &&
                (parts.last().equals("SKILL.md", true) || parts.last().equals("CLAUDE.md", true))
            AgentConfigKind.AGENTS -> parts.size == 1 && parts.last().endsWith(".md", true)
        }
    }

    private fun validatePath(path: String) {
        if (path.isEmpty() || '\u0000' in path || path.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES ||
            path.split('/').any { it.isEmpty() || it == "." || it == ".." }
        ) throw IOException("Invalid remote configuration relative path")
    }

    private fun validateRoot(root: String) {
        if (!root.startsWith('/') || '\u0000' in root || root.toByteArray(Charsets.UTF_8).size > MAX_PATH_BYTES) {
            throw IOException("Invalid remote configuration root")
        }
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

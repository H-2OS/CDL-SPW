// SPDX-License-Identifier: AGPL-3.0-only
package dev.cdl.cover

import java.awt.image.BufferedImage
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong

class CoverArtProvider {

    private val lock = Any()

    @Volatile
    private var cachedPath: String = ""

    @Volatile
    private var cachedImage: BufferedImage? = null

    @Volatile
    private var loadAttempted: Boolean = false

    @Volatile
    private var loadInFlight: Boolean = false

    private val submissions = AtomicLong(0L)

    val loadSubmissions: Long get() = submissions.get()

    private val generation = AtomicLong(0L)

    private val executor = Executors.newSingleThreadExecutor(
        ThreadFactory { runnable ->
            Thread(runnable, "cdl-cover").apply { isDaemon = true }
        }
    )

    fun coverFor(audioPath: String): BufferedImage? {
        if (audioPath.isEmpty()) return null

        if (cachedPath != audioPath) {
            synchronized(lock) {
                if (cachedPath != audioPath) {
                    cachedPath = audioPath
                    cachedImage = null
                    loadAttempted = false

                    loadInFlight = false
                    generation.incrementAndGet()
                }
            }
        }

        cachedImage?.let { return it }

        if (loadAttempted) return null

        synchronized(lock) {
            if (loadInFlight) return null
            loadInFlight = true
        }

        val requestedPath = audioPath
        val requestedGeneration = generation.get()
        submissions.incrementAndGet()
        executor.execute {
            val image = runCatching { loadCover(requestedPath) }
                .onFailure {  }
                .getOrNull()
            synchronized(lock) {

                loadInFlight = false

                if (requestedGeneration != generation.get()) return@execute
                if (cachedPath != requestedPath) return@execute
                cachedImage = image
                loadAttempted = true
            }
        }
        return null
    }

    fun close() {
        executor.shutdownNow()
        synchronized(lock) {
            cachedImage = null
            cachedPath = ""
            loadAttempted = false

            loadInFlight = false
            generation.incrementAndGet()
        }
    }

    private fun loadCover(audioPath: String): BufferedImage? {
        val path = runCatching { Path.of(audioPath) }.getOrNull() ?: return null

        if (Files.isRegularFile(path) && Files.isReadable(path)) {
            embeddedCover(path)?.let { return it }
        }
        return sidecarCover(path)
    }

    private fun embeddedCover(path: Path): BufferedImage? {
        val header = runCatching { readHeader(path, 12) }.getOrNull() ?: return null
        if (header.size < 4) return null

        return when {
            isId3(header) -> runCatching { readId3Cover(path) }.getOrNull()
            isFlac(header) -> runCatching { readFlacCover(path) }.getOrNull()
            isMp4(header) -> runCatching { readMp4Cover(path) }.getOrNull()
            else -> null
        }
    }

    private fun readId3Cover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val header = ByteArray(10)
            if (file.read(header) != 10) return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return null
            }
            val majorVersion = header[3].toInt() and 0xFF

            val tagSize = synchsafeInt(header, 6)

            val fileLength = runCatching { file.length() }.getOrElse { 0L }
            if (tagSize <= 0L || tagSize > fileLength) return null

            val tagEnd = 10L + tagSize
            val body = ByteArray(tagSize.toInt())
            if (file.read(body) != body.size) return null
            file.seek(10L)

            var fallback: ByteArray? = null
            var offset = 0L
            while (offset + 10 <= tagSize) {
                file.seek(10L + offset)
                val frameHeader = ByteArray(10)
                if (file.read(frameHeader) != 10) break

                val frameId = String(frameHeader, 0, 4, StandardCharsets.ISO_8859_1)

                if (frameId[0] == '\u0000') break

                val frameSize: Long = if (majorVersion >= 4) {
                    synchsafeInt(frameHeader, 4)
                } else {
                    (bigEndianInt(frameHeader, 4) ?: break).toLong()
                }
                if (frameSize <= 0L) break

                if (offset + 10L + frameSize > tagSize) break
                val nextOffset = offset + 10L + frameSize

                if (frameId == "APIC") {
                    file.seek(10L + offset + 10L)
                    val frame = ByteArray(frameSize.toInt())
                    if (file.read(frame) != frame.size) break

                    val pictureType = apicPictureType(frame)
                    val data = apicImageData(frame)
                    if (data != null && data.isNotEmpty()) {

                        if (pictureType == PICTURE_TYPE_FRONT_COVER) return decode(data)

                        if (fallback == null) fallback = data
                    }
                }
                offset = nextOffset
            }
            return fallback?.let { decode(it) }
        }
    }

    private fun apicPictureType(frame: ByteArray): Int {

        val encoding = frame[0].toInt() and 0xFF

        val mimeEnd = indexOfNul(frame, 1, singleByteNul = true) ?: return -1
        val typeIndex = mimeEnd + 1
        if (typeIndex >= frame.size) return -1
        return frame[typeIndex].toInt() and 0xFF
    }

    private fun apicImageData(frame: ByteArray): ByteArray? {
        if (frame.size < 4) return null
        val encoding = frame[0].toInt() and 0xFF
        val mimeEnd = indexOfNul(frame, 1, singleByteNul = true) ?: return null
        val typeIndex = mimeEnd + 1
        if (typeIndex >= frame.size) return null

        val descriptionEnd = indexOfNul(frame, typeIndex + 1, singleByteNul = !isWideEncoding(encoding))
        if (descriptionEnd == null) return null
        val dataStart = descriptionEnd + if (isWideEncoding(encoding)) 2 else 1
        if (dataStart >= frame.size) return null
        return frame.copyOfRange(dataStart, frame.size)
    }

    private fun isWideEncoding(encoding: Int): Boolean = encoding == 1 || encoding == 2

    private fun indexOfNul(bytes: ByteArray, from: Int, singleByteNul: Boolean): Int? {
        if (from >= bytes.size) return null
        if (singleByteNul) {
            for (index in from until bytes.size) {
                if (bytes[index].toInt() == 0) return index
            }
            return null
        }
        var index = from
        while (index + 1 < bytes.size) {
            if (bytes[index].toInt() == 0 && bytes[index + 1].toInt() == 0) return index
            index += 2
        }
        return null
    }

    private fun readFlacCover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val magic = ByteArray(4)
            if (file.read(magic) != 4) return null
            if (String(magic, StandardCharsets.ISO_8859_1) != "fLaC") return null

            var fallback: ByteArray? = null
            var isLast = false
            var guard = 0
            while (!isLast) {

                if (guard++ > MAX_FLAC_BLOCKS) break

                val blockHeader = ByteArray(4)
                if (file.read(blockHeader) != 4) break
                val headerByte = blockHeader[0].toInt() and 0xFF
                isLast = (headerByte and 0x80) != 0
                val blockType = headerByte and 0x7F

                val blockLength = bigEndianInt24(blockHeader, 1) ?: break
                if (blockLength < 0) break

                if (blockType == FLAC_BLOCK_PICTURE) {
                    val block = ByteArray(blockLength)
                    if (file.read(block) != block.size) break

                    val pictureType = bigEndianInt(block, 0)
                    val data = flacPictureData(block)
                    if (data != null && data.isNotEmpty()) {

                        if (pictureType == PICTURE_TYPE_FRONT_COVER) return decode(data)
                        if (fallback == null) fallback = data
                    }
                } else {

                    if (!skipFully(file, blockLength.toLong())) break
                }
            }
            return fallback?.let { decode(it) }
        }
    }

    private fun flacPictureData(block: ByteArray): ByteArray? {
        var cursor = 4

        val mimeLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4 + mimeLength

        val descriptionLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4 + descriptionLength

        cursor += 16
        val dataLength = bigEndianInt(block, cursor) ?: return null
        cursor += 4
        if (cursor < 0 || dataLength <= 0) return null
        if (cursor + dataLength > block.size) {

            val remaining = block.size - cursor
            if (remaining <= 0) return null
            return block.copyOfRange(cursor, block.size)
        }
        return block.copyOfRange(cursor, cursor + dataLength)
    }

    private fun readMp4Cover(path: Path): BufferedImage? {
        RandomAccessFile(path.toFile(), "r").use { file ->
            val window = runCatching { readHeader(file, MP4_SCAN_LIMIT, path) }.getOrNull() ?: return null
            val covrIndex = indexOfAtom(window, "covr")
            if (covrIndex < 0) return null

            val covrStart = covrIndex + 8
            if (covrStart >= window.size) return null

            val dataAtom = indexOfAtom(window, "data", from = covrStart)
            if (dataAtom < 0) return null

            val payloadStart = dataAtom + 8 + 8
            if (payloadStart >= window.size) return null
            val payload = window.copyOfRange(payloadStart, window.size)

            return decode(payload)
        }
    }

    private fun indexOfAtom(window: ByteArray, type: String, from: Int = 0): Int {
        val bytes = type.toByteArray(StandardCharsets.ISO_8859_1)
        if (bytes.size != 4) return -1
        var index = from
        while (index + 4 <= window.size) {
            if (window[index] == bytes[0] &&
                window[index + 1] == bytes[1] &&
                window[index + 2] == bytes[2] &&
                window[index + 3] == bytes[3]
            ) {
                return index
            }
            index++
        }
        return -1
    }

    private fun sidecarCover(audioPath: Path): BufferedImage? {
        val directory = audioPath.parent ?: return null
        for (name in SIDECAR_NAMES) {
            val candidate = runCatching { directory.resolve(name) }.getOrNull() ?: continue
            if (!runCatching { Files.isRegularFile(candidate) }.getOrDefault(false)) continue
            val image = runCatching {

                decodeCoverFile(candidate)
            }.getOrNull()
            if (image != null) return image
        }
        return null
    }

    private fun decode(data: ByteArray): BufferedImage? = decodeCoverImage(data)

    private fun readHeader(path: Path, count: Int): ByteArray =
        RandomAccessFile(path.toFile(), "r").use { readHeader(it, count, path) }

    private fun readHeader(file: RandomAccessFile, count: Int, path: Path): ByteArray {
        val available = runCatching { file.length() }.getOrElse { 0L }
        val size = minOf(count.toLong(), available).toInt()
        if (size <= 0) return ByteArray(0)
        val buffer = ByteArray(size)
        val read = file.read(buffer)
        return if (read <= 0) ByteArray(0) else if (read == buffer.size) buffer else buffer.copyOf(read)
    }

    private fun skipFully(file: RandomAccessFile, count: Long): Boolean =
        runCatching { file.seek(file.filePointer + count); true }.getOrDefault(false)

    private fun synchsafeInt(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return -1L
        var result = 0L
        for (index in 0 until 4) {
            result = (result shl 7) or (bytes[offset + index].toLong() and 0x7FL)
        }
        return result
    }

    private fun bigEndianInt(bytes: ByteArray, offset: Int): Int? = bigEndianInt(bytes, offset, 4)

    private fun bigEndianInt(bytes: ByteArray, offset: Int, size: Int): Int? {
        if (offset < 0 || offset + size > bytes.size) return null
        var result = 0
        for (index in 0 until size) {
            result = (result shl 8) or (bytes[offset + index].toInt() and 0xFF)
        }
        return result
    }

    private fun bigEndianInt24(bytes: ByteArray, offset: Int): Int? = bigEndianInt(bytes, offset, 3)

    private fun isId3(header: ByteArray): Boolean =
        header.size >= 3 &&
            header[0] == 'I'.code.toByte() &&
            header[1] == 'D'.code.toByte() &&
            header[2] == '3'.code.toByte()

    private fun isFlac(header: ByteArray): Boolean =
        header.size >= 4 && String(header, 0, 4, StandardCharsets.ISO_8859_1) == "fLaC"

    private fun isMp4(header: ByteArray): Boolean =
        header.size >= 8 && String(header, 4, 4, StandardCharsets.ISO_8859_1) == "ftyp"

    private companion object {

        @Suppress("unused")
        const val MAX_CACHED = 1

        const val PICTURE_TYPE_FRONT_COVER = 3

        const val FLAC_BLOCK_PICTURE = 6

        const val MAX_FLAC_BLOCKS = 256

        const val MP4_SCAN_LIMIT = 4 * 1024 * 1024

        val SIDECAR_NAMES = listOf(
            "cover.jpg",
            "cover.png",
            "folder.jpg",
            "folder.png",
            "front.jpg",
            "front.png",
        )
    }
}

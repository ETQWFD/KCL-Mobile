/*
 * KCL Mobile
 * Copyright (C) 2025 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 */

package com.kcl.pcl.game.download.engine

import com.kcl.pcl.path.URL_USER_AGENT
import com.kcl.pcl.utils.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "Fetcher"

/** 响应的状态码、最终地址与响应头集合（键不区分大小写） */
class ResponseInfo(
    val code: Int,
    val url: URL,
    headers: Map<String, List<String>>
) {
    private val values: Map<String, String> = headers.entries
        .associate { it.key.lowercase() to it.value.first() }

    fun header(name: String): String? = values[name.lowercase()]

    fun headerAsLong(name: String): Long = header(name)?.trim()?.toLongOrNull() ?: -1L

    companion object {
        /**
         * 按索引枚举响应头
         */
        fun of(code: Int, url: URL, connection: HttpURLConnection): ResponseInfo {
            val headers = LinkedHashMap<String, MutableList<String>>()
            var index = 0
            while (true) {
                val value = connection.getHeaderField(index) ?: break
                val name = connection.getHeaderFieldKey(index)
                if (!name.isNullOrEmpty()) {
                    headers.getOrPut(name) { mutableListOf() }.add(value)
                }
                index++
            }
            return ResponseInfo(code, url, headers)
        }
    }
}

/** 响应体传输编码；不支持 broker 压缩（如 br），遇到即视为该次尝试失败 */
enum class ContentEncoding {
    IDENTITY, GZIP;

    fun wrap(input: InputStream): InputStream =
        if (this == GZIP) GZIPInputStream(input) else input

    companion object {
        fun fromHeaders(response: ResponseInfo): ContentEncoding {
            val encoding = response.header("content-encoding") ?: return IDENTITY
            return when {
                encoding.isEmpty() || encoding == "identity" -> IDENTITY
                encoding.equals("gzip", ignoreCase = true) -> GZIP
                else -> throw IOException("Unsupported content encoding: $encoding")
            }
        }
    }
}

/**
 * Range 续传的资格上下文：只有 200 OK + accept-ranges: bytes + identity 编码 + 已知长度，
 * 且携带强 ETag 或 Last-Modified 的响应才具备续传资格。
 */
class ResumeContext private constructor(
    private val url: URL,
    private val contentLength: Long,
    private val strongETag: String?,
    private val lastModified: String?
) {
    /** 未压缩口径的已收字节，与 Content-Range 的起点语义对齐 */
    var countUncompressed = 0L
        private set

    fun addBytes(count: Long) {
        countUncompressed += count
    }

    fun ifRange(): String = strongETag ?: lastModified!!

    /** 206 响应是否与既有分段匹配：校验长度、验证器与 Content-Range 的逐字段一致性 */
    fun canResume(code: Int, response: ResponseInfo): Boolean {
        if (code != HttpURLConnection.HTTP_PARTIAL) return false
        if (ContentEncoding.fromHeaders(response) != ContentEncoding.IDENTITY) return false

        val bodyLength = response.headerAsLong("content-length")
        if (contentLength != bodyLength + countUncompressed) return false

        if (strongETag != null) {
            if (strongETag != response.header("etag")) return false
        } else {
            if (url != response.url) return false
            if (lastModified != response.header("last-modified").orEmpty()) return false
        }

        val match = CONTENT_RANGE_PATTERN.find(response.header("content-range").orEmpty())
            ?: return false
        val (start, end, total) = match.destructured
        val startValue = start.toLongOrNull() ?: return false
        val endValue = end.toLongOrNull() ?: return false
        val totalValue = total.toLongOrNull() ?: return false

        if (startValue != countUncompressed || endValue < startValue || totalValue != contentLength) {
            return false
        }
        return endValue - startValue + 1 == bodyLength
    }

    fun hasPartialContent(): Boolean = countUncompressed in 1 until contentLength

    companion object {
        private val CONTENT_RANGE_PATTERN = Regex("""bytes ([0-9]+)-([0-9]+)/([0-9]+)""")

        fun of(response: ResponseInfo): ResumeContext? {
            if (response.code != HttpURLConnection.HTTP_OK) return null
            if (!response.header("accept-ranges").orEmpty().equals("bytes", ignoreCase = true)) return null
            if (ContentEncoding.fromHeaders(response) != ContentEncoding.IDENTITY) return null

            val contentLength = response.headerAsLong("content-length")
            if (contentLength < 0) return null

            val eTag = response.header("etag")
            val strongETag = eTag?.takeIf { it.isNotBlank() && !it.startsWith("W/", ignoreCase = true) }
            val lastModified = response.header("last-modified").orEmpty()
            if (strongETag == null && lastModified.isBlank()) return null

            return ResumeContext(response.url, contentLength, strongETag, lastModified)
        }
    }
}

/** 单个候选源重试耗尽后的失败包装 */
class DownloadException(
    val url: String,
    cause: Throwable
) : IOException("Unable to download $url, ${cause.message}", cause)

/** 下载内容的校验和与声明不符 */
class ChecksumMismatchException(
    val algorithm: String,
    val expected: String,
    val actual: String
) : IOException("Checksum mismatch ($algorithm): expected=$expected, actual=$actual")

/**
 * [Modified from HMCL FetchTask](https://github.com/HMCL-dev/HMCL/blob/59bcc7fe/HMCLCore/src/main/java/org/jackhuang/hmcl/task/FetchTask.java)
 *
 * 候选源顺序下载的核心实现：单源内至多重试 [DEFAULT_RETRY] 次（间隔 200ms），
 * 手动跟随重定向（上限 20 跳），Range 断点续传（强校验），连接与读取超时 10s；
 * 4xx 视为该源无货（不重试、立即换下一候选），写盘损坏从头重试且不消耗重试次数。
 */
object Fetcher {
    const val DEFAULT_RETRY = 5

    /** 多候选源时，非最后一个源的快速换源重试次数（见 [downloadFile]） */
    private const val FALLBACK_RETRY = 1

    private const val TIMEOUT_MILLIS = 10_000
    private const val MAX_REDIRECTS = 20
    private const val RETRY_BACKOFF_MILLIS = 200L

    /**
     * 读盘缓冲。32KB 时单条连接在较快的镜像上每秒要配对做几十次
     * `InputStream.read` + `FileChannel.write` 系统调用；翻倍到 64KB 可减半配对次数，
     * 对大文件（客户端 jar、LWJGL natives）吞吐更友好，与电脑版本轮「加大读取缓冲」对齐。
     * 文件级并发上限为 16（见 BatchDownloader.DEFAULT_MAX_CONNECTIONS），
     * 全部在途连接合计缓冲也仅约 1MB，移动端内存无压力。
     */
    private const val BUFFER_SIZE = 64 * 1024

    /**
     * 单文件多连接分片并行下载（对齐电脑版 NetTask 思路）：
     * 支持 `accept-ranges: bytes` 的源（mojang / bmclapi 镜像均支持）上，把大文件按
     * [CHUNK_TARGET_BYTES] 切成至多 [maxChunksForHeap] 段，每段一条独立 Range 连接并行落盘，
     * 再按序合并并复用 [FileSink] 做整文件 SHA-1 校验与原子替换。
     *
     * 只对 ≥ [CHUNK_MIN_BYTES] 的文件启用：批量里的海量小文件（assets / 小 jar）仍走原单连接路径，
     * 避免为它们付出分片探针开销与 socket 放大。单文件占 BatchDownloader 一个文件名额，
     * 内部并发数随进程 Java 堆自适应（见 [maxChunksForHeap]）：低端机（≤192MB heap）只开 2 路，
     * 中端机 3-4 路，宽裕机最多 6 路；真正会被分片的大文件每批只有个位数（client.jar 等），
     * 全局在途连接数仍贴近文件级并发 16 的设计上限，不会回到原先 64 路的 NAT / 限流风险区。
     *
     * OOM 防护：分片下载过程中一旦抛 OOM（或别的内存类 Error），立刻清理临时段并回落到单连接路径，
     * 不让并行 socket 缓冲把本就紧张的低端机堆压爆。
     */
    private const val CHUNK_MIN_BYTES = 4L * 1024 * 1024
    private const val CHUNK_TARGET_BYTES = 2L * 1024 * 1024

    /** 各档堆上限对应的最大分片数；低端机少开几路，避免并行 socket 缓冲 OOM */
    private const val CHUNKS_HEAP_TINY = 2      // heap ≤ 192MB
    private const val CHUNKS_HEAP_SMALL = 3     // heap ≤ 384MB
    private const val CHUNKS_HEAP_MID = 4       // heap ≤ 640MB
    private const val CHUNKS_HEAP_LARGE = 6     // heap > 640MB

    /**
     * 按进程 Java 堆上限返回允许的最大分片数。Android 上 `Runtime.maxMemory()` 是 zygote 给本进程
     * 划的硬上限，低端机（mt6765 / 1-2GB 真机）常见 192-256MB；此时每路 64KB 缓冲看似不多，
     * 但加上 HttpURLConnection 内部缓冲与 native socket buffer，6 路并行足以把堆顶到 OOM。
     */
    private fun maxChunksForHeap(): Int {
        val maxHeap = Runtime.getRuntime().maxMemory()
        return when {
            maxHeap <= 192L * 1024 * 1024 -> CHUNKS_HEAP_TINY
            maxHeap <= 384L * 1024 * 1024 -> CHUNKS_HEAP_SMALL
            maxHeap <= 640L * 1024 * 1024 -> CHUNKS_HEAP_MID
            else -> CHUNKS_HEAP_LARGE
        }
    }

    /** Content-Range 形如 `bytes start-end/total`，分片探测只取 total */
    private val CONTENT_RANGE_TOTAL = Regex("""bytes \d+-\d+/(\d+)""")

    /** 一个闭区间字节分片 [start, end] */
    private class ChunkRange(val start: Long, val end: Long)

    /**
     * 按候选源顺序下载文件；全部候选耗尽后抛 [AllSourcesFailedException]，
     * message 内含每个源，因果链保留最后一次失败。
     *
     * 多候选源时，除最后一个之外的源只重试 [FALLBACK_RETRY] 次就换源：
     * 单源内一次尝试最长 10s，满额重试（5 次）意味着源挂掉时**每个文件**要先白等 50s
     * 才轮到下一个源，整批下载看起来就是「卡住」。
     *
     * @param onBytes 每次成功落盘的增量字节回调（未压缩口径）
     */
    suspend fun downloadFile(
        urls: List<String>,
        targetFile: File,
        sha1: String? = null,
        retry: Int = DEFAULT_RETRY,
        onBytes: (Long) -> Unit = {}
    ) {
        require(urls.isNotEmpty()) { "At least one URL is required" }
        require(retry > 0) { "Retry count must be greater than 0" }

        val candidates = urls.distinct()
        val failures = mutableListOf<DownloadException>()
        candidates.forEachIndexed { index, candidate ->
            val url = try {
                URL(candidate)
            } catch (e: Exception) {
                failures += DownloadException(candidate, IOException("Invalid URL", e))
                return@forEachIndexed
            }
            // 换源策略：非最后一个候选源早点换源，最后一个源用满重试次数
            val attemptRetry = if (index == candidates.lastIndex) {
                retry
            } else {
                minOf(retry, FALLBACK_RETRY)
            }
            try {
                // 优先尝试多连接分片并行下载（仅对支持 Range 的大文件生效）；
                // 返回 false 表示该源不适合分片，回落到原单连接路径（含断点续传/重试/校验）。
                if (!downloadCandidateChunked(url, targetFile, sha1, attemptRetry, onBytes)) {
                    downloadCandidate(url, targetFile, sha1, attemptRetry, onBytes)
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadException) {
                Logger.warning(TAG, "Candidate source failed: $candidate", e)
                failures += e
            }
        }

        val last = failures.removeAt(failures.lastIndex)
        failures.forEach { last.addSuppressed(it) }
        val detail = candidates.joinToString(separator = "\n") { "- $it" }
        throw AllSourcesFailedException("All ${candidates.size} candidate source(s) failed:\n$detail", last)
    }

    /** 单个候选源的跨尝试状态：落盘上下文与续传资格在重试间延续 */
    private class CandidateState {
        var sink: FileSink? = null
        var resume: ResumeContext? = null

        /** 丢弃落盘上下文：关闭并删除临时文件 */
        fun discardSink() {
            val current = sink
            if (current != null) {
                runCatching { current.close() }
                sink = null
            }
        }
    }

    private enum class AttemptResult { DONE, RESTART_FREE }

    /**
     * 单候选源的多连接分片下载。返回 true 表示已成功落盘；
     * 返回 false 表示该源不适合分片（不支持 Range / 文件太小 / 只切出 1 段），
     * 调用方应回落到原单连接路径。分片过程中任一段重试耗尽则抛 [DownloadException]，
     * 由上层按候选源顺序换源。
     */
    private suspend fun downloadCandidateChunked(
        url: URL,
        targetFile: File,
        sha1: String?,
        retry: Int,
        onBytes: (Long) -> Unit
    ): Boolean {
        val totalLength = try {
            runInterruptible(Dispatchers.IO) { probeRangeTotalLength(url) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DownloadException(url.toString(), e)
        }
        if (totalLength < 0) return false
        if (totalLength < CHUNK_MIN_BYTES) return false

        val chunks = splitChunks(totalLength, maxChunksForHeap())
        if (chunks.size <= 1) return false

        val parent = targetFile.parentFile ?: targetFile.canonicalFile.parentFile!!
        val parts = chunks.mapIndexed { i, _ ->
            File(parent, ".${targetFile.name}.part$i.tmp")
        }

        Logger.info(TAG, "Chunked download: $url (${chunks.size} x ${totalLength / chunks.size} bytes)")

        var sink: FileSink? = null
        try {
            // 各段并行下载；段内独立重试。任一段失败会取消其余段并抛异常。
            coroutineScope {
                val workers = Semaphore(chunks.size)
                val jobs = chunks.mapIndexed { i, chunk ->
                    async(Dispatchers.IO) {
                        workers.withPermit {
                            runInterruptible(Dispatchers.IO) {
                                downloadChunkToPart(url, chunk, parts[i], retry, onBytes)
                            }
                        }
                    }
                }
                jobs.joinAll()
            }

            // 按序合并各段到 FileSink：一边拼接一边计算整文件 SHA-1，
            // finish() 校验通过后原子替换目标文件，与单连接路径语义一致。
            // 段字节在 downloadChunkToPart 里已全部计入 onBytes，这里合并不再重复计数。
            sink = FileSink(targetFile, sha1)
            val activeSink = sink!!
            val mergeBuffer = ByteArray(BUFFER_SIZE)
            parts.forEach { part ->
                FileInputStream(part).use { input ->
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
                        val len = input.read(mergeBuffer)
                        if (len == -1) break
                        activeSink.write(mergeBuffer, 0, len)
                    }
                }
            }
            activeSink.finish()
            sink = null
            return true
        } catch (e: OutOfMemoryError) {
            // 低端机堆被并行 socket 缓冲顶爆：不向上报链路抛 OOM，
            // 清理临时段后回落到单连接路径（调用方再走 downloadCandidate）。
            Logger.warning(TAG, "Chunked download OOM, fallback to single connection: $url", e)
            return false
        } finally {
            sink?.let { runCatching { it.close() } }
            parts.forEach { runCatching { it.delete() } }
        }
    }

    /**
     * 用 `Range: bytes=0-0` 探测源是否支持分片并取整文件长度。
     * 返回 -1 表示不支持（服务器返回 200 忽略 Range）或无法判定；
     * 网络类异常直接抛出，由上层判定为该候选源失败。
     */
    private fun probeRangeTotalLength(originalUrl: URL): Long {
        var current = originalUrl
        var redirects = 0
        while (true) {
            val conn = openConnection(current)
            var keep = false
            try {
                conn.setRequestProperty("Range", "bytes=0-0")
                val code = conn.responseCode
                val response = ResponseInfo.of(code, conn.url, conn)
                if (code in 300..308 && code != HttpURLConnection.HTTP_NOT_MODIFIED && code != 306) {
                    if (++redirects >= MAX_REDIRECTS) throw IOException("Too much redirects")
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirected to an empty location")
                    current = current.toURI().resolve(location).toURL()
                    if (!isHttpUri(current)) throw IOException("Redirected to non-HTTP URL: $current")
                    continue
                }
                keep = true
                if (code == HttpURLConnection.HTTP_PARTIAL) {
                    val match = CONTENT_RANGE_TOTAL.find(response.header("content-range").orEmpty())
                    return match?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                }
                // 200 = 服务器忽略 Range，不支持分片；其它状态码按该源失败处理
                return -1L
            } finally {
                if (!keep) disconnectQuietly(conn)
            }
        }
    }

    /** 按目标段长把整文件切成至多 [maxChunks] 个闭区间分片 */
    private fun splitChunks(totalLength: Long, maxChunks: Int): List<ChunkRange> {
        val want = (totalLength + CHUNK_TARGET_BYTES - 1) / CHUNK_TARGET_BYTES
        val n = want.toInt().coerceAtLeast(1).coerceAtMost(maxChunks.coerceAtLeast(1))
        val chunkSize = (totalLength + n - 1) / n
        val out = ArrayList<ChunkRange>(n)
        var pos = 0L
        while (pos < totalLength) {
            val end = minOf(pos + chunkSize - 1, totalLength - 1)
            out.add(ChunkRange(pos, end))
            pos = end + 1
        }
        return out
    }

    /** 单段下载到临时文件，段内独立重试 [retry] 次；耗尽抛 [DownloadException] */
    private fun downloadChunkToPart(
        originalUrl: URL,
        chunk: ChunkRange,
        partFile: File,
        retry: Int,
        onBytes: (Long) -> Unit
    ) {
        var attempt = 0
        var lastError: Exception? = null
        while (attempt < retry) {
            try {
                streamRangeToFile(originalUrl, chunk, partFile, onBytes)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                attempt++
                if (attempt < retry) Thread.sleep(RETRY_BACKOFF_MILLIS)
            }
        }
        throw DownloadException(originalUrl.toString(), lastError ?: IOException("Chunk download failed"))
    }

    /** 实际把 [chunk] 区间流式落盘到 [partFile]；校验状态码与段长度 */
    private fun streamRangeToFile(
        originalUrl: URL,
        chunk: ChunkRange,
        partFile: File,
        onBytes: (Long) -> Unit
    ) {
        var current = originalUrl
        var redirects = 0
        var conn: HttpURLConnection? = null
        var response: ResponseInfo? = null
        while (true) {
            val c = openConnection(current)
            var keep = false
            try {
                c.setRequestProperty("Range", "bytes=${chunk.start}-${chunk.end}")
                val code = c.responseCode
                if (code in 300..308 && code != HttpURLConnection.HTTP_NOT_MODIFIED && code != 306) {
                    if (++redirects >= MAX_REDIRECTS) throw IOException("Too much redirects")
                    val location = c.getHeaderField("Location")
                        ?: throw IOException("Redirected to an empty location")
                    current = current.toURI().resolve(location).toURL()
                    if (!isHttpUri(current)) throw IOException("Redirected to non-HTTP URL: $current")
                    continue
                }
                conn = c
                response = ResponseInfo.of(code, c.url, c)
                keep = true
                break
            } finally {
                if (!keep) disconnectQuietly(c)
            }
        }

        val code = response!!.code
        if (code == HttpURLConnection.HTTP_OK) {
            // 探测时支持、实际却忽略 Range：按段失败处理（会触发段内重试/换源）
            throw IOException("Server ignored Range request for chunk $originalUrl")
        }
        if (code != HttpURLConnection.HTTP_PARTIAL) {
            throw HttpResultException(code, "HTTP $code: $originalUrl")
        }

        val expectedLen = chunk.end - chunk.start + 1
        FileOutputStream(partFile).use { out ->
            val input = conn!!.inputStream
            val buffer = ByteArray(BUFFER_SIZE)
            var total = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
                val len = input.read(buffer)
                if (len == -1) break
                out.write(buffer, 0, len)
                total += len
                onBytes(len.toLong())
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
            if (total != expectedLen) {
                throw IOException("Chunk size mismatch: expected $expectedLen bytes, got $total ($originalUrl)")
            }
        }
    }

    private suspend fun downloadCandidate(
        url: URL,
        targetFile: File,
        sha1: String?,
        retry: Int,
        onBytes: (Long) -> Unit
    ) {
        val state = CandidateState()
        val exceptions = mutableListOf<Exception>()
        var retryLimit = retry
        var retryTime = 0
        try {
            while (retryTime < retryLimit) {
                currentCoroutineContext().ensureActive()
                Logger.info(TAG, "Download started: $url")
                val attempt = retryTime
                retryTime++

                try {
                    val result = runInterruptible(Dispatchers.IO) {
                        attempt(url, targetFile, sha1, state, onBytes)
                    }
                    if (result == AttemptResult.DONE) return
                    //续传失效或 416：从头重来且不消耗重试次数
                    retryLimit++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: HttpResultException) {
                    Logger.warning(TAG, "Download failed: $url", e)
                    if (e.code in 400..499) throw DownloadException(url.toString(), e)
                    exceptions += e
                    if (attempt < retryLimit - 1) delay(RETRY_BACKOFF_MILLIS.milliseconds)
                } catch (e: Exception) {
                    Logger.warning(TAG, "Download failed: $url", e)
                    exceptions += e
                    if (attempt < retryLimit - 1) delay(RETRY_BACKOFF_MILLIS.milliseconds)
                }
            }
        } finally {
            state.discardSink()
        }

        val last = exceptions.lastOrNull() ?: IOException("No exceptions")
        exceptions.dropLast(1).forEach { last.addSuppressed(it) }
        throw DownloadException(url.toString(), last)
    }

    /** 一次完整尝试：手动跟随重定向、处理续传握手、流式落盘并提交。阻塞执行，取消依赖线程中断 */
    private fun attempt(
        url: URL,
        targetFile: File,
        sha1: String?,
        state: CandidateState,
        onBytes: (Long) -> Unit
    ): AttemptResult {
        var redirects: MutableList<URL>? = null

        val headers = linkedMapOf("accept-encoding" to "gzip")
        val resumeRequested = state.resume?.hasPartialContent() == true
        if (resumeRequested) {
            val resume = state.resume!!
            headers["range"] = "bytes=${resume.countUncompressed}-"
            headers["if-range"] = resume.ifRange()
        }

        var currentUri = url
        var connection: HttpURLConnection?
        var response: ResponseInfo?

        while (true) {
            val conn = openConnection(currentUri)
            var keep = false
            try {
                headers.forEach { (name, value) -> conn.setRequestProperty(name, value) }
                val code = conn.responseCode
                response = ResponseInfo.of(code, conn.url, conn)

                if (code in 300..308 && code != HttpURLConnection.HTTP_NOT_MODIFIED && code != 306) {
                    val seen = redirects ?: mutableListOf<URL>().also { redirects = it }
                    if (seen.size >= MAX_REDIRECTS) throw IOException("Too much redirects")

                    val location = conn.getHeaderField("Location")
                    if (location.isNullOrBlank()) throw IOException("Redirected to an empty location")

                    val target = currentUri.toURI().resolve(location).toURL()
                    seen.add(target)
                    if (!isHttpUri(target)) throw IOException("Redirected to non-HTTP URL: $target")
                    currentUri = target
                } else {
                    keep = true
                    connection = conn
                    break
                }
            } finally {
                if (!keep) disconnectQuietly(conn)
            }
        }

        val code = response.code

        if (resumeRequested && code == 416 /* HTTP_RANGE_NOT_SATISFIABLE */) {
            //已有分段不再可用：丢弃续传上下文，从头下载
            state.resume = null
            state.discardSink()
            return AttemptResult.RESTART_FREE
        }
        if (code / 100 == 4) throw HttpResultException(code, "HTTP $code: $url")
        if (code / 100 != 2) throw HttpResultException(code, "HTTP $code: $url")

        val contentLength = response.headerAsLong("content-length")
        val contentEncoding = ContentEncoding.fromHeaders(response)

        if (state.sink == null) {
            state.sink = FileSink(targetFile, sha1)
            state.resume = ResumeContext.of(response)
        } else if (resumeRequested) {
            if (state.resume!!.canResume(code, response)) {
                Logger.info(TAG, "Resuming $url from ${state.resume!!.countUncompressed}")
            } else {
                //服务器没能按段续传：丢弃已有内容，从头开始
                state.resume = null
                state.discardSink()
                return AttemptResult.RESTART_FREE
            }
        } else {
            state.discardSink()
            state.sink = FileSink(targetFile, sha1)
            state.resume = ResumeContext.of(response)
        }

        var inputStream: InputStream? = null
        var bodyConsumed = false
        try {
            inputStream = connection.inputStream
            transfer(state.sink!!, state.resume, inputStream, contentLength, contentEncoding, onBytes)
            inputStream = null
            bodyConsumed = true
        } catch (e: Throwable) {
            if (state.sink?.broken == true) {
                //写盘失败（磁盘错误等）：整个上下文作废，重试从零开始
                state.resume = null
                state.discardSink()
            }
            throw e
        } finally {
            runCatching { inputStream?.close() }
            if (!bodyConsumed) disconnectQuietly(connection)
        }

        try {
            state.sink!!.finish()
        } catch (e: Throwable) {
            state.resume = null
            state.discardSink()
            throw e
        }
        state.resume = null
        state.sink = null
        return AttemptResult.DONE
    }

    private fun transfer(
        sink: FileSink,
        resume: ResumeContext?,
        rawInput: InputStream,
        contentLength: Long,
        contentEncoding: ContentEncoding,
        onBytes: (Long) -> Unit
    ) {
        val counter = CountingInputStream(rawInput)
        contentEncoding.wrap(counter).use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var lastDownloaded = 0L
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
                val len = input.read(buffer)
                if (len == -1) break
                sink.write(buffer, 0, len)
                resume?.addBytes(len.toLong())
                onBytes(counter.downloaded - lastDownloaded)
                lastDownloaded = counter.downloaded
            }
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Cancelled")
            onBytes(counter.downloaded - lastDownloaded)

            if (contentLength >= 0 && counter.downloaded != contentLength) {
                throw IOException("Unexpected file size: ${counter.downloaded}, expected: $contentLength")
            }
        }
    }

    private fun openConnection(url: URL): HttpURLConnection {
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("User-Agent", URL_USER_AGENT)
        return connection
    }

    private fun isHttpUri(url: URL): Boolean =
        url.protocol.equals("http", ignoreCase = true) || url.protocol.equals("https", ignoreCase = true)

    private fun disconnectQuietly(connection: HttpURLConnection?) {
        if (connection == null) return
        runCatching { connection.errorStream?.close() }
        connection.disconnect()
    }

    private class CountingInputStream(input: InputStream) : InputStream() {
        private val delegate = input
        var downloaded = 0L
            private set

        override fun read(): Int {
            val value = delegate.read()
            if (value >= 0) downloaded++
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = delegate.read(buffer, offset, length)
            if (count >= 0) downloaded += count
            return count
        }

        override fun close() {
            delegate.close()
        }
    }
}

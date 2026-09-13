package gg.sona.recast.mc

import gg.sona.recast.clip.export.ExportJob
import gg.sona.recast.clip.export.ExportProgress
import java.io.BufferedInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.zip.ZipInputStream
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.name

@OptIn(ExperimentalPathApi::class)
class FfmpegDownloadJob(
    private val build: FfmpegBuild,
    private val directory: Path,
    private val install: (Path) -> String,
) : ExportJob {
    override val name: String get() = "download ffmpeg"

    @Volatile
    private var cancelled = false

    @Volatile
    private var body: InputStream? = null

    override fun run(progress: (Double) -> Unit): Path = run(ExportProgress(progress) {})

    override fun run(report: ExportProgress): Path {
        val staging = directory.resolveSibling("${directory.name}.part")
        report.detail("connecting")
        val client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
        val request = HttpRequest.newBuilder(URI.create(build.url))
            .header("User-Agent", "Recast")
            .timeout(Duration.ofSeconds(30))
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "download failed with HTTP ${response.statusCode()}" }
        val length = response.headers().firstValueAsLong("Content-Length").orElse(-1L)
        try {
            if (cancelled) throw InterruptedIOException("download cancelled")
            if (Files.exists(staging)) staging.deleteRecursively()
            Files.createDirectories(staging)
            val stream = response.body().also { body = it }
            var extracted = 0
            ZipInputStream(BufferedInputStream(Progress(stream, length, report), 1 shl 16)).use { zip ->
                for (entry in generateSequence { zip.nextEntry }) {
                    if (entry.isDirectory || !entry.name.startsWith(build.prefix)) continue
                    val file = entry.name.removePrefix(build.prefix)
                    if ('/' in file || file.startsWith("jni")) continue
                    Files.copy(zip, staging.resolve(file), StandardCopyOption.REPLACE_EXISTING)
                    extracted++
                }
            }
            check(extracted > 0) { "the downloaded archive did not contain ${build.prefix}" }
            staging.resolve(build.binary).toFile().setExecutable(true, false)
            if (Files.exists(directory)) directory.deleteRecursively()
            Files.move(staging, directory, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            body = null
            if (Files.exists(staging)) staging.deleteRecursively()
        }
        report.progress(0.95)
        report.detail("ready: ${install(directory.resolve(build.binary))}")
        return directory
    }

    override fun cancel() {
        cancelled = true
        runCatching { body?.close() }
    }

    private inner class Progress(source: InputStream, private val length: Long, private val report: ExportProgress) :
        FilterInputStream(source) {
        private var total = 0L
        private var reported = 0L

        override fun read(): Int {
            val byte = super.read()
            if (byte >= 0) advance(1)
            return byte
        }

        override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
            if (cancelled) throw InterruptedIOException("download cancelled")
            val read = super.read(buffer, offset, count)
            if (read > 0) advance(read)
            return read
        }

        private fun advance(bytes: Int) {
            total += bytes
            if (total - reported < 1 shl 20) return
            reported = total
            if (length > 0) {
                report.progress((total.toDouble() / length * 0.9).coerceIn(0.0, 0.9))
                report.detail(String.format("downloading %.0f / %.0f MB", total / 1048576.0, length / 1048576.0))
            } else {
                report.detail(String.format("downloading %.0f MB", total / 1048576.0))
            }
        }
    }
}

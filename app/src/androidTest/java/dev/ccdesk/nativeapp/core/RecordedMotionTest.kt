package dev.ccdesk.nativeapp.core

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt

/** Opt-in measurement of a specific adb screenrecord copied into this app's test cache.
 * Does not capture the screen, access media libraries, or alter the recorded frames.
 */
@RunWith(AndroidJUnit4::class)
class RecordedMotionTest {
    @Test fun measureRecordedSidebarEdgeAtOriginalResolution() {
        val arguments = InstrumentationRegistry.getArguments()
        val movieName = arguments.getString("movie")
        assumeTrue("Supply a cache movie basename to opt into motion measurement", !movieName.isNullOrBlank())
        if (Build.VERSION.SDK_INT < 28) {
            assumeTrue("Frame-index retrieval requires Android 9 or later", false)
            return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val cache = context.cacheDir.canonicalFile
        require(movieName!!.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.mp4"))) { "movie must be an MP4 basename inside app cache" }
        val movie = File(cache, movieName).canonicalFile
        require(movie.parentFile == cache && movie.isFile) { "Specified movie is not a regular cache file" }
        require(!(arguments.containsKey("scanY") && arguments.containsKey("scanYDp"))) { "Specify either scanY pixels or scanYDp" }
        val density = context.resources.displayMetrics.density
        val scanY = arguments.getString("scanYDp")?.toFloat()?.let { (it * density).roundToInt() }
            ?: arguments.getString("scanY")?.toInt() ?: 400
        val rawColor = arguments.getString("markerColor") ?: "f3f2ec"
        require(rawColor.matches(Regex("#?[A-Fa-f0-9]{6}"))) { "markerColor must be six RGB hex digits" }
        val marker = Color.parseColor("#" + rawColor.removePrefix("#"))
        val tolerance = arguments.getString("tolerance")?.toInt() ?: 6
        require(tolerance in 0..64) { "tolerance must be between 0 and 64 per RGB channel" }
        val report = json("movie" to movieName, "passed" to false, "scanYpx" to scanY,
            "density" to density, "markerColor" to "#${rawColor.removePrefix("#").lowercase()}",
            "tolerancePerChannel" to tolerance,
            "measurement" to "Continuous matching RGB prefix from x=0; edgePx is the first nonmatching pixel.",
            "ptsAssociation" to "Frame indices paired with sorted video presentation timestamps; frame count equality is required.")
        val frames = JSONArray()
        report.put("frames", frames)
        val extractor = MediaExtractor()
        val retriever = MediaMetadataRetriever()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            movie.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
            }
            report.put("movieSha256", digest.digest().joinToString("") { "%02x".format(it) })
            extractor.setDataSource(movie.absolutePath)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: error("Movie has no video track")
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val presentationTimes = mutableListOf<Long>()
            while (extractor.sampleTime >= 0) {
                presentationTimes += extractor.sampleTime
                if (!extractor.advance()) break
            }
            require(presentationTimes.isNotEmpty()) { "Movie has no video samples" }
            val sortedTimes = presentationTimes.sorted()
            retriever.setDataSource(movie.absolutePath)
            val declaredFrameCount = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull()
            val frameCount = declaredFrameCount ?: sortedTimes.size
            report.put("width", format.getInteger(MediaFormat.KEY_WIDTH))
                .put("height", format.getInteger(MediaFormat.KEY_HEIGHT))
                .put("rotationDegrees", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0)
                .put("videoMime", format.getString(MediaFormat.KEY_MIME))
                .put("extractorSamples", sortedTimes.size)
                .put("frameCount", frameCount)
                .put("declaredFrameCount", declaredFrameCount)
                .put("presentationTimestampsStrictlyIncreasing", sortedTimes.zipWithNext().all { (a,b) -> b > a })
            assertEquals("Frame count and extracted PTS count must match before pairing", sortedTimes.size, frameCount)
            val export = arguments.getString("exportFrames")?.let { value ->
                if (value.isBlank() || value == "none") emptySet()
                else value.split(',').map { it.trim().toInt() }.toSet()
            } ?: setOf(0, frameCount / 2, frameCount - 1)
            require(export.size <= 12 && export.all { it in 0 until frameCount }) { "Export at most 12 valid frame indices" }
            for (index in 0 until frameCount) {
                val bitmap = retriever.getFrameAtIndex(index) ?: error("Unable to decode video frame $index")
                try {
                    require(scanY in 0 until bitmap.height) { "scanY is outside the decoded frame" }
                    val row = IntArray(bitmap.width)
                    bitmap.getPixels(row, 0, bitmap.width, 0, scanY, bitmap.width, 1)
                    var edge = 0
                    while (edge < row.size && matches(row[edge], marker, tolerance)) edge++
                    val result = json("index" to index, "ptsUs" to sortedTimes[index],
                        "relativeUs" to sortedTimes[index] - sortedTimes.first(), "edgePx" to edge,
                        "decodedWidth" to bitmap.width, "decodedHeight" to bitmap.height)
                    if (index in export) {
                        val output = File(cache, "motion-frame-${index.toString().padStart(5,'0')}.png")
                        output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        result.put("exportedPng", output.name)
                    }
                    frames.put(result)
                } finally { bitmap.recycle() }
            }
            report.put("passed", true)
        } catch (error: Throwable) {
            report.put("failureType", error.javaClass.simpleName)
            throw error
        } finally {
            extractor.release()
            retriever.release()
            File(cache, "motion-report.json").writeText(report.toString(2))
        }
    }

    private fun matches(actual: Int, expected: Int, tolerance: Int): Boolean =
        abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
        abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
        abs(Color.blue(actual) - Color.blue(expected)) <= tolerance
}

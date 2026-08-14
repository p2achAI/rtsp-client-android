package ir.am3n.rtsp.client.decoders

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodec.OnFrameRenderedListener
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Process.setThreadPriority
import android.os.Process
import android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
import android.util.Log
import android.view.Surface
import android.view.SurfaceView
import com.google.android.renderscript.Toolkit
import com.google.android.renderscript.YuvFormat
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import androidx.media3.common.util.Util
import ir.am3n.rtsp.client.Rtsp
import ir.am3n.rtsp.client.data.YuvFrame
import ir.am3n.utils.DecoderType
import ir.am3n.utils.MediaCodecUtils
import ir.am3n.utils.VideoCodecType
import ir.am3n.utils.capabilitiesToString
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class VideoDecoder(
    private var surface: Surface? = null,
    private var surfaceView: SurfaceView? = null,
    var requestMediaImage: Boolean,
    var requestYuv: Boolean,
    var requestBitmap: Boolean,
    private val mimeType: String,
    private val width: Int,
    private val height: Int,
    private val rotation: Int, // 0, 90, 180, 270
    private val queue: VideoFrameQueue,
    private var videoDecoderType: DecoderType = DecoderType.SOFTWARE, //DecoderType.HARDWARE, //
    private val clientListener: RtspClientListener? = null,
    private val frameRenderedListener: OnFrameRenderedListener? = null,
    private val vps: ByteArray? = null,
    private val sps: ByteArray? = null,
    private val pps: ByteArray? = null
) : Thread() {

    companion object {

        private const val TAG: String = "VideoDecoder"

        private val DEQUEUE_INPUT_TIMEOUT_US = TimeUnit.MILLISECONDS.toMicros(500)
        private val DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US = TimeUnit.MILLISECONDS.toMicros(100)

    }

    private val rect = Rect()
    private var exitFlag = AtomicBoolean(false)

    private var keyColorFormat = 0
    private val hasLoggedYuvImageLayout = AtomicBoolean(false)
    private val hasLoggedLegacyYuvFallback = AtomicBoolean(false)

    /** Decoder latency used for statistics */
    @Volatile
    private var decoderLatency = -1

    /** Flag for allowing calculating latency */
    private var decoderLatencyRequested = false

    /** Network latency used for statistics */
    @Volatile
    private var networkLatency = -1
    private var videoDecoderName: String? = null
    private var firstFrameDecoded = false

    init {
        name = "RTSP video thread"
        setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)
        fixSurfaceSize()
    }

    fun setSurfaceView(surfaceView: SurfaceView?) {
        this.surfaceView = surfaceView
        fixSurfaceSize()
    }

    fun stopAsync() {
        if (Rtsp.DEBUG) Log.v(TAG, "stopAsync()")
        exitFlag.set(true)
        // Wake up sleep() code
        interrupt()
    }

    /**
     * Currently used video decoder. Video decoder can be changed on runtime.
     * If videoDecoderType set to HARDWARE, it can be switched to SOFTWARE in case of decoding issue
     * (e.g. hardware decoder does not support the stream resolution).
     * If videoDecoderType set to SOFTWARE, it will always remain SOFTWARE (no an`y changes).
     */
    fun getCurrentVideoDecoderType(): DecoderType {
        return videoDecoderType
    }

    fun getCurrentVideoDecoderName(): String? {
        return videoDecoderName
    }

    /**
     * Get frames decoding/rendering latency in millis. Returns -1 if not supported.
     */
    fun getCurrentVideoDecoderLatencyMillis(): Int {
        decoderLatencyRequested = true
        return decoderLatency
    }

    /**
     * Get network latency in millis. Returns -1 if not supported.
     */
    fun getCurrentNetworkLatencyMillis(): Int {
        return networkLatency
    }

    // Utility function to find a byte array slice within another byte array
    private fun ByteArray.indexOfSlice(slice: ByteArray): Int {
        outer@ for (i in 0..this.size - slice.size) {
            for (j in slice.indices) {
                if (this[i + j] != slice[j]) continue@outer
            }
            return i
        }
        return -1
    }

    override fun run() {
        if (Rtsp.DEBUG) Log.d(TAG, "$name started")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            setThreadPriority(Process.THREAD_PRIORITY_VIDEO)
        }

        try {
            Log.i(TAG, "Starting $videoDecoderType video decoder...")
            var decoder = try {
                createVideoDecoderAndStart(videoDecoderType)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to start $videoDecoderType video decoder (${e.message})", e)
                Log.i(TAG, "Starting software video decoder...")
                try {
                    createVideoDecoderAndStart(DecoderType.SOFTWARE)
                } catch (e2: Throwable) {
                    Log.e(TAG, "Failed to start video software decoder. Exiting...", e)
                    return
                }
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var lastOutputOrFormatChangeMs = System.currentTimeMillis()
            var tryAgainStreak = 0

            try {
                // Map for calculating decoder rendering latency.
                // key - original frame timestamp, value - timestamp when frame was added to the map
                val keyframesTimestamps = HashMap<Long, Long>()

                var frameQueuedMsec = System.currentTimeMillis()
                var frameAlreadyDequeued = false

                // Main loop
                while (!exitFlag.get()) {
                    try {
                        if (Rtsp.DEBUG) {
                            Log.d(TAG, "Decoder codec capabilities: ${decoder.codecInfo.getCapabilitiesForType(mimeType).capabilitiesToString()}")
                        }
                        val inIndex: Int = decoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)
                        if (inIndex >= 0) {
                            // fill inputBuffers[inputBufferIndex] with valid data
                            val byteBuffer: ByteBuffer? = decoder.getInputBuffer(inIndex)
                            byteBuffer?.rewind()

                            // Preventing BufferOverflowException
                            // if (length > byteBuffer.limit()) throw DecoderFatalException("Error")

                            val frame = queue.pop()
                            if (frame == null) {
                                Log.d(TAG, "Empty video frame")
                                // Release input buffer
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                            } else {
                                // Add timestamp for keyframe to calculating latency further.
                                if ((Rtsp.DEBUG || decoderLatencyRequested) && frame.isKeyframe) {
                                    if (keyframesTimestamps.size > 5) {
                                        // Something wrong with map. Allow only 5 map entries.
                                        keyframesTimestamps.clear()
                                    }
                                    val l = System.currentTimeMillis()
                                    keyframesTimestamps[frame.timestamp] = l
                                }
                                // Calculate network latency
                                networkLatency = if (frame.capturedTimestamp > -1)
                                    (frame.timestamp - frame.capturedTimestamp).toInt()
                                else
                                    -1

                                if (byteBuffer != null && frame.length > byteBuffer.remaining()) {
                                    Log.w(
                                        TAG,
                                        "Skipping NAL larger than decoder input buffer " +
                                                "(${frame.length} > ${byteBuffer.remaining()})"
                                    )
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                                    continue
                                }
                                byteBuffer?.put(frame.data, frame.offset, frame.length)
                                // --- BEGIN NAL TYPE EXTRACTION ---
                                var nalType = -1
                                val nalStart = listOf(0, 1, 2, 3, 4).firstOrNull { i ->
                                    frame.data.size > frame.offset + i + 4 &&
                                    frame.data.copyOfRange(frame.offset + i, frame.offset + i + 4)
                                        .contentEquals(byteArrayOf(0x00, 0x00, 0x00, 0x01))
                                }
                                if (nalStart != null) {
                                    val nalHeader = frame.data[frame.offset + nalStart + 4].toInt()
                                    nalType = when (frame.codecType) {
                                        VideoCodecType.H264 -> nalHeader and 0x1F
                                        VideoCodecType.H265 -> (nalHeader ushr 1) and 0x3F
                                        VideoCodecType.UNKNOWN -> -1
                                    }
                                } else {
                                    Log.w(TAG, "Failed to detect NAL start code")
                                }
                                // fallback: crude heuristic for NAL start (still -1 if not found)
                                // (original code: val nalType = frame.data[frame.offset + 4].toInt() and 0x1F)
                                // --- END NAL TYPE EXTRACTION ---
                                // --- BEGIN FULL NAL TYPES SCAN ---
                                fun findNalTypes(data: ByteArray): List<Int> {
                                    val types = mutableListOf<Int>()
                                    var i = 0
                                    while (i <= data.size - 4) {
                                        if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                                            if (i + 4 < data.size) {
                                                val nalHeader = data[i + 4]
                                                types.add(
                                                    when (frame.codecType) {
                                                        VideoCodecType.H264 -> nalHeader.toInt() and 0x1F
                                                        VideoCodecType.H265 -> (nalHeader.toInt() ushr 1) and 0x3F
                                                        VideoCodecType.UNKNOWN -> -1
                                                    }
                                                )
                                            }
                                            i += 4
                                        } else {
                                            i++
                                        }
                                    }
                                    return types
                                }
                                val allTypes = findNalTypes(frame.data)
                                if (Rtsp.DEBUG) {
                                    Log.d(TAG, "Full NAL types in frame: ${allTypes.joinToString()}")
                                }
                                // --- END FULL NAL TYPES SCAN ---
                                // --- BEGIN IDR NAL EXTRACTION ---
                                val idrNalStartIndex = if (frame.codecType == VideoCodecType.H264) {
                                    frame.data.indexOfSlice(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65.toByte()))
                                } else {
                                    -1
                                }
                                if (idrNalStartIndex >= 0) {
                                    Log.w(TAG, "Extracting IDR-only NAL from full frame")
                                    val idrData = frame.data.copyOfRange(idrNalStartIndex, frame.offset + frame.length)
                                    byteBuffer?.clear()
                                    byteBuffer?.put(idrData)
                                    decoder.queueInputBuffer(inIndex, 0, idrData.size, frame.timestamp, 0)
                                    continue
                                }
                                // --- END IDR NAL EXTRACTION ---
                                if (Rtsp.DEBUG) {
                                    Log.i(TAG, "Trying to queue NAL type=$nalType, size=${frame.length}")
                                    // --- BEGIN NAL HEADER DEBUG LOGGING ---
                                    val headerPreview = frame.data.sliceArray(frame.offset until (frame.offset + 16).coerceAtMost(frame.data.size))
                                    Log.d(TAG, "NAL Preview [type=$nalType, size=${frame.length}]: ${headerPreview.joinToString(" ") { "%02X".format(it) }}")
                                    Log.d(TAG, "Timestamp: ${frame.timestamp}")
                                    // --- END NAL HEADER DEBUG LOGGING ---
                                }
                                // --- BEGIN NAL SAFEGUARD ---
                                val supportedNalType = when (frame.codecType) {
                                    VideoCodecType.H264 -> nalType in 1..5 || nalType == 7 || nalType == 8
                                    VideoCodecType.H265 -> nalType in 0..47
                                    VideoCodecType.UNKNOWN -> false
                                }
                                if (!supportedNalType) {
                                    Log.w(TAG, "Skipping unknown or unsupported NAL type=$nalType")
                                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, 0)
                                    continue
                                }
                                // --- END NAL SAFEGUARD ---
                                if (Rtsp.DEBUG) {
                                    val l = System.currentTimeMillis()
                                    Log.i(TAG, "\tFrame queued (${l - frameQueuedMsec}) ${if (frame.isKeyframe) "key frame" else ""}")
                                    frameQueuedMsec = l
                                }
                                decoder.queueInputBuffer(inIndex, frame.offset, frame.length, frame.timestamp, 0)
                            }
                        }

                        if (exitFlag.get())
                            break

                        // Get all output buffer frames until no buffer from decoder available (INFO_TRY_AGAIN_LATER).
                        // Single input buffer frame can contain several frames, e.g. SPS + PPS + IDR.
                        // Thus dequeueOutputBuffer should be called several times.
                        // First time it obtains SPS + PPS, second one - IDR frame.
                        do {
                            // For the first time wait for a frame within 100 msec, next times no timeout
                            val timeout = if (frameAlreadyDequeued || !firstFrameDecoded) 0L else DEQUEUE_OUTPUT_BUFFER_TIMEOUT_US
                            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, timeout)
                            when (outIndex) {
                                // Resolution changed
                                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    Log.d(TAG, "Decoder format changed: ${decoder.outputFormat}")
                                    updateOutputColorFormat(decoder.outputFormat)
                                    frameAlreadyDequeued = true
                                    tryAgainStreak = 0
                                    lastOutputOrFormatChangeMs = System.currentTimeMillis()
                                }
                                // No any frames in queue
                                MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                    if (Rtsp.DEBUG) Log.d(TAG, "No output from decoder available")
                                    frameAlreadyDequeued = true
                                    tryAgainStreak++
                                    val now = System.currentTimeMillis()
                                    if (DecoderFallbackPolicy.shouldFallback(
                                            videoDecoderType,
                                            now - lastOutputOrFormatChangeMs,
                                            tryAgainStreak,
                                        )) {
                                        Log.w(TAG, "HW decoder appears stalled (no output ${now - lastOutputOrFormatChangeMs}ms, tryAgainStreak=$tryAgainStreak). Falling back to SW.")
                                        // Fallback: stop current decoder and switch to software
                                        stopAndReleaseVideoDecoder(decoder)
                                        videoDecoderType = DecoderType.SOFTWARE
                                        decoder = createVideoDecoderAndStart(DecoderType.SOFTWARE)
                                        // Reset watchdog state after restart
                                        tryAgainStreak = 0
                                        lastOutputOrFormatChangeMs = System.currentTimeMillis()
                                        // Skip to next loop iteration to re-enter dequeue with the new decoder
                                        continue
                                    }
                                }
                                // Frame decoded
                                else -> {
                                    if (outIndex >= 0) {
                                        if (Rtsp.DEBUG || decoderLatencyRequested) {
                                            val ts = bufferInfo.presentationTimeUs
                                            keyframesTimestamps.remove(ts)?.apply {
                                                decoderLatency = (System.currentTimeMillis() - this).toInt()
                                            }
                                        }

                                        val hasRenderableSurface = surface != null || surfaceView?.holder?.surface?.isValid == true
                                        val render = hasRenderableSurface && bufferInfo.size != 0 && !exitFlag.get()
                                        if (Rtsp.DEBUG) Log.i(TAG, "\tFrame decoded [outIndex=$outIndex, render=$render]")
                                        decodeYuv(decoder, bufferInfo, outIndex)
                                        decoder.releaseOutputBuffer(outIndex, render)
                                        if (!firstFrameDecoded && bufferInfo.size != 0) {
                                            firstFrameDecoded = true
                                        }
                                        frameAlreadyDequeued = false
                                        tryAgainStreak = 0
                                        lastOutputOrFormatChangeMs = System.currentTimeMillis()
                                    } else {
                                        Log.e(TAG, "Obtaining frame failed w/ error code $outIndex")
                                    }
                                }
                            }
                            // For SPS/PPS frame request another frame (IDR)
                        } while (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED)

                        // All decoded frames have been rendered, we can stop playing now
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            if (Rtsp.DEBUG) Log.d(TAG, "OutputBuffer BUFFER_FLAG_END_OF_STREAM")
                            break
                        }
                    } catch (ignored: InterruptedException) {
                    } catch (e: IllegalStateException) {
                        // Restarting decoder in software mode
                        Log.e(TAG, "${e.message}", e)
                        stopAndReleaseVideoDecoder(decoder)
                        Log.i(TAG, "Starting software video decoder...")
                        decoder = createVideoDecoderAndStart(DecoderType.SOFTWARE)
                        Log.i(
                            TAG,
                            "Software video decoder '${decoder.name}' started (${
                                decoder.codecInfo.getCapabilitiesForType(mimeType).capabilitiesToString()
                            })"
                        )
                    } catch (e: MediaCodec.CodecException) {
                        Log.w(TAG, "${e.diagnosticInfo}\nisRecoverable: $${e.isRecoverable}, isTransient: ${e.isTransient}")
                        if (e.isRecoverable) {
                            // Recoverable error.
                            // Calling stop(), configure(), and start() to recover.
                            Log.i(TAG, "Recovering video decoder...")
                            try {
                                decoder.stop()
                                val format = getDecoderMediaFormat(decoder)
                                decoder.configure(format, surface, null, 0)
                                decoder.start()
                                Log.i(TAG, "Video decoder recovering succeeded")
                            } catch (e2: Throwable) {
                                Log.e(TAG, "Video decoder recovering failed")
                                Log.e(TAG, "${e2.message}", e2)
                            }
                        } else if (e.isTransient) {
                            // Transient error. Resources are temporarily unavailable and
                            // the method may be retried at a later time.
                            Log.w(TAG, "Video decoder resource temporarily unavailable")
                        } else {
                            // Fatal error. Restarting decoder in software mode.
                            stopAndReleaseVideoDecoder(decoder)
                            Log.i(TAG, "Starting video software decoder...")
                            decoder = createVideoDecoderAndStart(DecoderType.SOFTWARE)
                            Log.i(
                                TAG,
                                "Software video decoder '${decoder.name}' started (${
                                    decoder.codecInfo.getCapabilitiesForType(mimeType).capabilitiesToString()
                                })"
                            )
                        }
                    } catch (e: Throwable) {
                        Log.e(TAG, "${e.message}", e)
                    }
                } // while

                // Drain decoder
                val inIndex: Int = decoder.dequeueInputBuffer(DEQUEUE_INPUT_TIMEOUT_US)
                if (inIndex >= 0) {
                    decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                } else {
                    Log.w(TAG, "Not able to signal end of stream")
                }

            } catch (e2: Throwable) {
                Log.e(TAG, "${e2.message}", e2)
            } finally {
                stopAndReleaseVideoDecoder(decoder)
            }

        } catch (e: Throwable) {
            Log.e(TAG, "$name stopped due to '${e.message}'")
            // While configuring stopAsync can be called and surface released. Just exit.
            if (!exitFlag.get()) e.printStackTrace()
            return
        }

        if (Rtsp.DEBUG) Log.d(TAG, "$name stopped")
    }

    private fun fixSurfaceSize() {
        if (Rtsp.DEBUG) Log.d(
            TAG,
            "fixSurfaceSize()  width: $width   height: $height   " + "sw: ${surfaceView?.measuredWidth}  sh: ${surfaceView?.measuredHeight}"
        )
        surfaceView?.post {
            if (width > height) {
                val rate = (surfaceView?.measuredWidth ?: 0).toFloat() / width.toFloat()
                val height = (height * rate).toInt()
                surfaceView?.holder?.setFixedSize(surfaceView!!.measuredWidth, height)
                rect.right = surfaceView!!.measuredWidth
                rect.bottom = height
                if (Rtsp.DEBUG) Log.d(TAG, "fixSurfaceSize()   set  width: ${surfaceView!!.measuredWidth}   height: $height")
            } else {
                val rate = (surfaceView?.measuredHeight ?: 0).toFloat() / height.toFloat()
                val width = (width * rate).toInt()
                surfaceView?.holder?.setFixedSize(width, surfaceView!!.measuredHeight)
                rect.right = width
                rect.bottom = surfaceView!!.measuredHeight
                if (Rtsp.DEBUG) Log.d(TAG, "fixSurfaceSize()   set  width: $width   height: ${surfaceView!!.measuredHeight}")
            }
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun getDecoderSafeWidthHeight(decoder: MediaCodec): Pair<Int, Int> {
        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType).videoCapabilities
        return if (capabilities.isSizeSupported(width, height)) {
            Pair(width, height)
        } else {
            val widthAlignment = capabilities.widthAlignment
            val heightAlignment = capabilities.heightAlignment
            Pair(
                Util.ceilDivide(width, widthAlignment) * widthAlignment,
                Util.ceilDivide(height, heightAlignment) * heightAlignment
            )
        }
    }

    @SuppressLint("InlinedApi")
    private fun getWidthHeight(mediaFormat: MediaFormat): Pair<Int, Int> {
        // Sometimes height obtained via KEY_HEIGHT is not valid, e.g. can be 1088 instead 1080
        // (no problems with width though). Use crop parameters to correctly determine height.
        val hasCrop =
            mediaFormat.containsKey(MediaFormat.KEY_CROP_RIGHT) && mediaFormat.containsKey(MediaFormat.KEY_CROP_LEFT) &&
                    mediaFormat.containsKey(MediaFormat.KEY_CROP_BOTTOM) && mediaFormat.containsKey(MediaFormat.KEY_CROP_TOP)
        val width =
            if (hasCrop)
                mediaFormat.getInteger(MediaFormat.KEY_CROP_RIGHT) - mediaFormat.getInteger(MediaFormat.KEY_CROP_LEFT) + 1
            else
                mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
        var height =
            if (hasCrop)
                mediaFormat.getInteger(MediaFormat.KEY_CROP_BOTTOM) - mediaFormat.getInteger(MediaFormat.KEY_CROP_TOP) + 1
            else
                mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
        // Fix for 1080p resolution for Samsung S21
        // {crop-right=1919, max-height=4320, sar-width=1, color-format=2130708361, mime=video/raw,
        // hdr-static-info=java.nio.HeapByteBuffer[pos=0 lim=25 cap=25],
        // priority=0, color-standard=1, feature-secure-playback=0, color-transfer=3, sar-height=1,
        // crop-bottom=1087, max-width=8192, crop-left=0, width=1920, color-range=2, crop-top=0,
        // rotation-degrees=0, frame-rate=30, height=1088}
        height = height / 16 * 16 // 1088 -> 1080
//        if (height == 1088)
//            height = 1080
        return Pair(width, height)
    }

    private fun getDecoderMediaFormat(decoder: MediaCodec): MediaFormat {
        if (Rtsp.DEBUG) Log.v(TAG, "getDecoderMediaFormat()")
        val safeWidthHeight = getDecoderSafeWidthHeight(decoder)
        val format = MediaFormat.createVideoFormat(mimeType, safeWidthHeight.first, safeWidthHeight.second)
        Log.i(TAG, "Configuring surface ${safeWidthHeight.first}x${safeWidthHeight.second} w/ '$mimeType'")
        format.setInteger(MediaFormat.KEY_ROTATION, rotation)
        if (surface == null && (surfaceView != null || requestMediaImage || requestYuv || requestBitmap)) {
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
        }
        val supportsLowLatency = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            decoder.codecInfo.getCapabilitiesForType(mimeType)
                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        if (supportsLowLatency) {
            // format.setFeatureEnabled(android.media.MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency, true)
            // Request low-latency for the decoder. Not all of the decoders support that.
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        if (sps != null && pps != null) {
            if (mimeType == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                val parameterSets = listOfNotNull(vps, sps, pps)
                val csd = ByteArray(parameterSets.sumOf { it.size })
                var offset = 0
                parameterSets.forEach {
                    it.copyInto(csd, offset)
                    offset += it.size
                }
                format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            } else {
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
            }
//            Log.d(TAG, "SPS: ${sps.joinToString(" ") { "%02X".format(it) }}")
//            Log.d(TAG, "PPS: ${pps.joinToString(" ") { "%02X".format(it) }}")
        }
        return format
    }

    private fun createVideoDecoderAndStart(decoderType: DecoderType): MediaCodec {
        if (Rtsp.DEBUG) Log.v(TAG, "createVideoDecoderAndStart(decoderType=$decoderType)")

        @SuppressLint("UnsafeOptInUsageError")
        val decoder = when (decoderType) {
            DecoderType.HARDWARE -> {
                val hwDecoders = MediaCodecUtils.getHardwareDecoders(mimeType)
                if (hwDecoders.isEmpty()) {
                    Log.w(TAG, "Cannot get hardware video decoders for mime type '$mimeType'. Using default one.")
                    MediaCodec.createDecoderByType(mimeType)
                } else {
                    val lowLatencyDecoder = MediaCodecUtils.getLowLatencyDecoder(hwDecoders)
                    val name = lowLatencyDecoder?.let {
                        Log.i(TAG, "[$name] Dedicated low-latency decoder found '${lowLatencyDecoder.name}'")
                        lowLatencyDecoder.name
                    } ?: hwDecoders[0].name
                    MediaCodec.createByCodecName(name)
                }
            }

            DecoderType.SOFTWARE -> {
                val swDecoders = MediaCodecUtils.getSoftwareDecoders(mimeType)
                if (swDecoders.isEmpty()) {
                    Log.w(TAG, "Cannot get software video decoders for mime type '$mimeType'. Using default one .")
                    MediaCodec.createDecoderByType(mimeType)
                } else {
                    val name = swDecoders[0].name
                    MediaCodec.createByCodecName(name)
                }
            }
        }
        this.videoDecoderType = decoderType
        this.videoDecoderName = decoder.name

        decoder.setOnFrameRenderedListener(frameRenderedListener, null)

        val format = getDecoderMediaFormat(decoder)
        decoder.configure(format, surface, null, 0)
        decoder.start()

        updateOutputColorFormat(decoder.outputFormat)
        if (Rtsp.DEBUG) Log.i(TAG, "keyColorFormat= $keyColorFormat")

        val capabilities = decoder.codecInfo.getCapabilitiesForType(mimeType)
        val lowLatencySupport = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            capabilities.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
        } else {
            false
        }
        Log.i(
            TAG, "[$name] Video decoder '${decoder.name}' started " +
                    "(${
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (decoder.codecInfo.isHardwareAccelerated) "hardware" else "software"
                        } else ""
                    }, " +
                    "${capabilities.capabilitiesToString()}, " +
                    "${if (lowLatencySupport) "w/" else "w/o"} low-latency support)"
        )

        return decoder
    }

    private fun updateOutputColorFormat(outputFormat: MediaFormat) {
        if (outputFormat.containsKey(MediaFormat.KEY_COLOR_FORMAT)) {
            keyColorFormat = outputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT)
        }
    }

    private fun stopAndReleaseVideoDecoder(decoder: MediaCodec) {
        if (Rtsp.DEBUG) Log.v(TAG, "stopAndReleaseVideoDecoder()")
        val type = videoDecoderType.toString().lowercase()
        Log.i(TAG, "Stopping $type video decoder...")
        try {
            decoder.stop()
            Log.i(TAG, "Decoder successfully stopped")
        } catch (e3: Throwable) {
            Log.e(TAG, "Failed to stop decoder", e3)
        }
        Log.i(TAG, "Releasing decoder...")
        try {
            decoder.release()
            Log.i(TAG, "Decoder successfully released")
        } catch (e3: Throwable) {
            Log.e(TAG, "Failed to release decoder", e3)
        }
        queue.clear()
    }

    private data class PackedYuvFrame(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val format: YuvFormat
    )

    private fun decodeYuv(decoder: MediaCodec, info: MediaCodec.BufferInfo, index: Int) {
        var outputImage: Image? = null
        var imageOwnershipTransferred = false

        try {
            if (surfaceView == null && !requestMediaImage && !requestYuv && !requestBitmap) {
                return
            }

            val shouldDrawBitmap = surfaceView?.holder?.surface?.isValid == true
            if (surfaceView != null && !shouldDrawBitmap) {
                Log.w(TAG, "Surface is not valid for rendering")
            }

            val needsPackedYuv = requestYuv || requestBitmap || shouldDrawBitmap
            if (needsPackedYuv || requestMediaImage) {
                outputImage = try {
                    decoder.getOutputImage(index)
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to obtain decoder output as Image", t)
                    null
                }
            }

            var packedYuv = if (needsPackedYuv && outputImage != null) {
                try {
                    outputImageToNv21(outputImage)
                } catch (t: Throwable) {
                    Log.w(TAG, "Unable to convert decoder Image planes to NV21", t)
                    null
                }
            } else {
                null
            }

            if (needsPackedYuv && packedYuv == null && !requestMediaImage) {
                outputImage?.close()
                outputImage = null
                if (hasLoggedLegacyYuvFallback.compareAndSet(false, true)) {
                    Log.w(TAG, "Falling back to packed codec output; plane layout is unavailable")
                }
                packedYuv = readLegacyPackedYuv(decoder, info, index)
            }

            val bitmap = if ((shouldDrawBitmap || requestBitmap) && packedYuv != null) {
                try {
                    Toolkit.yuvToRgbBitmap(
                        packedYuv.data,
                        packedYuv.width,
                        packedYuv.height,
                        packedYuv.format
                    )
                } catch (t: Throwable) {
                    Log.e(TAG, "Unable to convert decoded YUV frame to Bitmap", t)
                    null
                }
            } else {
                null
            }

            if (bitmap != null) {
                surfaceView?.post {
                    surfaceView?.holder?.surface?.run {
                        if (isValid) {
                            lockCanvas(rect)?.run {
                                drawBitmap(bitmap, null, rect, null)
                                unlockCanvasAndPost(this)
                            }
                        }
                    }
                }
            }

            if (requestMediaImage || requestYuv || requestBitmap) {
                val listener = clientListener
                val crop = outputImage?.cropRect
                val frameWidth = packedYuv?.width ?: crop?.width() ?: width
                val frameHeight = packedYuv?.height ?: crop?.height() ?: height
                listener?.onRtspVideoFrameReceived(
                    frameWidth,
                    frameHeight,
                    if (requestMediaImage) outputImage else null,
                    if (requestYuv && packedYuv != null) {
                        YuvFrame(packedYuv.data, packedYuv.format)
                    } else {
                        null
                    },
                    if (requestBitmap) bitmap?.copy(Bitmap.Config.ARGB_8888, true) else null
                )
                if (requestMediaImage && outputImage != null && listener != null) {
                    imageOwnershipTransferred = true
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to process decoded video frame", t)
        } finally {
            if (!imageOwnershipTransferred) {
                outputImage?.close()
            }
        }
    }

    private fun outputImageToNv21(image: Image): PackedYuvFrame {
        require(image.format == ImageFormat.YUV_420_888) {
            "Unsupported output image format ${image.format}"
        }

        val crop = image.cropRect
        val planes = image.planes.map {
            Yuv420PlaneConverter.Plane(
                buffer = it.buffer,
                rowStride = it.rowStride,
                pixelStride = it.pixelStride
            )
        }
        if (hasLoggedYuvImageLayout.compareAndSet(false, true)) {
            Log.i(
                TAG,
                "Decoder Image layout crop=$crop, planes=" +
                        planes.joinToString { "row=${it.rowStride}/pixel=${it.pixelStride}" }
            )
        }

        return PackedYuvFrame(
            data = Yuv420PlaneConverter.toNv21(
                cropLeft = crop.left,
                cropTop = crop.top,
                width = crop.width(),
                height = crop.height(),
                planes = planes
            ),
            width = crop.width(),
            height = crop.height(),
            format = YuvFormat.NV21
        )
    }

    private fun readLegacyPackedYuv(
        decoder: MediaCodec,
        info: MediaCodec.BufferInfo,
        index: Int
    ): PackedYuvFrame? {
        val source = decoder.getOutputBuffer(index)?.duplicate() ?: return null
        val start = info.offset
        val end = info.offset + info.size
        if (start < 0 || end < start || end > source.limit()) {
            Log.w(
                TAG,
                "Invalid codec output range offset=${info.offset}, size=${info.size}, limit=${source.limit()}"
            )
            return null
        }
        source.position(start)
        source.limit(end)
        val data = ByteArray(source.remaining())
        source.get(data)

        val format = when (keyColorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar -> YuvFormat.YV12

            MediaCodecInfo.CodecCapabilities.COLOR_QCOM_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar -> YuvFormat.YV21

            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar -> YuvFormat.NV12

            else -> {
                Log.w(TAG, "Unknown packed YUV color format $keyColorFormat")
                return null
            }
        }
        return PackedYuvFrame(data, width, height, format)
    }

}

package ir.am3n.rtsp.client

import android.graphics.Bitmap
import android.media.Image
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process.THREAD_PRIORITY_URGENT_AUDIO
import android.os.Process.setThreadPriority
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import ir.am3n.rtsp.client.data.AudioFrame
import ir.am3n.rtsp.client.interfaces.Frame
import ir.am3n.rtsp.client.data.SdpInfo
import ir.am3n.rtsp.client.data.VideoFrame
import ir.am3n.rtsp.client.data.YuvFrame
import ir.am3n.rtsp.client.decoders.AudioDecoder
import ir.am3n.rtsp.client.decoders.AudioFrameQueue
import ir.am3n.rtsp.client.decoders.VideoDecoder
import ir.am3n.rtsp.client.decoders.VideoFrameQueue
import ir.am3n.rtsp.client.interfaces.RtspClientListener
import ir.am3n.rtsp.client.interfaces.RtspFrameListener
import ir.am3n.rtsp.client.interfaces.RtspStatusListener
import ir.am3n.utils.AudioCodecType
import ir.am3n.utils.DecoderType
import ir.am3n.utils.NetUtils
import ir.am3n.utils.VideoCodecType
import ir.am3n.utils.VideoCodecUtils
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.min

class Rtsp {

    private var videoDecoderType: DecoderType = DecoderType.SOFTWARE

    /** Selects the preferred decoder for the next RTSP session. Hardware falls back to software. */
    fun setVideoDecoderType(type: DecoderType) {
        videoDecoderType = type
    }

    companion object {

        private const val TAG: String = "Rtsp"
        private const val DEFAULT_RTSP_PORT = 554

        var DEBUG = false

        suspend fun isOnline(url: String, username: String? = null, password: String? = null, userAgent: String? = null): Boolean {
            return suspendCoroutine {
                Rtsp().apply {
                    init(url, username, password, userAgent)
                    setStatusListener(object : RtspStatusListener {
                        override fun onConnecting() {}
                        override fun onConnected(sdpInfo: SdpInfo) {}
                        override fun onFirstFrameRendered() {}
                        override fun onDisconnecting() {}
                        override fun onDisconnected() {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(false)
                        }
                        override fun onUnauthorized() {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(true)
                        }
                        override fun onFailed(message: String?) {
                            setStatusListener(null)
                            setFrameListener(null)
                            it.resume(false)
                        }
                    })
                    setFrameListener(object : RtspFrameListener {
                        override fun onVideoNalUnitReceived(frame: Frame?) {
                            setStatusListener(null)
                            setFrameListener(null)
                            stop()
                            it.resume(true)
                        }
                        override fun onVideoFrameReceived(
                            width: Int, height: Int, mediaImage: Image?,
                            yuv: YuvFrame?, bitmap: Bitmap?
                        ) {}
                        override fun onAudioSampleReceived(frame: Frame?) {}
                    })
                    start(playVideo = true, playAudio = false)
                }
            }
        }

    }

    internal inner class RtspThread : Thread() {

        private var rtspStopped: AtomicBoolean = AtomicBoolean(false)

        init {
            name = "RTSP IO thread"
            setThreadPriority(THREAD_PRIORITY_URGENT_AUDIO)
        }

        fun stopAsync() {
            if (DEBUG) Log.v(TAG, "stopAsync()")
            rtspStopped.set(true)
            interrupt()
        }

        override fun run() {
            onRtspClientStarted()
            try {
                val execution = EndpointFallbackExecutor.execute(
                    endpointUris,
                    isStopped = { rtspStopped.get() },
                    attempt = { endpointUri ->
                        endpointConnected = false
                        terminalUnauthorized = false
                        endpointFailureMessage = null
                        val port = if (endpointUri.port == -1) DEFAULT_RTSP_PORT else endpointUri.port
                        var socket: Socket? = null
                        try {
                            if (DEBUG) Log.d(TAG, "Connecting to ${endpointUri.host}:$port...")
                            socket = NetUtils.createSocketAndConnect(endpointUri, port, timeout)
                            val rtspClient = RtspClient.Builder(
                                socket, endpointUri.toString(), rtspStopped, clientListener
                            )
                                .requestVideo(playVideo)
                                .requestAudio(playAudio)
                                .withUserAgent(userAgent)
                                .withCredentials(username, password)
                                .build()
                            rtspClient.execute()
                        } catch (t: Throwable) {
                            endpointFailureMessage = t.message
                            if (DEBUG) Log.w(TAG, "Endpoint failed: $endpointUri", t)
                        } finally {
                            socket?.let { NetUtils.closeSocket(it) }
                        }
                        when {
                            rtspStopped.get() -> EndpointFallbackExecutor.ExecutionResult(
                                EndpointFallbackExecutor.AttemptResult.STOPPED
                            )
                            terminalUnauthorized -> EndpointFallbackExecutor.ExecutionResult(
                                EndpointFallbackExecutor.AttemptResult.UNAUTHORIZED
                            )
                            endpointConnected -> EndpointFallbackExecutor.ExecutionResult(
                                EndpointFallbackExecutor.AttemptResult.CONNECTED
                            )
                            else -> EndpointFallbackExecutor.ExecutionResult(
                                EndpointFallbackExecutor.AttemptResult.FAILED,
                                endpointFailureMessage
                            )
                        }
                    },
                    onFallback = { _, next ->
                        if (DEBUG) Log.i(TAG, "Trying fallback endpoint $next")
                    }
                )
                if (execution.result == EndpointFallbackExecutor.AttemptResult.FAILED) {
                    uiHandler.post { statusListener?.onFailed(execution.lastFailureMessage) }
                }
            } finally {
                onRtspClientStopped()
            }
        }
    }

    private var endpointUris: List<Uri> = emptyList()
    @Volatile private var endpointConnected = false
    @Volatile private var terminalUnauthorized = false
    @Volatile private var endpointFailureMessage: String? = null
    private var username: String? = null
    private var password: String? = null
    private var userAgent: String? = null
    private var timeout: Long = 5000

    private var playVideo = true
    private var requestMediaImage = false
    private var requestYuv = false
    private var requestBitmap = false

    private var playAudio = true
    private var requestAudio = false

    private var rtspThread: RtspThread? = null
    private var videoQueue = VideoFrameQueue(frameQueueCapacity = 120)
    private var audioQueue = AudioFrameQueue(frameQueueCapacity = 120)
    private var videoDecoder: VideoDecoder? = null
    private var audioDecoder: AudioDecoder? = null

    private var statusListener: RtspStatusListener? = null
    private var frameListener: RtspFrameListener? = null
    private val uiHandler = Handler(Looper.getMainLooper())

    private var surfaceView: SurfaceView? = null
    private var videoMimeType: String = "video/avc"
    private var videoCodecType: VideoCodecType = VideoCodecType.H264
    private var audioMimeType: String = ""
    private var audioSampleRate: Int = 0
    private var audioChannelCount: Int = 0
    private var audioCodecConfig: ByteArray? = null

    private val clientListener = object : RtspClientListener {

        override fun onRtspConnecting() {
            if (DEBUG) Log.v(TAG, "onRtspConnecting()")
        }

        override fun onRtspConnected(sdpInfo: SdpInfo) {
            endpointConnected = true
            if (DEBUG) Log.v(TAG, "onRtspConnected()")
            if (sdpInfo.videoTrack != null) {
                videoQueue.clear()
                when (sdpInfo.videoTrack?.videoCodec) {
                    RtspClientUtils.VIDEO_CODEC_H264 -> {
                        videoMimeType = "video/avc"
                        videoCodecType = VideoCodecType.H264
                    }
                    RtspClientUtils.VIDEO_CODEC_H265 -> {
                        videoMimeType = "video/hevc"
                        videoCodecType = VideoCodecType.H265
                    }
                }
                when (sdpInfo.audioTrack?.audioCodec) {
                    RtspClientUtils.AUDIO_CODEC_AAC -> audioMimeType = "audio/mp4a-latm"
                }
                val parameterSets = listOfNotNull(
                    sdpInfo.videoTrack?.vps,
                    sdpInfo.videoTrack?.sps,
                    sdpInfo.videoTrack?.pps
                )
                // Initialize decoder
                if (sdpInfo.videoTrack?.sps != null && sdpInfo.videoTrack?.pps != null) {
                    val data = ByteArray(parameterSets.sumOf { it.size })
                    var parameterSetOffset = 0
                    parameterSets.forEach {
                        it.copyInto(data, parameterSetOffset)
                        parameterSetOffset += it.size
                    }
                    videoQueue.push(
                        VideoFrame(
                            videoCodecType,
                            isKeyframe = true,
                            data,
                            offset = 0,
                            data.size,
                            timestamp = 0
                        )
                    )
                } else {
                    if (DEBUG) Log.d(TAG, "RTSP SPS and PPS NAL units missed in SDP")
                }
            }
            if (sdpInfo.audioTrack != null) {
                audioQueue.clear()
                when (sdpInfo.audioTrack?.audioCodec) {
                    RtspClientUtils.AUDIO_CODEC_AAC -> audioMimeType = "audio/mp4a-latm"
                }
                audioSampleRate = sdpInfo.audioTrack?.sampleRateHz!!
                audioChannelCount = sdpInfo.audioTrack?.channels!!
                audioCodecConfig = sdpInfo.audioTrack?.config
            }
            startDecoders(sdpInfo)
            uiHandler.post {
                statusListener?.onConnected(sdpInfo)
            }
        }

        override fun onRtspVideoNalUnitReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            val isKeyframe = when (videoCodecType) {
                VideoCodecType.H264 -> VideoCodecUtils.isAnyH264KeyFrame(data, offset, min(length, 1000))
                VideoCodecType.H265 -> VideoCodecUtils.isAnyH265KeyFrame(data, offset, length)
                VideoCodecType.UNKNOWN -> false
            }
            if (length > 0) {
                val frame = VideoFrame(videoCodecType, isKeyframe, data, offset, length, timestamp)
                videoQueue.push(frame)
                frameListener?.onVideoNalUnitReceived(frame)
            } else {
                frameListener?.onVideoNalUnitReceived(null)
                if (DEBUG) Log.e(TAG, "onRtspVideoNalUnitReceived() zero length")
            }
        }

        override fun onRtspVideoFrameReceived(width: Int, height: Int, mediaImage: Image?, yuv: YuvFrame?, bitmap: Bitmap?) {
            frameListener?.onVideoFrameReceived(width, height, mediaImage, yuv, bitmap)
        }

        override fun onRtspAudioSampleReceived(data: ByteArray, offset: Int, length: Int, timestamp: Long) {
            if (length > 0) {
                audioQueue.push(AudioFrame(AudioCodecType.AAC_LC, data, offset, length, timestamp))
                if (requestAudio) {
                    frameListener?.onAudioSampleReceived(AudioFrame(AudioCodecType.AAC_LC, data, offset, length, timestamp))
                }
            } else {
                frameListener?.onAudioSampleReceived(null)
                if (DEBUG) Log.e(TAG, "onRtspAudioSampleReceived() zero length")
            }
        }

        override fun onRtspDisconnecting() {
            if (DEBUG) Log.v(TAG, "onRtspConnected()")
        }

        override fun onRtspDisconnected() {
            if (DEBUG) Log.v(TAG, "onRtspDisconnected()")
            uiHandler.post {
                statusListener?.onDisconnected()
            }
        }

        override fun onRtspFailedUnauthorized() {
            if (DEBUG) Log.v(TAG, "onRtspFailedUnauthorized()")
            terminalUnauthorized = true
            uiHandler.post {
                statusListener?.onUnauthorized()
            }
        }

        override fun onRtspFailed(message: String?) {
            if (DEBUG) Log.v(TAG, "onRtspFailed(message='$message')")
            endpointFailureMessage = message
        }

    }

    private val surfaceCallback = object : SurfaceHolder.Callback {

        override fun surfaceCreated(holder: SurfaceHolder) {
            if (DEBUG) Log.v(TAG, "surfaceCreated()")
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (DEBUG) Log.v(TAG, "surfaceChanged(format=$format, width=$width, height=$height)")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (DEBUG) Log.v(TAG, "surfaceDestroyed()")
        }

    }

    fun init(url: String, username: String? = null, password: String? = null, userAgent: String? = null, timeout: Long = 5000) {
        init(listOf(url), username, password, userAgent, timeout)
    }

    fun init(urls: List<String>, username: String? = null, password: String? = null, userAgent: String? = null, timeout: Long = 5000) {
        require(urls.isNotEmpty()) { "At least one RTSP URL is required" }
        require(urls.none { it.isBlank() }) { "RTSP URLs must not be blank" }
        val parsedUris = urls.distinct().map(Uri::parse)
        require(parsedUris.all { it.scheme.equals("rtsp", true) || it.scheme.equals("rtsps", true) }) {
            "Only RTSP/RTSPS URLs are supported"
        }
        if (DEBUG) Log.v(TAG, "init(urls=$urls, username=$username, userAgent='$userAgent')")
        this.endpointUris = parsedUris
        this.terminalUnauthorized = false
        var urlUsername: String? = null
        var urlPassword: String? = null
        Regex("//.*:.*@").find(urls.first())?.value?.replace("//", "")?.replace("@", "")?.split(":")?.let {
            urlUsername = it[0]
            urlPassword = it[1]
        }
        this.username = username ?: urlUsername
        this.password = password ?: urlPassword
        this.userAgent = userAgent
        this.timeout = timeout
        this.videoQueue.timeout = timeout
        this.audioQueue.timeout = timeout
    }

    fun start(playVideo: Boolean = true, playAudio: Boolean = true) {
        if (DEBUG) Log.v(TAG, "start()")
        if (isStarted()) return
        rtspThread?.stopAsync()
        this.playVideo = playVideo
        this.playAudio = playAudio
        rtspThread = RtspThread()
        rtspThread!!.start()
    }

    fun stop() {
        if (DEBUG) Log.v(TAG, "stop()")
        rtspThread?.stopAsync()
        rtspThread = null
    }

    fun isStarted(): Boolean {
        return rtspThread != null
    }

    fun setStatusListener(listener: RtspStatusListener?) {
        if (DEBUG) Log.v(TAG, "setStatusListener()")
        this.statusListener = listener
    }

    fun setFrameListener(listener: RtspFrameListener?) {
        if (DEBUG) Log.v(TAG, "setFrameListener()")
        this.frameListener = listener
    }

    fun setSurfaceView(surfaceView: SurfaceView?) {
        this.surfaceView = surfaceView
        this.videoDecoder?.setSurfaceView(surfaceView)
    }

    fun setRequestMediaImage(requestMediaImage: Boolean) {
        this.requestMediaImage = requestMediaImage
        this.videoDecoder?.requestMediaImage = requestMediaImage
    }

    fun setRequestYuv(requestYuv: Boolean) {
        this.requestYuv = requestYuv
        this.videoDecoder?.requestYuv = requestYuv
    }

    fun setRequestBitmap(requestBitmap: Boolean) {
        this.requestBitmap = requestBitmap
        this.videoDecoder?.requestBitmap = requestBitmap
    }

    fun setRequestAudioSample(requestAudio: Boolean) {
        this.requestAudio = requestAudio
    }

    private fun onRtspClientStarted() {
        if (DEBUG) Log.v(TAG, "onRtspClientStarted()")
        uiHandler.post { statusListener?.onConnecting() }
    }

    private fun onRtspClientStopped() {
        if (DEBUG) Log.v(TAG, "onRtspClientStopped()")
        stopDecoders()
        rtspThread = null
    }


    private fun startDecoders(sdpInfo: SdpInfo) {
        if (DEBUG) Log.v(TAG, "startDecoders()")
        if (playVideo && videoMimeType.isNotEmpty()) {
            if (DEBUG) Log.i(TAG, "Starting video decoder with mime type \"$videoMimeType\"")
            surfaceView?.holder?.addCallback(surfaceCallback)
            videoDecoder?.stopAsync()
            videoDecoder = VideoDecoder(
                surface = null, surfaceView, requestMediaImage, requestYuv, requestBitmap,
                videoMimeType, sdpInfo.videoTrack!!.frameWidth, sdpInfo.videoTrack!!.frameHeight, rotation = 0,
                videoQueue, videoDecoderType = videoDecoderType,
                clientListener = clientListener, vps = sdpInfo.videoTrack!!.vps,
                sps = sdpInfo.videoTrack!!.sps, pps = sdpInfo.videoTrack!!.pps
            )
            videoDecoder!!.start()
        }
        if (playAudio && audioMimeType.isNotEmpty()) {
            if (DEBUG) Log.i(TAG, "Starting audio decoder with mime type \"$audioMimeType\"")
            audioDecoder?.stopAsync()
            audioDecoder = AudioDecoder(
                audioMimeType, audioSampleRate, audioChannelCount,
                audioCodecConfig, audioQueue
            )
            audioDecoder!!.start()
        }
    }

    private fun stopDecoders(video: Boolean = true, audio: Boolean = true) {
        if (DEBUG) Log.v(TAG, "stopDecoders()")
        if (video) {
            surfaceView?.holder?.removeCallback(surfaceCallback)
            videoDecoder?.stopAsync()
            videoDecoder = null
        }
        if (audio) {
            audioDecoder?.stopAsync()
            audioDecoder = null
        }
    }

}

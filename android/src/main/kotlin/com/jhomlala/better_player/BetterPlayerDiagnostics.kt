package com.jhomlala.better_player

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaDrm
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.google.android.exoplayer2.C
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.TracksInfo
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.analytics.AnalyticsListener.EventTime
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.google.android.exoplayer2.drm.ExoMediaDrm
import com.google.android.exoplayer2.drm.MediaDrmCallback
import com.google.android.exoplayer2.drm.MediaDrmCallbackException
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.upstream.HttpDataSource
import com.google.android.exoplayer2.video.VideoSize
import java.io.IOException
import java.util.UUID

/**
 * Sends playback diagnostics to Dart as `{"event": "diagnostic", "name": ..., "data": {...}}`
 * on the player's event channel. Covers what Dart cannot see: Widevine/device
 * capabilities, DRM licence requests, network load errors (with HTTP status),
 * decoder setup, formats and a snapshot while the player is stuck buffering.
 *
 * Never sends tokens: URLs are reduced to host + path, headers to their names.
 */
internal class BetterPlayerDiagnostics(private val eventSink: QueuingEventSink) {
    private val mainHandler = Handler(Looper.getMainLooper())

    fun emit(name: String, data: Map<String, Any?> = emptyMap()) {
        val event: MutableMap<String, Any?> = HashMap()
        event["event"] = "diagnostic"
        event["name"] = name
        event["data"] = data.mapValues { (_, v) -> sanitizeValue(v) }
        event["nativeTimeMs"] = SystemClock.elapsedRealtime()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            safeSend(event)
        } else {
            mainHandler.post { safeSend(event) }
        }
    }

    private fun safeSend(event: Map<String, Any?>) {
        try {
            eventSink.success(event)
        } catch (e: Exception) {
            Log.e(TAG, "diagnostic send failed: $e")
        }
    }

    private fun sanitizeValue(value: Any?): Any? = when (value) {
        null, is String, is Boolean, is Int, is Long, is Double -> value
        is Float -> value.toDouble()
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to sanitizeValue(v) }
        is List<*> -> value.map { sanitizeValue(it) }
        else -> value.toString()
    }

    companion object {
        private const val TAG = "BetterPlayerDiag"
        private val WIDEVINE_UUID: UUID = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)

        /** Host + path only (drops query strings that can carry tokens). */
        fun safeUrl(url: String?): String {
            if (url.isNullOrEmpty()) return "null"
            return try {
                val uri = Uri.parse(url)
                "${uri.scheme}://${uri.host}${uri.path ?: ""}"
            } catch (e: Exception) {
                "unparseable"
            }
        }

        fun safeUri(uri: Uri?): String = safeUrl(uri?.toString())

        /** Class + message for the whole cause chain, plus the details Android hides in subclasses. */
        fun describeThrowable(t: Throwable?, maxDepth: Int = 6): String {
            if (t == null) return "null"
            val parts = mutableListOf<String>()
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < maxDepth) {
                val extra = StringBuilder()
                when (current) {
                    is HttpDataSource.InvalidResponseCodeException -> {
                        extra.append(" [http=${current.responseCode} ${current.responseMessage}")
                        extra.append(" url=${safeUri(current.dataSpec.uri)}")
                        val body = try {
                            String(current.responseBody, Charsets.UTF_8)
                        } catch (e: Exception) {
                            ""
                        }
                        if (body.isNotBlank()) extra.append(" body=${body.take(400)}")
                        extra.append("]")
                    }
                    is HttpDataSource.HttpDataSourceException -> {
                        extra.append(" [httpType=${current.type} url=${safeUri(current.dataSpec.uri)}]")
                    }
                    is MediaDrmCallbackException -> {
                        extra.append(" [drmUrl=${safeUri(current.uriAfterRedirects)} bytesLoaded=${current.bytesLoaded}]")
                    }
                    is MediaCodec.CryptoException -> {
                        extra.append(" [cryptoErrorCode=${current.errorCode}]")
                    }
                    is MediaCodec.CodecException -> {
                        extra.append(" [diag=${current.diagnosticInfo} transient=${current.isTransient} recoverable=${current.isRecoverable}]")
                    }
                    is MediaDrm.MediaDrmStateException -> {
                        extra.append(" [diag=${current.diagnosticInfo}]")
                    }
                }
                if (Build.VERSION.SDK_INT >= 31 && current is MediaDrm.MediaDrmStateException) {
                    extra.append(" [errorCode=${current.errorCode}]")
                }
                parts.add("${current.javaClass.name}: ${current.message}$extra")
                current = current.cause
                depth++
            }
            return parts.joinToString(" <- caused by ")
        }

        fun describeFormat(format: Format?): String {
            if (format == null) return "null"
            return "id=${format.id} mime=${format.sampleMimeType} container=${format.containerMimeType} " +
                    "codecs=${format.codecs} ${format.width}x${format.height} " +
                    "bitrate=${format.bitrate} fps=${format.frameRate} " +
                    "drmInitData=${format.drmInitData != null} " +
                    "cryptoType=${format.cryptoType}"
        }

        private fun dataTypeName(dataType: Int): String = when (dataType) {
            C.DATA_TYPE_MEDIA -> "media"
            C.DATA_TYPE_MEDIA_INITIALIZATION -> "init"
            C.DATA_TYPE_DRM -> "drm"
            C.DATA_TYPE_MANIFEST -> "manifest"
            C.DATA_TYPE_TIME_SYNCHRONIZATION -> "timeSync"
            C.DATA_TYPE_AD -> "ad"
            C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "progressiveLive"
            else -> "type$dataType"
        }

        private fun trackTypeName(trackType: Int): String = when (trackType) {
            C.TRACK_TYPE_VIDEO -> "video"
            C.TRACK_TYPE_AUDIO -> "audio"
            C.TRACK_TYPE_TEXT -> "text"
            C.TRACK_TYPE_DEFAULT -> "default"
            C.TRACK_TYPE_UNKNOWN -> "unknown"
            else -> "type$trackType"
        }

        fun stateName(state: Int): String = when (state) {
            Player.STATE_IDLE -> "IDLE"
            Player.STATE_BUFFERING -> "BUFFERING"
            Player.STATE_READY -> "READY"
            Player.STATE_ENDED -> "ENDED"
            else -> "state$state"
        }

        /** Device build facts (no identifiers). */
        fun deviceInfo(): Map<String, Any?> {
            val info = linkedMapOf<String, Any?>(
                "manufacturer" to Build.MANUFACTURER,
                "brand" to Build.BRAND,
                "model" to Build.MODEL,
                "device" to Build.DEVICE,
                "product" to Build.PRODUCT,
                "hardware" to Build.HARDWARE,
                "board" to Build.BOARD,
                "sdkInt" to Build.VERSION.SDK_INT,
                "release" to Build.VERSION.RELEASE,
                "buildDisplay" to Build.DISPLAY,
                "incremental" to Build.VERSION.INCREMENTAL
            )
            if (Build.VERSION.SDK_INT >= 23) info["securityPatch"] = Build.VERSION.SECURITY_PATCH
            if (Build.VERSION.SDK_INT >= 31) {
                info["socManufacturer"] = Build.SOC_MANUFACTURER
                info["socModel"] = Build.SOC_MODEL
            }
            return info
        }

        /**
         * Widevine capabilities from a throwaway MediaDrm instance. No device-unique
         * properties (deviceUniqueId / provisioningUniqueId) are read.
         */
        fun widevineInfo(): Map<String, Any?> {
            val info = linkedMapOf<String, Any?>()
            info["widevineSupported"] = try {
                MediaDrm.isCryptoSchemeSupported(WIDEVINE_UUID)
            } catch (e: Exception) {
                "error: $e"
            }
            if (Build.VERSION.SDK_INT >= 28) {
                info["maxSecurityLevelForDevice"] = try {
                    securityLevelName(MediaDrm.getMaxSecurityLevel())
                } catch (e: Exception) {
                    "error: $e"
                }
            }
            var drm: MediaDrm? = null
            try {
                drm = MediaDrm(WIDEVINE_UUID)
                for (prop in listOf(
                    "vendor", "version", "description", "algorithms", "systemId",
                    "securityLevel", "hdcpLevel", "maxHdcpLevel", "usageReportingSupport",
                    "maxNumberOfSessions", "numberOfOpenSessions", "oemCryptoApiVersion",
                    "oemCryptoBuildInformation", "resourceRatingTier", "decryptHashSupport",
                    "SRMUpdateSupport", "provisioningModel"
                )) {
                    info["prop.$prop"] = try {
                        drm.getPropertyString(prop)
                    } catch (e: Exception) {
                        "n/a"
                    }
                }
                if (Build.VERSION.SDK_INT >= 28) {
                    info["connectedHdcpLevel"] = try {
                        hdcpName(drm.connectedHdcpLevel)
                    } catch (e: Exception) {
                        "error: $e"
                    }
                    info["maxHdcpLevelApi"] = try {
                        hdcpName(drm.maxHdcpLevel)
                    } catch (e: Exception) {
                        "error: $e"
                    }
                    info["openSessionCount"] = try {
                        drm.openSessionCount
                    } catch (e: Exception) {
                        "error: $e"
                    }
                    info["maxSessionCount"] = try {
                        drm.maxSessionCount
                    } catch (e: Exception) {
                        "error: $e"
                    }
                }
            } catch (e: Exception) {
                info["mediaDrmError"] = describeThrowable(e)
            } finally {
                try {
                    if (Build.VERSION.SDK_INT >= 28) drm?.close() else @Suppress("DEPRECATION") drm?.release()
                } catch (_: Exception) {
                }
            }
            return info
        }

        /** Which video decoders exist, and which can play secure (L1) content. */
        fun decoderInfo(): Map<String, Any?> {
            val info = linkedMapOf<String, Any?>()
            try {
                val codecs = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
                for (mime in listOf("video/avc", "video/hevc", "video/x-vnd.on2.vp9", "video/av01")) {
                    val decoders = mutableListOf<String>()
                    for (codec in codecs) {
                        if (codec.isEncoder) continue
                        if (codec.supportedTypes.none { it.equals(mime, ignoreCase = true) }) continue
                        val secure = try {
                            codec.getCapabilitiesForType(mime)
                                .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                        } catch (e: Exception) {
                            false
                        }
                        val hw = if (Build.VERSION.SDK_INT >= 29) {
                            if (codec.isHardwareAccelerated) "hw" else "sw"
                        } else ""
                        decoders.add("${codec.name}${if (secure) "(secure)" else ""}${if (hw.isNotEmpty()) "[$hw]" else ""}")
                    }
                    info[mime] = if (decoders.isEmpty()) "none" else decoders.joinToString(", ")
                }
            } catch (e: Exception) {
                info["error"] = describeThrowable(e)
            }
            return info
        }

        private fun securityLevelName(level: Int): String = when (level) {
            MediaDrm.SECURITY_LEVEL_SW_SECURE_CRYPTO -> "SW_SECURE_CRYPTO(L3)"
            MediaDrm.SECURITY_LEVEL_SW_SECURE_DECODE -> "SW_SECURE_DECODE(L3)"
            MediaDrm.SECURITY_LEVEL_HW_SECURE_CRYPTO -> "HW_SECURE_CRYPTO(L2)"
            MediaDrm.SECURITY_LEVEL_HW_SECURE_DECODE -> "HW_SECURE_DECODE(L1)"
            MediaDrm.SECURITY_LEVEL_HW_SECURE_ALL -> "HW_SECURE_ALL(L1)"
            MediaDrm.SECURITY_LEVEL_UNKNOWN -> "UNKNOWN"
            else -> "level$level"
        }

        private fun hdcpName(level: Int): String = when (level) {
            MediaDrm.HDCP_NONE -> "NONE"
            MediaDrm.HDCP_V1 -> "V1"
            MediaDrm.HDCP_V2 -> "V2"
            MediaDrm.HDCP_V2_1 -> "V2_1"
            MediaDrm.HDCP_V2_2 -> "V2_2"
            MediaDrm.HDCP_V2_3 -> "V2_3"
            MediaDrm.HDCP_NO_DIGITAL_OUTPUT -> "NO_DIGITAL_OUTPUT"
            MediaDrm.HDCP_LEVEL_UNKNOWN -> "UNKNOWN"
            else -> "level$level"
        }
    }

    /** Wraps the licence callback to time each request and capture the server's error reply. */
    inner class LoggingMediaDrmCallback(
        private val delegate: MediaDrmCallback,
        private val licenseUrl: String?
    ) : MediaDrmCallback {
        private var keyRequestCount = 0

        override fun executeProvisionRequest(
            uuid: UUID,
            request: ExoMediaDrm.ProvisionRequest
        ): ByteArray {
            val started = SystemClock.elapsedRealtime()
            emit("drm_provision_request", mapOf("url" to safeUrl(request.defaultUrl)))
            try {
                val response = delegate.executeProvisionRequest(uuid, request)
                emit(
                    "drm_provision_response", mapOf(
                        "bytes" to response.size,
                        "tookMs" to SystemClock.elapsedRealtime() - started
                    )
                )
                return response
            } catch (e: Exception) {
                emit(
                    "drm_provision_failed", mapOf(
                        "tookMs" to SystemClock.elapsedRealtime() - started,
                        "error" to describeThrowable(e)
                    )
                )
                throw e
            }
        }

        override fun executeKeyRequest(uuid: UUID, request: ExoMediaDrm.KeyRequest): ByteArray {
            keyRequestCount++
            val started = SystemClock.elapsedRealtime()
            emit(
                "drm_license_request", mapOf(
                    "n" to keyRequestCount,
                    "licenseUrl" to safeUrl(licenseUrl),
                    "requestDefaultUrl" to safeUrl(request.licenseServerUrl),
                    "requestBytes" to request.data.size,
                    "requestType" to request.requestType
                )
            )
            try {
                val response = delegate.executeKeyRequest(uuid, request)
                val preview = String(response, Charsets.ISO_8859_1)
                val looksLikeText = preview.take(64).all { it.code in 9..126 }
                emit(
                    "drm_license_response", mapOf(
                        "n" to keyRequestCount,
                        "bytes" to response.size,
                        "tookMs" to SystemClock.elapsedRealtime() - started,
                        // A Widevine licence is binary; readable text here usually means
                        // the licence server sent back an error message with HTTP 200.
                        "textReply" to if (looksLikeText) preview.take(400) else null
                    )
                )
                return response
            } catch (e: Exception) {
                emit(
                    "drm_license_failed", mapOf(
                        "n" to keyRequestCount,
                        "tookMs" to SystemClock.elapsedRealtime() - started,
                        "error" to describeThrowable(e)
                    )
                )
                throw e
            }
        }
    }

    /** Low-level player events Dart never sees. */
    inner class Listener(private val player: ExoPlayer) : AnalyticsListener {
        private var mediaLoadsCompleted = 0
        private var mediaBytesLoaded = 0L
        private var loadErrors = 0
        private var lastBandwidthEmitMs = 0L
        private var bufferingSnapshots = 0
        private var bufferingSince = 0L
        private val snapshotRunnable = object : Runnable {
            override fun run() {
                if (player.playbackState != Player.STATE_BUFFERING) return
                if (bufferingSnapshots >= 40) return
                bufferingSnapshots++
                emit("native_buffering_snapshot", snapshot())
                mainHandler.postDelayed(this, 5000)
            }
        }

        fun snapshot(): Map<String, Any?> = mapOf(
            "state" to stateName(player.playbackState),
            "playWhenReady" to player.playWhenReady,
            "suppression" to player.playbackSuppressionReason,
            "isLoading" to player.isLoading,
            "positionMs" to player.currentPosition,
            "bufferedPositionMs" to player.bufferedPosition,
            "totalBufferedMs" to player.totalBufferedDuration,
            "durationMs" to player.duration,
            "bufferingForMs" to if (bufferingSince > 0) SystemClock.elapsedRealtime() - bufferingSince else 0,
            "mediaLoadsCompleted" to mediaLoadsCompleted,
            "mediaBytesLoaded" to mediaBytesLoaded,
            "loadErrors" to loadErrors,
            "videoFormat" to describeFormat(player.videoFormat),
            "audioFormat" to describeFormat(player.audioFormat),
            "videoSize" to "${player.videoSize.width}x${player.videoSize.height}"
        )

        fun stop() {
            mainHandler.removeCallbacks(snapshotRunnable)
        }

        override fun onPlaybackStateChanged(eventTime: EventTime, state: Int) {
            mainHandler.removeCallbacks(snapshotRunnable)
            if (state == Player.STATE_BUFFERING) {
                bufferingSince = SystemClock.elapsedRealtime()
                bufferingSnapshots = 0
                mainHandler.postDelayed(snapshotRunnable, 5000)
            } else {
                bufferingSince = 0
            }
            emit("native_state", mapOf("state" to stateName(state), "positionMs" to player.currentPosition))
        }

        override fun onPlayWhenReadyChanged(eventTime: EventTime, playWhenReady: Boolean, reason: Int) {
            emit("native_play_when_ready", mapOf("playWhenReady" to playWhenReady, "reason" to reason))
        }

        override fun onPlaybackSuppressionReasonChanged(eventTime: EventTime, playbackSuppressionReason: Int) {
            emit("native_suppression", mapOf("reason" to playbackSuppressionReason))
        }

        override fun onLoadCompleted(eventTime: EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
            if (mediaLoadData.dataType == C.DATA_TYPE_MEDIA) {
                mediaLoadsCompleted++
                mediaBytesLoaded += loadEventInfo.bytesLoaded
                // The first few media loads prove segments arrive; after that just count.
                if (mediaLoadsCompleted > 3) return
            }
            emit(
                "load_completed", mapOf(
                    "dataType" to dataTypeName(mediaLoadData.dataType),
                    "trackType" to trackTypeName(mediaLoadData.trackType),
                    "uri" to safeUri(loadEventInfo.uri),
                    "bytes" to loadEventInfo.bytesLoaded,
                    "tookMs" to loadEventInfo.loadDurationMs,
                    "format" to mediaLoadData.trackFormat?.let { describeFormat(it) }
                )
            )
        }

        override fun onLoadError(
            eventTime: EventTime,
            loadEventInfo: LoadEventInfo,
            mediaLoadData: MediaLoadData,
            error: IOException,
            wasCanceled: Boolean
        ) {
            loadErrors++
            if (loadErrors > 30) return
            emit(
                "load_error", mapOf(
                    "n" to loadErrors,
                    "dataType" to dataTypeName(mediaLoadData.dataType),
                    "trackType" to trackTypeName(mediaLoadData.trackType),
                    "uri" to safeUri(loadEventInfo.dataSpec.uri),
                    "wasCanceled" to wasCanceled,
                    "error" to describeThrowable(error)
                )
            )
        }

        override fun onDownstreamFormatChanged(eventTime: EventTime, mediaLoadData: MediaLoadData) {
            emit(
                "downstream_format", mapOf(
                    "trackType" to trackTypeName(mediaLoadData.trackType),
                    "format" to describeFormat(mediaLoadData.trackFormat)
                )
            )
        }

        override fun onBandwidthEstimate(
            eventTime: EventTime,
            totalLoadTimeMs: Int,
            totalBytesLoaded: Long,
            bitrateEstimate: Long
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastBandwidthEmitMs < 15000) return
            lastBandwidthEmitMs = now
            emit("bandwidth", mapOf("bitrateEstimate" to bitrateEstimate))
        }

        override fun onDrmSessionAcquired(eventTime: EventTime, state: Int) {
            emit("drm_session_acquired", mapOf("state" to state))
        }

        override fun onDrmKeysLoaded(eventTime: EventTime) {
            emit("drm_keys_loaded")
        }

        override fun onDrmKeysRestored(eventTime: EventTime) {
            emit("drm_keys_restored")
        }

        override fun onDrmSessionManagerError(eventTime: EventTime, error: Exception) {
            emit("drm_session_error", mapOf("error" to describeThrowable(error)))
        }

        override fun onDrmSessionReleased(eventTime: EventTime) {
            emit("drm_session_released")
        }

        override fun onVideoDecoderInitialized(
            eventTime: EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            emit("video_decoder", mapOf("name" to decoderName, "initMs" to initializationDurationMs))
        }

        override fun onAudioDecoderInitialized(
            eventTime: EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long
        ) {
            emit("audio_decoder", mapOf("name" to decoderName, "initMs" to initializationDurationMs))
        }

        override fun onVideoInputFormatChanged(
            eventTime: EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            emit("video_input_format", mapOf("format" to describeFormat(format)))
        }

        override fun onAudioInputFormatChanged(
            eventTime: EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?
        ) {
            emit("audio_input_format", mapOf("format" to describeFormat(format)))
        }

        override fun onVideoCodecError(eventTime: EventTime, videoCodecError: Exception) {
            emit("video_codec_error", mapOf("error" to describeThrowable(videoCodecError)))
        }

        override fun onAudioCodecError(eventTime: EventTime, audioCodecError: Exception) {
            emit("audio_codec_error", mapOf("error" to describeThrowable(audioCodecError)))
        }

        override fun onRenderedFirstFrame(eventTime: EventTime, output: Any, renderTimeMs: Long) {
            emit("first_frame_rendered", mapOf("positionMs" to player.currentPosition))
        }

        override fun onDroppedVideoFrames(eventTime: EventTime, droppedFrames: Int, elapsedMs: Long) {
            emit("dropped_frames", mapOf("count" to droppedFrames, "overMs" to elapsedMs))
        }

        override fun onVideoSizeChanged(eventTime: EventTime, videoSize: VideoSize) {
            emit("video_size", mapOf("size" to "${videoSize.width}x${videoSize.height}"))
        }

        override fun onTracksInfoChanged(eventTime: EventTime, tracksInfo: TracksInfo) {
            val groups = mutableListOf<String>()
            for (groupInfo in tracksInfo.trackGroupInfos) {
                val group = groupInfo.trackGroup
                for (i in 0 until group.length) {
                    val f = group.getFormat(i)
                    groups.add(
                        "${trackTypeName(groupInfo.trackType)}:${f.sampleMimeType}:${f.width}x${f.height}:" +
                                "${f.bitrate}:supported=${groupInfo.isTrackSupported(i)}:" +
                                "selected=${groupInfo.isTrackSelected(i)}"
                    )
                }
            }
            emit("tracks", mapOf("count" to groups.size, "tracks" to groups.take(25)))
        }

        override fun onPlayerError(eventTime: EventTime, error: PlaybackException) {
            emit(
                "player_error", mapOf(
                    "errorCode" to error.errorCode,
                    "errorCodeName" to error.errorCodeName,
                    "error" to describeThrowable(error),
                    "snapshot" to snapshot()
                )
            )
        }
    }
}

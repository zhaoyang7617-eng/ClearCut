package com.novacut.editor.engine

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import com.novacut.editor.engine.AppLog
import com.novacut.editor.model.VideoCodec

/**
 * Device-aware encoder capability probe. Answers the question
 * "will this device *actually* encode at the requested spec?"
 * beyond the coarser "is the codec present at all?" check that
 * `ExportConfig.getAvailableCodecs` already performs.
 *
 * The goal is to surface a warning in the pre-flight report when
 * the user has picked a combination the encoder advertises it can't
 * handle (e.g. 4K HEVC on a phone whose HEVC encoder only goes to
 * 1080p), so the user can dial it back before burning 40 minutes of
 * render time and hitting a Transformer error mid-export.
 *
 * Queries are narrow on purpose: we only look at advertised
 * capabilities, not runtime probes. Real-world encoders sometimes
 * refuse configurations they claim to support, and that's left to
 * Media3 Transformer's own retry logic. This probe is an *early
 * warning*, not a guarantee.
 */
object EncoderCapabilityProbe {

    private const val TAG = "EncoderCapabilityProbe"
    private const val MIME_DOLBY_VISION = "video/dolby-vision"
    private const val FEATURE_HDR_EDITING = "FEATURE_HdrEditing"
    private const val FEATURE_HLG_EDITING = "FEATURE_HlgEditing"

    /**
     * APV (Advanced Professional Video) codec MIME type. Android 16 native;
     * Galaxy S26 Ultra is the first phone with hardware support. APV is an
     * intra-frame codec designed for pro post-production with 4:2:2 10-bit
     * at up to 2 Gbps. ClearCut treats APV as ingest-only — see R6.11.
     */
    const val MIME_APV = "video/apv"

    enum class HdrExportFormat(val displayName: String) {
        HDR10("HDR10"),
        HDR10_PLUS("HDR10+"),
        DOLBY_VISION_PROFILE_10("Dolby Vision Profile 10")
    }

    enum class DeviceEncodingTier(val displayName: String) {
        STANDARD("标准"),
        ADVANCED("高级"),
        PREMIUM("旗舰")
    }

    /**
     * HDR editing features explicitly advertised by an encoder. Profile
     * levels describe formats, but these feature flags are the platform's
     * contract that the encoder accepts HDR editing input.
     */
    data class HdrFeatureSupport(
        val hdrEditing: Boolean = false,
        val hlgEditing: Boolean = false,
    ) {
        val canPreserveHdr: Boolean get() = hdrEditing || hlgEditing

        val advertisedFeatureNames: Set<String>
            get() = buildSet {
                if (hdrEditing) add(FEATURE_HDR_EDITING)
                if (hlgEditing) add(FEATURE_HLG_EDITING)
            }
    }

    data class HdrProfileSupport(
        val codec: VideoCodec,
        val supportedFormats: Set<HdrExportFormat>,
        val maxWidth: Int = 0,
        val maxHeight: Int = 0,
        val maxBitrate: Int = 0,
        val encoderNames: Set<String> = emptySet(),
        val featureSupport: HdrFeatureSupport = HdrFeatureSupport(),
    ) {
        val hasAnyHdr: Boolean get() = supportedFormats.isNotEmpty()
        val canPreserveHdr: Boolean get() = featureSupport.canPreserveHdr
        val hasHdr10Plus: Boolean get() = HdrExportFormat.HDR10_PLUS in supportedFormats
        val hasDolbyVisionProfile10: Boolean
            get() = HdrExportFormat.DOLBY_VISION_PROFILE_10 in supportedFormats

        fun featureFailureReason(): String {
            return "无法使用 ${codec.label} 进行 HDR 导出：所选编码器未报告 $FEATURE_HDR_EDITING 或 $FEATURE_HLG_EDITING。请选择 SDR 或其他编码格式。"
        }
    }

    data class DeviceEncodingTierHint(
        val tier: DeviceEncodingTier,
        val detail: String,
        val availableCodecs: Set<VideoCodec>,
        val hdrFormats: Set<HdrExportFormat>,
        val hasHardwareHevc: Boolean,
        val hasHardwareAv1: Boolean,
        val hasHardwareVp9: Boolean
    )

    /**
     * Per-codec capability snapshot. `supported = false` means no
     * encoder on this device accepts the (width, height, framerate,
     * bitrate) tuple. Reasons are human-readable for toast display.
     */
    data class Capability(
        val supported: Boolean,
        val reason: String? = null,
        /** False means the probe could not establish a positive capability claim. */
        val known: Boolean = true,
    )

    /**
     * Check whether any of the device's advertised encoders for
     * `codec` can handle the requested (w, h, fps, bitrate). Walks
     * every matching encoder so a device with multiple (e.g.
     * Qualcomm + Google-software) HEVC encoders is correctly
     * reported as supporting the higher of the two's capabilities.
     */
    fun check(
        codec: VideoCodec,
        width: Int,
        height: Int,
        framerate: Int,
        bitrate: Int
    ): Capability {
        if (width <= 0 || height <= 0 || framerate <= 0 || bitrate <= 0) {
            return Capability(false, "导出尺寸无效")
        }
        val mimeType = codec.mimeType
        val codecInfos = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
                .filter { it.supportedTypes.any { t -> t.equals(mimeType, ignoreCase = true) } }
        } catch (e: Exception) {
            AppLog.w(TAG, "MediaCodecList lookup failed for ${codec.label}", e)
            return Capability(
                supported = false,
                reason = "无法查询编码器能力；导出完成后将再验证输出文件。",
                known = false,
            )
        }
        if (codecInfos.isEmpty()) {
            return Capability(
                false,
                "设备上没有 ${codec.label} 编码器；回退到 H.264 更稳妥。"
            )
        }

        // Accumulate the reason from the *best* encoder that declines
        // so the warning explains which limit was hit. Many devices
        // expose a software encoder that accepts anything — if any
        // encoder says yes we report supported=true.
        var firstReason: String? = null
        for (info in codecInfos) {
            val caps = try {
                info.getCapabilitiesForType(mimeType)
            } catch (_: IllegalArgumentException) {
                continue
            } ?: continue
            val videoCaps = caps.videoCapabilities ?: continue

            if (!videoCaps.isSizeSupported(width, height)) {
                firstReason = firstReason ?: "此设备上的 ${codec.label} 最高支持 " +
                    "${videoCaps.supportedWidths.upper}×${videoCaps.supportedHeights.upper}"
                continue
            }
            if (!videoCaps.areSizeAndRateSupported(width, height, framerate.toDouble())) {
                firstReason = firstReason ?: "此设备上的 ${codec.label} 在 ${width}×${height} 下最高支持 ${videoCaps.getSupportedFrameRatesFor(width, height).upper.toInt()} fps"
                continue
            }
            val bitrateRange = videoCaps.bitrateRange
            if (bitrate !in bitrateRange.lower..bitrateRange.upper) {
                firstReason = firstReason ?: "此设备上的 ${codec.label} 码率上限为 ${bitrateRange.upper / 1_000_000} Mbps"
                continue
            }
            return Capability(true)
        }
        return Capability(false, firstReason ?: "${codec.label} 无法编码当前配置")
    }

    /**
     * Returns the HDR profiles the device advertises for the selected export codec.
     *
     * Dolby Vision Profile 10 is AV1-based on Android (`dav1.10`), so ClearCut
     * reports it with AV1 rather than HEVC. This is still an advisory: Media3
     * and the platform encoder perform the authoritative negotiation at export
     * time.
     */
    fun queryHdrProfiles(codec: VideoCodec): HdrProfileSupport {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return HdrProfileSupport(codec = codec, supportedFormats = emptySet())
        }

        val mimeTypes = buildSet {
            add(codec.mimeType)
            if (codec == VideoCodec.AV1) add(MIME_DOLBY_VISION)
        }
        val formats = mutableSetOf<HdrExportFormat>()
        val encoders = mutableSetOf<String>()
        var maxWidth = 0
        var maxHeight = 0
        var maxBitrate = 0
        var featureSupport = HdrFeatureSupport()

        for ((info, mimeType) in matchingEncoderEntries(mimeTypes)) {
            val caps = try {
                info.getCapabilitiesForType(mimeType)
            } catch (_: IllegalArgumentException) {
                continue
            } catch (t: Throwable) {
                AppLog.w(TAG, "Capability lookup failed for ${info.name} / $mimeType", t)
                continue
            }

            val entryFeatureSupport = readHdrFeatureSupport(caps)
            featureSupport = featureSupport.merge(entryFeatureSupport)
            val discovered = caps.profileLevels
                ?.mapNotNull { classifyHdrProfile(mimeType, it) }
                ?.toSet()
                .orEmpty()

            if (discovered.isNotEmpty() || entryFeatureSupport.canPreserveHdr) {
                formats += discovered
                encoders += info.name
                caps.videoCapabilities?.let { videoCaps ->
                    maxWidth = maxOf(maxWidth, videoCaps.supportedWidths.upper)
                    maxHeight = maxOf(maxHeight, videoCaps.supportedHeights.upper)
                    maxBitrate = maxOf(maxBitrate, videoCaps.bitrateRange.upper)
                }
            }
        }

        return HdrProfileSupport(
            codec = codec,
            supportedFormats = formats,
            maxWidth = maxWidth,
            maxHeight = maxHeight,
            maxBitrate = maxBitrate,
            encoderNames = encoders,
            featureSupport = featureSupport,
        )
    }

    /** A PII-free line for the local diagnostic bundle. */
    internal fun diagnosticHdrFeatureLine(info: MediaCodecInfo, mimeType: String): String {
        val support = try {
            info.getCapabilitiesForType(mimeType)?.let(::readHdrFeatureSupport)
        } catch (t: Throwable) {
            AppLog.w(TAG, "HDR feature lookup failed for ${info.name} / $mimeType", t)
            null
        }
        return buildString {
            append("encoder_features\t${info.name}\t$mimeType")
            append("\t$FEATURE_HDR_EDITING=${support?.hdrEditing ?: false}")
            append("\t$FEATURE_HLG_EDITING=${support?.hlgEditing ?: false}")
        }
    }

    /**
     * Coarse user-facing device tier. It is intentionally derived from actual
     * advertised encoders instead of model names so Tensor, Snapdragon, Exynos,
     * and future devices all get the same treatment.
     */
    fun deviceTierHint(): DeviceEncodingTierHint {
        val availableCodecs = VideoCodec.entries
            .filter { matchingEncoderEntries(setOf(it.mimeType)).isNotEmpty() }
            .toSet()
        val hdrFormats = VideoCodec.entries
            .flatMap { queryHdrProfiles(it).supportedFormats }
            .toSet()
        val hasHardwareHevc = hasHardwareEncoder(VideoCodec.HEVC.mimeType)
        val hasHardwareAv1 = hasHardwareEncoder(VideoCodec.AV1.mimeType)
        val hasHardwareVp9 = hasHardwareEncoder(VideoCodec.VP9.mimeType)

        val tier = when {
            hasHardwareAv1 && hasHardwareVp9 -> DeviceEncodingTier.PREMIUM
            hasHardwareHevc || hasHardwareAv1 || hasHardwareVp9 || hdrFormats.isNotEmpty() ->
                DeviceEncodingTier.ADVANCED
            else -> DeviceEncodingTier.STANDARD
        }
        val detail = when (tier) {
            DeviceEncodingTier.PREMIUM -> {
                val hdr = formatHdrList(hdrFormats)
                if (hdr.isNotBlank()) {
                    "检测到硬件 AV1 和 VP9 编码器，并支持 $hdr HDR 配置。"
                } else {
                    "检测到硬件 AV1 和 VP9 编码器，可用于高效的现代编码导出。"
                }
            }
            DeviceEncodingTier.ADVANCED -> {
                val codecs = availableCodecs
                    .filter { it != VideoCodec.H264 }
                    .joinToString(", ") { it.label }
                    .ifBlank { "现代编码格式" }
                val hdr = formatHdrList(hdrFormats)
                if (hdr.isNotBlank()) {
                    "可使用 $codecs 编码，并支持 $hdr HDR 配置。"
                } else {
                    "可使用 $codecs 编码。HDR 支持取决于所选编码格式和源素材。"
                }
            }
            DeviceEncodingTier.STANDARD ->
                "检测到基础 H.264 导出路径。较长时间线建议使用更保守的导出设置。"
        }

        return DeviceEncodingTierHint(
            tier = tier,
            detail = detail,
            availableCodecs = availableCodecs,
            hdrFormats = hdrFormats,
            hasHardwareHevc = hasHardwareHevc,
            hasHardwareAv1 = hasHardwareAv1,
            hasHardwareVp9 = hasHardwareVp9
        )
    }

    // --- R6.11 APV ingest probe ---

    data class ApvSupport(
        /** True iff the device advertises at least one APV decoder. */
        val hasDecoder: Boolean,
        /** True iff the decoder is hardware-accelerated (not software fallback). */
        val isHardwareDecoder: Boolean,
        /** Decoder codec names for the diagnostic bundle. */
        val decoderNames: List<String>,
    ) {
        val isUsable: Boolean get() = hasDecoder
    }

    /**
     * Probe APV (Advanced Professional Video) decoder availability.
     *
     * ClearCut treats APV as **ingest-only** (R6.11c): APV is intra-frame
     * coding designed for pro post-production; encoded files are 10–50×
     * larger than HEVC equivalents. We surface a "Source is APV — large
     * file" chip on import when this returns `hasDecoder = true`, and we
     * never encode to APV by default. Encoder probing is omitted intentionally.
     */
    fun probeApvIngest(): ApvSupport {
        val entries = matchingDecoderEntries(setOf(MIME_APV))
        if (entries.isEmpty()) {
            return ApvSupport(hasDecoder = false, isHardwareDecoder = false, decoderNames = emptyList())
        }
        val isHardware = entries.any { (info, _) -> info.isHardwareAcceleratedCompat() }
        val names = entries.map { (info, _) -> info.name }.distinct()
        return ApvSupport(hasDecoder = true, isHardwareDecoder = isHardware, decoderNames = names)
    }

    private fun matchingDecoderEntries(mimeTypes: Set<String>): List<Pair<MediaCodecInfo, String>> {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { !it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { supported ->
                            mimeTypes.any { wanted -> supported.equals(wanted, ignoreCase = true) }
                        }
                        .map { supported -> info to supported }
                }
        } catch (t: Throwable) {
            AppLog.w(TAG, "MediaCodecList decoder lookup failed", t)
            emptyList()
        }
    }

    private fun matchingEncoderEntries(mimeTypes: Set<String>): List<Pair<MediaCodecInfo, String>> {
        return try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder }
                .flatMap { info ->
                    info.supportedTypes
                        .filter { supported ->
                            mimeTypes.any { wanted -> supported.equals(wanted, ignoreCase = true) }
                        }
                        .map { supported -> info to supported }
                }
        } catch (t: Throwable) {
            AppLog.w(TAG, "MediaCodecList lookup failed", t)
            emptyList()
        }
    }

    private fun hasHardwareEncoder(mimeType: String): Boolean {
        return matchingEncoderEntries(setOf(mimeType)).any { (info, _) -> info.isHardwareAcceleratedCompat() }
    }

    private fun readHdrFeatureSupport(caps: MediaCodecInfo.CodecCapabilities): HdrFeatureSupport {
        val hdrEditing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            isFeatureSupportedSafely(caps, MediaCodecInfo.CodecCapabilities.FEATURE_HdrEditing)
        } else {
            false
        }
        val hlgEditing = if (Build.VERSION.SDK_INT >= 35) {
            isFeatureSupportedSafely(caps, MediaCodecInfo.CodecCapabilities.FEATURE_HlgEditing)
        } else {
            false
        }
        return HdrFeatureSupport(hdrEditing = hdrEditing, hlgEditing = hlgEditing)
    }

    private fun isFeatureSupportedSafely(
        caps: MediaCodecInfo.CodecCapabilities,
        feature: String,
    ): Boolean {
        return try {
            caps.isFeatureSupported(feature)
        } catch (t: Throwable) {
            AppLog.w(TAG, "Codec feature lookup failed for $feature", t)
            false
        }
    }

    private fun HdrFeatureSupport.merge(other: HdrFeatureSupport): HdrFeatureSupport {
        return HdrFeatureSupport(
            hdrEditing = hdrEditing || other.hdrEditing,
            hlgEditing = hlgEditing || other.hlgEditing,
        )
    }

    private fun MediaCodecInfo.isHardwareAcceleratedCompat(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isHardwareAccelerated
        } else {
            val lower = name.lowercase()
            !lower.startsWith("omx.google.") &&
                !lower.startsWith("c2.android.") &&
                !lower.contains(".sw.") &&
                !lower.contains("software")
        }
    }

    private fun classifyHdrProfile(
        mimeType: String,
        profileLevel: CodecProfileLevel
    ): HdrExportFormat? {
        return when {
            mimeType.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) -> when (profileLevel.profile) {
                CodecProfileLevel.HEVCProfileMain10HDR10 -> HdrExportFormat.HDR10
                CodecProfileLevel.HEVCProfileMain10HDR10Plus -> HdrExportFormat.HDR10_PLUS
                else -> null
            }
            mimeType.equals(MediaFormat.MIMETYPE_VIDEO_AV1, ignoreCase = true) -> when (profileLevel.profile) {
                CodecProfileLevel.AV1ProfileMain10HDR10 -> HdrExportFormat.HDR10
                CodecProfileLevel.AV1ProfileMain10HDR10Plus -> HdrExportFormat.HDR10_PLUS
                else -> null
            }
            mimeType.equals(MediaFormat.MIMETYPE_VIDEO_VP9, ignoreCase = true) -> when (profileLevel.profile) {
                CodecProfileLevel.VP9Profile2HDR,
                CodecProfileLevel.VP9Profile3HDR -> HdrExportFormat.HDR10
                CodecProfileLevel.VP9Profile2HDR10Plus,
                CodecProfileLevel.VP9Profile3HDR10Plus -> HdrExportFormat.HDR10_PLUS
                else -> null
            }
            mimeType.equals(MIME_DOLBY_VISION, ignoreCase = true) -> when (profileLevel.profile) {
                CodecProfileLevel.DolbyVisionProfileDvav110 -> HdrExportFormat.DOLBY_VISION_PROFILE_10
                else -> null
            }
            else -> null
        }
    }

    private fun formatHdrList(formats: Set<HdrExportFormat>): String {
        return formats
            .map { it.displayName }
            .sorted()
            .joinToString(", ")
    }
}

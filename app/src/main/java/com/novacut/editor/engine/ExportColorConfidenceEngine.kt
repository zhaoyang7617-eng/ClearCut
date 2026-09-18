package com.novacut.editor.engine

import com.novacut.editor.model.ExportConfig
import com.novacut.editor.model.Track
import com.novacut.editor.model.TrackType
import com.novacut.editor.model.VideoCodec

/**
 * Pure pre-flight analyzer for export color and HDR confidence.
 *
 * The export pipeline still performs the authoritative Media3 negotiation at
 * render time. This class keeps the user-facing forecast deterministic and
 * easy to unit-test before any Android codec APIs are involved.
 */
object ExportColorConfidenceEngine {

    enum class Tone { GOOD, INFO, WARNING }

    data class HdrEncodeSupport(
        val supportedFormats: Set<String> = emptySet(),
        val maxWidth: Int = 0,
        val maxHeight: Int = 0,
        val maxBitrate: Int = 0,
        /** Null preserves the legacy pure-analysis fixture contract. Production passes a probe result. */
        val featureSupport: EncoderCapabilityProbe.HdrFeatureSupport? = null,
    ) {
        val hasAnyHdr: Boolean get() = supportedFormats.isNotEmpty()
        val canPreserveHdr: Boolean
            get() = featureSupport?.canPreserveHdr ?: hasAnyHdr
    }

    data class SourceHdrSummary(
        val supportedFormats: Set<String> = emptySet(),
        val inspectedSourceCount: Int = 0,
        val totalSourceCount: Int = 0,
        val apvSourceCount: Int = 0
    ) {
        val hasHdrSource: Boolean get() = supportedFormats.isNotEmpty()
        val hasApvSource: Boolean get() = apvSourceCount > 0
        val hasUltraHdrGainMap: Boolean
            get() = supportedFormats.any { format ->
                format.contains("Ultra HDR", ignoreCase = true) &&
                    format.contains("gain map", ignoreCase = true)
            }
        val isFullyInspected: Boolean
            get() = totalSourceCount > 0 && inspectedSourceCount >= totalSourceCount
    }

    data class Chip(
        val label: String,
        val detail: String,
        val tone: Tone
    )

    data class Report(
        val chips: List<Chip>,
        val warnings: List<String>
    ) {
        val hasWarnings: Boolean get() = warnings.isNotEmpty()
    }

    fun analyze(
        config: ExportConfig,
        width: Int,
        height: Int,
        hdrSupport: HdrEncodeSupport,
        sourceSummary: SourceHdrSummary = SourceHdrSummary(),
        projectColorPolicy: ProjectColorPolicy = ProjectColorPolicy.DEFAULT,
        overlaySummary: HdrOverlaySummary = HdrOverlaySummary(),
    ): Report {
        val chips = mutableListOf<Chip>()
        val warnings = mutableListOf<String>()

        addSourceChips(sourceSummary, chips)

        if (!config.hdr10PlusMetadata) {
            chips += Chip(
                label = "SDR 输出",
                detail = "已关闭 HDR 元数据，以获得更广泛的播放兼容性。",
                tone = Tone.GOOD
            )
            chips += Chip(
                label = "Rec.709 兼容",
                detail = "当前导出设置优先采用标准 SDR 社交视频流程。",
                tone = Tone.INFO
            )
            if (sourceSummary.hasHdrSource) {
                chips += Chip(
                    label = "HDR 源素材",
                    detail = "检测到 ${sourceSummary.formatList()} 源素材；如需 HDR 输出，请开启“保留 HDR 元数据”。",
                    tone = Tone.INFO
                )
            }
            addProjectColorPolicyChips(projectColorPolicy, config, chips, warnings)
            return Report(chips = chips, warnings = warnings)
        }

        if (!config.codec.canCarryHdr()) {
            chips += Chip(
                label = "HDR 不可用",
                detail = "${config.codec.label} 导出将按 SDR 处理。",
                tone = Tone.WARNING
            )
            warnings += "${config.codec.label} 无法在 ClearCut 导出中承载 HDR。请先切换到 HEVC、AV1 或 VP9，再保留 HDR 元数据。"
            addProjectColorPolicyChips(projectColorPolicy, config, chips, warnings)
            return Report(chips = chips, warnings = warnings)
        }

        val hdrOverlayDecision = HdrOverlayPolicy.evaluate(
            hdrRequested = config.hdr10PlusMetadata,
            codec = config.codec,
            overlays = overlaySummary,
        )
        hdrOverlayDecision.disclosure?.let { disclosure ->
            chips += Chip(
                label = if (hdrOverlayDecision.samplerBudgetExceeded) {
                    "HDR 叠加层资源上限"
                } else {
                    "HDR 叠加层 → SDR"
                },
                detail = disclosure,
                tone = Tone.WARNING,
            )
            warnings += disclosure
        }

        if (!hdrSupport.canPreserveHdr) {
            chips += Chip(
                label = if (hdrSupport.featureSupport == null) "未声明 HDR 支持" else "HDR 不可用",
                detail = if (hdrSupport.featureSupport == null) {
                    "未找到 ${config.codec.label} 的 HDR 编码配置。"
                } else {
                    "所选编码器未报告 FEATURE_HdrEditing 或 FEATURE_HlgEditing。"
                },
                tone = Tone.WARNING
            )
            warnings += if (hdrSupport.featureSupport == null) {
                "此设备未声明 ${config.codec.label} 的 HDR 编码支持；Media3 可能进行色调映射或回退到 SDR。"
            } else {
                "此设备未为 ${config.codec.label} 报告 FEATURE_HdrEditing 或 FEATURE_HlgEditing；请选择 SDR 或其他编码格式。"
            }
        } else if (!hdrSupport.hasAnyHdr) {
            val featureNames = hdrSupport.featureSupport
                ?.advertisedFeatureNames
                ?.sorted()
                ?.joinToString(", ")
                .orEmpty()
            chips += Chip(
                label = "HDR 功能支持",
                detail = if (featureNames.isBlank()) {
                    "${config.codec.label} 已报告 HDR 编辑支持。"
                } else {
                    "${config.codec.label} 已报告 $featureNames，但未返回明确命名的 HDR 配置。"
                },
                tone = Tone.INFO,
            )
        } else {
            val formats = hdrSupport.supportedFormats.sorted().joinToString(", ")
            chips += Chip(
                label = "已请求保留 HDR",
                detail = "${config.codec.label} 已声明支持 $formats 编码。",
                tone = Tone.GOOD
            )
        }

        val hasHdr10Plus = hdrSupport.supportedFormats.any { it.equals("HDR10+", ignoreCase = true) }
        val hasDolbyVisionProfile10 = hdrSupport.supportedFormats.any {
            it.equals("Dolby Vision Profile 10", ignoreCase = true)
        }

        if (hasDolbyVisionProfile10) {
            chips += Chip(
                label = "杜比视界路径",
                detail = "此设备已声明支持 Profile 10。",
                tone = Tone.GOOD
            )
        }

        if (hdrSupport.hasAnyHdr && !hasHdr10Plus && !hasDolbyVisionProfile10) {
            chips += Chip(
                label = "仅支持静态 HDR",
                detail = "此编码器未声明支持动态 HDR 元数据。",
                tone = Tone.INFO
            )
            warnings += "所选编码器声明支持 HDR，但不支持 HDR10+ 或杜比视界动态元数据。"
        } else if (hasHdr10Plus) {
            chips += Chip(
                label = "HDR10+ 元数据",
                detail = "所选编码器支持动态 HDR 元数据。",
                tone = Tone.GOOD
            )
        }

        if (hdrSupport.maxWidth > 0 && hdrSupport.maxHeight > 0 &&
            (width > hdrSupport.maxWidth || height > hdrSupport.maxHeight)
        ) {
            warnings += "${config.codec.label} 声明的 HDR 编码上限为 ${hdrSupport.maxWidth}x${hdrSupport.maxHeight}；本次导出为 ${width}x${height}。"
        }

        if (hdrSupport.maxBitrate > 0 && config.videoBitrate > hdrSupport.maxBitrate) {
            warnings += "${config.codec.label} 声明的 HDR 码率上限为 ${hdrSupport.maxBitrate / 1_000_000} Mbps；本次导出请求 ${config.videoBitrate / 1_000_000} Mbps。"
        }

        chips += Chip(
            label = "渲染时检查源素材",
            detail = if (sourceSummary.hasHdrSource) {
                "导入时已检测到源素材元数据，Media3 仍会在渲染时再次验证。"
            } else {
                "只有输入轨道实际包含 HDR 时才会保留 HDR。"
            },
            tone = Tone.INFO
        )

        addProjectColorPolicyChips(projectColorPolicy, config, chips, warnings)

        return Report(chips = chips, warnings = warnings.distinct())
    }

    fun summarizeSources(tracks: List<Track>): SourceHdrSummary {
        val visualClips = tracks
            .filter { it.type == TrackType.VIDEO || it.type == TrackType.OVERLAY }
            .flatMap { it.clips }
        val clips = (visualClips.ifEmpty { tracks.flatMap { it.clips } })
            .distinctBy { it.sourceUri.toString() }

        val inspected = clips.filter { it.sourceColorMetadata.isInspected }
        val formats = inspected
            .flatMap { clip -> clip.sourceColorMetadata.hdrFormats.map { it.displayName } }
            .toSet()
        val apvSourceCount = inspected.count { clip ->
            clip.sourceColorMetadata.mimeType.isApvMimeType()
        }

        return SourceHdrSummary(
            supportedFormats = formats,
            inspectedSourceCount = inspected.size,
            totalSourceCount = clips.size,
            apvSourceCount = apvSourceCount
        )
    }

    private fun addSourceChips(
        sourceSummary: SourceHdrSummary,
        chips: MutableList<Chip>
    ) {
        if (sourceSummary.hasApvSource) {
            chips += Chip(
                label = "源素材为 APV",
                detail = "检测到 APV 专业帧内编码素材；源文件体积可能非常大。",
                tone = Tone.WARNING
            )
        }
        if (sourceSummary.hasUltraHdrGainMap) {
            chips += Chip(
                label = "Ultra HDR 源素材",
                detail = "导入时检测到 ${sourceSummary.formatList()}。",
                tone = Tone.GOOD
            )
        } else if (sourceSummary.hasHdrSource) {
            chips += Chip(
                label = "HDR 源素材",
                detail = "导入时检测到 ${sourceSummary.formatList()} 源素材。",
                tone = Tone.GOOD
            )
        } else if (sourceSummary.isFullyInspected) {
            chips += Chip(
                label = "SDR 源素材",
                detail = "导入时未发现 HDR 源素材元数据。",
                tone = Tone.INFO
            )
        } else if (sourceSummary.totalSourceCount > 0) {
            chips += Chip(
                label = "源素材 HDR 状态未知",
                detail = "部分片段创建于加入 HDR 源素材检查功能之前。",
                tone = Tone.INFO
            )
        }
    }

    private fun SourceHdrSummary.formatList(): String =
        supportedFormats.sorted().joinToString(", ").ifBlank { "HDR" }

    private fun addProjectColorPolicyChips(
        policy: ProjectColorPolicy,
        config: ExportConfig,
        chips: MutableList<Chip>,
        warnings: MutableList<String>
    ) {
        when (policy.coherence()) {
            ProjectColorPolicy.Coherence.COHERENT -> {
                chips += Chip(
                    label = "项目色彩",
                    detail = "${policy.workingColorSpace.displayName}; ${policy.displayTransform.displayName}.",
                    tone = Tone.INFO
                )
            }
            ProjectColorPolicy.Coherence.SDR_TONEMAP_NOOP -> {
                chips += Chip(
                    label = "色彩策略警告",
                    detail = "SDR 项目当前选择了 ${policy.displayTransform.displayName}。",
                    tone = Tone.WARNING
                )
                warnings += "项目工作色彩空间为 SDR，但色彩策略仍启用了色调映射。"
            }
            ProjectColorPolicy.Coherence.HDR_PASSTHROUGH -> {
                chips += Chip(
                    label = "项目 HDR 意图",
                    detail = "${policy.workingColorSpace.displayName} 已设置为直通。",
                    tone = if (config.hdr10PlusMetadata) Tone.GOOD else Tone.WARNING
                )
                if (!config.hdr10PlusMetadata) {
                    warnings += "项目色彩策略为 HDR 直通，但本次导出未开启“保留 HDR 元数据”。"
                }
            }
            ProjectColorPolicy.Coherence.HDR_TO_SDR_TONEMAP -> {
                chips += Chip(
                    label = "项目色调映射",
                    detail = "${policy.workingColorSpace.displayName} 使用 ${policy.displayTransform.displayName}。",
                    tone = if (config.hdr10PlusMetadata) Tone.WARNING else Tone.INFO
                )
                if (config.hdr10PlusMetadata) {
                    warnings += "项目色彩策略会将 HDR 映射为 SDR，但本次导出却开启了“保留 HDR 元数据”。"
                }
            }
        }
    }

    private fun String?.isApvMimeType(): Boolean =
        this?.equals(EncoderCapabilityProbe.MIME_APV, ignoreCase = true) == true

    private fun VideoCodec.canCarryHdr(): Boolean = this != VideoCodec.H264
}

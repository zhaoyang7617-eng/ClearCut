package com.novacut.editor.engine

import com.novacut.editor.model.ExportConfig
import com.novacut.editor.model.TextOverlay
import com.novacut.editor.model.Track
import com.novacut.editor.model.TrackType

/**
 * Pure planner for the Android mixed-render export path.
 *
 * The composer can describe any copy/re-encode run shape, but FFmpeg concat is
 * only safe to attempt for a narrow runtime envelope today: one visible video
 * track, no separate audio/overlay/adjustment tracks, and no export-side
 * overlays/sidecar formats that would be lost by stream-copy runs. Anything
 * outside that shape falls back to the existing whole-timeline Transformer path.
 */
object MixedRenderExportPlanner {

    fun rejectionReason(
        tracks: List<Track>,
        config: ExportConfig,
        textOverlays: List<TextOverlay> = emptyList(),
        hasImageOverlays: Boolean = false,
        hasLottieOverlays: Boolean = false,
        hasTrackedObjects: Boolean = false,
    ): String? {
        if (config.forceConstantFrameRate) return "已请求恒定帧率"
        if (!config.allowStreamCopy) return "流复制已关闭"
        if (tracks.any { it.timelineOffsetMs != 0L }) return "存在逐轨时间线偏移"
        if (config.scrubMetadata) return "已请求清除元数据"
        if (config.preserveSourceLocationMetadata || config.preserveSourceStreamMetadata) {
            return "已请求保留源元数据"
        }
        if (textOverlays.isNotEmpty()) return "存在文字叠加层"
        if (hasImageOverlays) return "存在图片叠加层"
        if (hasLottieOverlays) return "存在 Lottie 叠加层"
        if (hasTrackedObjects) return "存在跟踪对象叠加层"
        if (config.chapters.isNotEmpty()) return "已请求章节标记"
        if (config.subtitleFormat != null) return "已请求字幕侧车文件"
        if (config.transparentBackground) return "已请求透明背景导出"
        if (config.exportAsGif) return "已请求 GIF 导出"
        if (config.captureFrameOnly) return "已请求画面捕获"
        if (config.exportAsContactSheet) return "已请求联系表导出"
        if (config.exportAudioOnly) return "已请求仅音频导出"
        if (config.exportStemsOnly) return "已请求音频分轨导出"
        if (config.watermark != null) return "已请求水印"
        if (config.targetSizeBytes != null) return "已请求目标文件大小码率"

        val visibleVideoTracks = tracks.filter {
            it.type == TrackType.VIDEO && it.isVisible && it.clips.any { clip -> clip.durationMs > 0L }
        }
        if (visibleVideoTracks.size != 1) return "混合渲染需要且仅需要一条可见视频轨道"

        val hasVisibleOverlayTracks = tracks.any {
            it.type == TrackType.OVERLAY && it.isVisible && it.clips.any { clip -> clip.durationMs > 0L }
        }
        if (hasVisibleOverlayTracks) return "存在叠加轨道"

        val hasVisibleAdjustmentTracks = tracks.any {
            it.type == TrackType.ADJUSTMENT && it.isVisible && it.clips.any { clip -> clip.durationMs > 0L }
        }
        if (hasVisibleAdjustmentTracks) return "存在调整轨道"

        val hasVisibleAudioTracks = tracks.any {
            it.type == TrackType.AUDIO && it.isVisible && it.clips.any { clip -> clip.durationMs > 0L }
        }
        if (hasVisibleAudioTracks) return "存在独立音频轨道"

        return null
    }

    fun buildPlan(
        tracks: List<Track>,
        config: ExportConfig,
        finalOutputName: String,
        projectStem: String,
        textOverlays: List<TextOverlay> = emptyList(),
        hasImageOverlays: Boolean = false,
        hasLottieOverlays: Boolean = false,
        hasTrackedObjects: Boolean = false,
    ): MixedRenderComposer.CompositionPlan? {
        val rejection = rejectionReason(
            tracks = tracks,
            config = config,
            textOverlays = textOverlays,
            hasImageOverlays = hasImageOverlays,
            hasLottieOverlays = hasLottieOverlays,
            hasTrackedObjects = hasTrackedObjects,
        )
        if (rejection != null) return null

        val visualTracks = tracks.filter {
            it.type == TrackType.VIDEO && it.isVisible && it.clips.any { clip -> clip.durationMs > 0L }
        }
        val segments = SmartRenderEngine.analyzeTimeline(
            tracks = visualTracks,
            config = config,
            textOverlays = textOverlays,
        )
        val runs = SmartRenderEngine.planRuns(segments)
        val finalExtension = finalOutputName
            .substringAfterLast('.', missingDelimiterValue = "")
            .ifBlank { "mp4" }

        return MixedRenderComposer.plan(
            runs = runs,
            projectStem = projectStem,
            finalOutputName = finalOutputName,
            finalExtension = finalExtension,
        ).takeIf { it.benefit == MixedRenderComposer.Benefit.Mixed && it.needsConcat }
    }

    fun sliceTracksForRun(
        tracks: List<Track>,
        run: SmartRenderEngine.RenderRun,
        normaliseTimelineStart: Boolean,
    ): List<Track> {
        val runClipIds = run.clipIds.toSet()
        return tracks.mapNotNull { track ->
            val clips = track.clips
                .filter { it.id in runClipIds && it.durationMs > 0L }
                .sortedBy { it.timelineStartMs }
                .map { clip ->
                    if (normaliseTimelineStart) {
                        clip.copy(timelineStartMs = (clip.timelineStartMs - run.startMs).coerceAtLeast(0L))
                    } else {
                        clip
                    }
                }
            if (clips.isEmpty()) null else track.copy(clips = clips)
        }
    }
}

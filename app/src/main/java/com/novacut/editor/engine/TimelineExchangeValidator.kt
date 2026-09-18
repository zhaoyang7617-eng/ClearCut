package com.novacut.editor.engine

import android.net.Uri
import com.novacut.editor.engine.TimelineExchangeEngine.TimelineExchangeFormat
import com.novacut.editor.model.BlendMode
import com.novacut.editor.model.Clip
import com.novacut.editor.model.TextOverlay
import com.novacut.editor.model.Track
import com.novacut.editor.model.TrackType
import com.novacut.editor.model.TransitionType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pre-flight validator for timeline import/export against external NLE formats.
 *
 * Runs *before* the export writer is invoked or *after* the import parser
 * returns its candidate state. Produces a single [Report] with categorised
 * issues so the UI can surface a real "what will/did change" sheet instead of
 * silently dropping data — historically the export path lost transitions, blend
 * modes, and effect chains without telling the user.
 *
 * The validator is intentionally pure: it does not touch the filesystem, never
 * mutates the input, and depends only on data classes from [com.novacut.editor.model].
 * That makes it safe to call from a worker thread or from inside an export
 * pipeline that already holds locks on the shared player.
 */
@Singleton
class TimelineExchangeValidator @Inject constructor() {

    enum class Severity {
        /** Operation cannot proceed as-is. */
        ERROR,

        /** Operation will proceed but data will be lost or substituted. */
        WARNING,

        /** Operation will proceed; informational only. */
        INFO
    }

    enum class Direction { EXPORT, IMPORT }

    /**
     * One actionable issue. [path] is a human-readable location ("Track 2 → Clip 5")
     * so the UI can drop it straight into a sheet without further interpretation.
     */
    data class Issue(
        val severity: Severity,
        val path: String,
        val message: String,
        val suggestedFix: String? = null
    )

    data class Report(
        val format: TimelineExchangeFormat,
        val direction: Direction,
        val issues: List<Issue>
    ) {
        val errors: List<Issue> = issues.filter { it.severity == Severity.ERROR }
        val warnings: List<Issue> = issues.filter { it.severity == Severity.WARNING }
        val infos: List<Issue> = issues.filter { it.severity == Severity.INFO }
        val canProceed: Boolean = errors.isEmpty()
        val summary: String
            get() = when {
                errors.isNotEmpty() -> "${errors.size} 个阻断问题，${warnings.size} 个有损问题"
                warnings.isNotEmpty() -> "${warnings.size} 个有损问题"
                infos.isNotEmpty() -> "${infos.size} 条说明"
                else -> "没有问题"
            }
    }

    /**
     * Validate a snapshot before exporting to [format].
     */
    fun validateExport(
        format: TimelineExchangeFormat,
        tracks: List<Track>,
        textOverlays: List<TextOverlay>,
        frameRate: Int = 30
    ): Report {
        val issues = mutableListOf<Issue>()

        if (!format.canExport) {
            issues += Issue(
                Severity.ERROR,
                path = format.displayName,
                message = "此格式暂不支持导出。",
                suggestedFix = "Pick a supported format (OTIO, FCPXML, EDL)."
            )
            return Report(format, Direction.EXPORT, issues)
        }

        if (frameRate <= 0) {
            issues += Issue(
                Severity.WARNING,
                path = "Project",
                message = "帧率“$frameRate”无效；将使用默认 30 fps。",
                suggestedFix = "Set the project frame rate before export."
            )
        }

        val videoTrackCount = tracks.count { it.type == TrackType.VIDEO }
        val overlayTrackCount = tracks.count { it.type == TrackType.OVERLAY }
        val audioTrackCount = tracks.count { it.type == TrackType.AUDIO }
        val adjustmentTrackCount = tracks.count { it.type == TrackType.ADJUSTMENT }

        // EDL is single-track-per-file by spec. Warn loudly so the user knows
        // tracks 2+ won't make it.
        if (format == TimelineExchangeFormat.EDL_CMX3600) {
            if (videoTrackCount + overlayTrackCount > 1) {
                issues += Issue(
                    Severity.WARNING,
                    path = "Tracks",
                    message = "EDL CMX 3600 仅支持单轨。只会导出第一条视频轨道。",
                    suggestedFix = "Use OTIO or FCPXML for multi-track projects."
                )
            }
            if (audioTrackCount > 0) {
                issues += Issue(
                    Severity.INFO,
                    path = "Audio",
                    message = "EDL 可以导出音频行，但不会保留逐片段音频效果。",
                )
            }
        }

        if (adjustmentTrackCount > 0) {
            issues += Issue(
                Severity.WARNING,
                path = "Adjustment layers",
                message = "$adjustmentTrackCount 条调整轨道在 $format 中没有对应结构，将被丢弃。",
                suggestedFix = "Bake adjustment-layer effects onto each affected clip before export."
            )
        }

        tracks.forEachIndexed { trackIdx, track ->
            val trackPath = "Track ${trackIdx + 1} (${track.type.name.lowercase()})"

            if (track.blendMode != BlendMode.NORMAL && format != TimelineExchangeFormat.OTIO) {
                issues += Issue(
                    Severity.WARNING,
                    path = trackPath,
                    message = "轨道混合模式“${track.blendMode.name}”在 $format 中没有对应项。",
                    suggestedFix = "Pre-composite the blend or expect 'normal' on import."
                )
            }

            if (track.audioEffects.isNotEmpty() && format != TimelineExchangeFormat.OTIO) {
                issues += Issue(
                    Severity.WARNING,
                    path = trackPath,
                    message = "$format 不会保留 ${track.audioEffects.size} 个轨道级音频效果。",
                )
            }

            track.clips.forEachIndexed { clipIdx, clip ->
                val clipPath = "$trackPath → Clip ${clipIdx + 1}"
                validateClipForExport(format, clip, clipPath, issues)
            }
        }

        textOverlays.forEachIndexed { idx, overlay ->
            val path = "Text overlay ${idx + 1}"
            if (overlay.endTimeMs <= overlay.startTimeMs) {
                issues += Issue(
                    Severity.ERROR,
                    path = path,
                    message = "叠加层结束时间（${overlay.endTimeMs} ms）不晚于开始时间（${overlay.startTimeMs} ms）。",
                    suggestedFix = "Drag the overlay to a positive duration before exporting."
                )
            }
            if (format == TimelineExchangeFormat.EDL_CMX3600 && overlay.text.isNotBlank()) {
                issues += Issue(
                    Severity.WARNING,
                    path = path,
                    message = "EDL 不支持文字轨道；叠加文字“${overlay.text.take(40)}”将被丢弃。",
                )
            } else if (format != TimelineExchangeFormat.OTIO && format != TimelineExchangeFormat.FCPXML) {
                issues += Issue(
                    Severity.WARNING,
                    path = path,
                    message = "除 OTIO/FCPXML 外，文字叠加层样式将丢失。",
                )
            }
        }

        return Report(format, Direction.EXPORT, issues)
    }

    /**
     * Validate the candidate result of an import before committing it to the
     * editor. [unresolvedMediaUris] is the list returned by the importer for
     * media that could not be located on disk.
     */
    fun validateImport(
        format: TimelineExchangeFormat,
        tracks: List<Track>,
        textOverlays: List<TextOverlay>,
        unresolvedMediaUris: List<String> = emptyList(),
        droppedEffects: Int = 0,
        importerWarnings: List<String> = emptyList()
    ): Report {
        val issues = mutableListOf<Issue>()

        if (!format.canImport) {
            issues += Issue(
                Severity.ERROR,
                path = format.displayName,
                message = "此格式不支持导入。",
                suggestedFix = "Re-export the timeline as OTIO, FCPXML, or EDL."
            )
            return Report(format, Direction.IMPORT, issues)
        }

        importerWarnings.forEach { msg ->
            issues += Issue(Severity.WARNING, path = "Parser", message = msg)
        }

        if (droppedEffects > 0) {
            issues += Issue(
                Severity.WARNING,
                path = "Effects",
                message = "$droppedEffects 个效果在 ClearCut 中没有对应项，已丢弃。",
                suggestedFix = "Re-apply effects manually after import."
            )
        }

        unresolvedMediaUris.forEach { uri ->
            issues += Issue(
                Severity.ERROR,
                path = "Media: $uri",
                message = "找不到源媒体文件。",
                suggestedFix = "Use 'Relink media' to point at the file's new location."
            )
        }

        if (tracks.isEmpty() && textOverlays.isEmpty()) {
            issues += Issue(
                Severity.ERROR,
                path = "Timeline",
                message = "导入的时间线不包含轨道或叠加层。",
                suggestedFix = "Verify the source file isn't an empty project."
            )
        }

        // Frame-rate drift: clip trims that don't snap to a sensible frame
        // boundary tend to mean the source NLE used a non-standard rate
        // (23.976 vs 24, 29.97 vs 30) and the importer assumed the wrong one.
        tracks.forEachIndexed { trackIdx, track ->
            val trackPath = "Track ${trackIdx + 1}"
            track.clips.forEachIndexed { clipIdx, clip ->
                if (clip.trimEndMs <= clip.trimStartMs) {
                    issues += Issue(
                        Severity.ERROR,
                        path = "$trackPath → Clip ${clipIdx + 1}",
                        message = "片段裁剪范围为空（${clip.trimStartMs}..${clip.trimEndMs} ms）。",
                        suggestedFix = "Re-export from the source NLE; this clip is unrecoverable."
                    )
                }
                if (clip.sourceUri == Uri.EMPTY || clip.sourceUri.toString().isBlank()) {
                    issues += Issue(
                        Severity.ERROR,
                        path = "$trackPath → Clip ${clipIdx + 1}",
                        message = "片段没有源 URI。",
                        suggestedFix = "Use 'Relink media' to point at the source file."
                    )
                }
                if (clip.sourceUri != Uri.EMPTY &&
                    clip.sourceUri.scheme?.lowercase() !in PROBEABLE_URI_SCHEMES
                ) {
                    issues += Issue(
                        Severity.ERROR,
                        path = "$trackPath → Clip ${clipIdx + 1}",
                        message = "无法验证媒体 URI 类型“${clip.sourceUri.scheme ?: "<无>"}”。",
                        suggestedFix = "Use 'Relink media' to choose a content:// or file:// source."
                    )
                }
            }
        }

        return Report(format, Direction.IMPORT, issues)
    }

    private fun validateClipForExport(
        format: TimelineExchangeFormat,
        clip: Clip,
        clipPath: String,
        issues: MutableList<Issue>
    ) {
        if (clip.sourceUri == Uri.EMPTY || clip.sourceUri.toString().isBlank()) {
            issues += Issue(
                Severity.ERROR,
                path = clipPath,
                message = "片段没有源 URI；导入器将无法找到媒体。",
                suggestedFix = "Relink the clip to a file before exporting."
            )
        }

        if (clip.trimEndMs <= clip.trimStartMs) {
            issues += Issue(
                Severity.ERROR,
                path = clipPath,
                message = "片段裁剪范围为空（${clip.trimStartMs}..${clip.trimEndMs} ms）。",
                suggestedFix = "Drag the clip handles to give it a positive duration."
            )
        }

        if (clip.isCompound && format != TimelineExchangeFormat.OTIO) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "导出时复合片段会展平为单个片段。",
                suggestedFix = "Open the compound to bake child timing if precision matters."
            )
        }

        if (clip.isReversed && format != TimelineExchangeFormat.OTIO) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "倒放仅用于预览；导出的片段会正向播放。",
                suggestedFix = "Pre-render the reversed clip with FFmpegX once it ships."
            )
        }

        if (clip.speedCurve != null && clip.speedCurve.points.size >= 2) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "导出时曲线变速会被展平为恒定时间重映射。",
                suggestedFix = "Bake the ramp into a rendered clip if timing matters."
            )
        }

        if (clip.blendMode != BlendMode.NORMAL && format != TimelineExchangeFormat.OTIO) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "片段混合模式“${clip.blendMode.name}”在 $format 中没有对应项。",
            )
        }

        if (clip.masks.isNotEmpty()) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "${format.displayName} 导出不会保留 ${clip.masks.size} 个蒙版。",
            )
        }

        if (clip.colorGrade != null) {
            issues += Issue(
                Severity.WARNING,
                path = clipPath,
                message = "$format 无法表示当前调色，将被丢弃。",
                suggestedFix = "Export an accompanying .cube LUT alongside the timeline."
            )
        }

        if (clip.effects.isNotEmpty() && format != TimelineExchangeFormat.OTIO) {
            issues += Issue(
                Severity.INFO,
                path = clipPath,
                message = "${clip.effects.size} 个效果会作为命名标记导出；接收端剪辑软件需要手动重新应用。",
            )
        }

        if (format == TimelineExchangeFormat.EDL_CMX3600) {
            listOfNotNull(clip.headTransition, clip.tailTransition).forEach { transition ->
                if (transition.type !in EDL_SUPPORTED_TRANSITIONS) {
                    issues += Issue(
                        Severity.WARNING,
                        path = clipPath,
                        message = "EDL 只支持硬切和叠化；“${transition.type.name}”将降级为叠化。",
                    )
                }
            }
        }
    }

    companion object {
        private val EDL_SUPPORTED_TRANSITIONS = setOf(
            TransitionType.DISSOLVE,
            TransitionType.FADE_BLACK,
            TransitionType.FADE_WHITE
        )
        private val PROBEABLE_URI_SCHEMES = setOf("content", "file", "asset", "http", "https")
    }
}

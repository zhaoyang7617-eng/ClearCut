package com.novacut.editor.engine

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class IncomingDocumentImportStatus {
    READY,
    BLOCKED,
    INVALID,
    IMPORTED,
}

data class IncomingDocumentImportPreview(
    val item: IncomingDocumentItem,
    val status: IncomingDocumentImportStatus,
    val title: String,
    val body: String,
    val details: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val canImportNow: Boolean = false,
    val captionImport: CaptionImportEngine.Preview? = null,
)

@Singleton
class IncomingDocumentImportRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val templateManager: TemplateManager,
    private val effectShareEngine: EffectShareEngine,
    private val timelineImportEngine: TimelineImportEngine,
    private val stylePackManager: StylePackManager,
    private val stabilizationProfileManager: StabilizationProfileManager,
) {
    suspend fun preview(item: IncomingDocumentItem): IncomingDocumentImportPreview = withContext(Dispatchers.IO) {
        val readability = validateReadable(item)
        if (readability != null) return@withContext readability

        when (item.kind) {
            IncomingDocumentKind.TEMPLATE -> previewTemplate(item)
            IncomingDocumentKind.EFFECT_PACK -> previewEffectPack(item)
            IncomingDocumentKind.STYLE_PACK -> previewStylePack(item)
            IncomingDocumentKind.LUT_CUBE -> previewLut(item) { LutEngine.parseCube(it) }
            IncomingDocumentKind.LUT_3DL -> previewLut(item) { LutEngine.parse3dl(it) }
            IncomingDocumentKind.OPENFX_DESCRIPTOR -> previewOpenFxDescriptor(item)
            IncomingDocumentKind.STABILIZATION_PROFILE -> previewStabilizationProfile(item)
            IncomingDocumentKind.PROJECT_ARCHIVE -> previewProjectArchive(item)
            IncomingDocumentKind.TIMELINE_OTIO,
            IncomingDocumentKind.TIMELINE_FCPXML,
            IncomingDocumentKind.TIMELINE_EDL,
            IncomingDocumentKind.EDIT_DECISION_JSON -> previewTimelineImport(item)
            IncomingDocumentKind.CAPTION_SRT,
            IncomingDocumentKind.CAPTION_WEBVTT -> previewCaptionImport(item)
        }
    }

    /**
     * Commit a previewed document. [preview] is read-only, so this is the only
     * entry point that writes anything, and it dispatches on the document's own
     * kind instead of assuming every confirmation is a template.
     *
     * Kinds whose preview reports `canImportNow = false` have no commit path and
     * are rejected here rather than silently routed to the wrong importer.
     */
    suspend fun commit(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        return when (item.kind) {
            IncomingDocumentKind.TEMPLATE -> importTemplate(item)
            IncomingDocumentKind.STYLE_PACK -> importStylePack(item)
            IncomingDocumentKind.STABILIZATION_PROFILE -> importStabilizationProfile(item)
            IncomingDocumentKind.EFFECT_PACK,
            IncomingDocumentKind.LUT_CUBE,
            IncomingDocumentKind.LUT_3DL,
            IncomingDocumentKind.OPENFX_DESCRIPTOR,
            IncomingDocumentKind.PROJECT_ARCHIVE,
            IncomingDocumentKind.TIMELINE_OTIO,
            IncomingDocumentKind.TIMELINE_FCPXML,
            IncomingDocumentKind.TIMELINE_EDL,
            IncomingDocumentKind.EDIT_DECISION_JSON,
            IncomingDocumentKind.CAPTION_SRT,
            IncomingDocumentKind.CAPTION_WEBVTT -> invalid(
                item = item,
                body = "${item.kind.displayName} 文件无法直接从项目页安装。${item.kind.targetAction}。",
            )
        }
    }

    suspend fun importTemplate(item: IncomingDocumentItem): IncomingDocumentImportPreview = withContext(Dispatchers.IO) {
        if (item.kind != IncomingDocumentKind.TEMPLATE) {
            return@withContext invalid(item, "此预览仅支持导入 ClearCut 模板文件。")
        }
        val readability = validateReadable(item)
        if (readability != null) return@withContext readability
        val result = templateManager.importTemplateFromUriDetailed(item.uri)
        val template = result.template
        if (template != null) {
            IncomingDocumentImportPreview(
                item = item,
                status = IncomingDocumentImportStatus.IMPORTED,
                title = "模板已导入",
                body = "已将“${template.name}”保存到模板库。",
                details = listOf(
                    "文件类型：${item.kind.displayName}",
                    "目标操作：${item.kind.targetAction}",
                    "轨道：${template.trackTypes.joinToString { it.name.lowercase() }}",
                    "文字叠加层：${template.textOverlayCount}",
                ),
                warnings = result.compatibilityReport?.issues.orEmpty().map { it.message } +
                    result.restoreReport.takeIf { it.isPartial }
                        ?.let { listOf("模板文档仅部分恢复：${it.summary()}。") }
                        .orEmpty(),
                canImportNow = false,
            )
        } else {
            invalid(
                item = item,
                body = templateImportFailureMessage(result.failure),
                warnings = result.compatibilityReport?.issues.orEmpty().map { it.message },
            )
        }
    }

    suspend fun importStylePack(item: IncomingDocumentItem): IncomingDocumentImportPreview = withContext(Dispatchers.IO) {
        if (item.kind != IncomingDocumentKind.STYLE_PACK) {
            return@withContext invalid(item, "只有 .ncstyle 文件可作为样式包导入。")
        }
        val readability = validateReadable(item)
        if (readability != null) return@withContext readability
        val result = stylePackManager.importFromUri(item.uri)
        val pack = result.pack
        if (pack != null) {
            IncomingDocumentImportPreview(
                item = item,
                status = IncomingDocumentImportStatus.IMPORTED,
                title = "样式包已安装",
                body = "“${pack.name}”已向字幕样式库添加 ${pack.styles.size} 个样式。",
                details = listOf(
                    "文件类型：${item.kind.displayName}",
                    "目标操作：${item.kind.targetAction}",
                    "样式包：${pack.name} v${pack.version}",
                    "作者：${pack.author.ifBlank { "未知" }}",
                    "样式数：${pack.styles.size}",
                    "架构版本：v${pack.schemaVersion}",
                    "内容哈希：${pack.contentHash.ifBlank { "未记录" }}",
                    "来源：${pack.provenanceSource ?: "未指定"}",
                    "原因代码：${result.reasonCode}",
                    "可回滚：${stylePackManager.canRollback(pack.id)}",
                ),
                warnings = result.warnings,
                canImportNow = false,
            )
        } else {
            invalid(
                item = item,
                body = stylePackFailureMessage(result.failure),
                warnings = result.warnings,
                reasonCode = result.reasonCode,
            )
        }
    }

    private fun previewStylePack(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val json = readText(item) ?: return invalid(item, "ClearCut 无法读取此样式包文件。")
        // Validation only: preview must not install. Installing happens in commit().
        val result = stylePackManager.validateFromJson(json)
        val pack = result.pack
        if (pack == null) {
            return invalid(item, stylePackFailureMessage(result.failure), result.warnings, result.reasonCode)
        }
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "样式包已准备好安装",
            body = "“${pack.name}”包含 ${pack.styles.size} 个字幕/文字样式。",
            details = baseDetails(item) + listOf(
                "样式包：${pack.name} v${pack.version}",
                "作者：${pack.author.ifBlank { "未知" }}",
                "许可证：${pack.license.ifBlank { "未指定" }}",
                "样式数：${pack.styles.size}",
                "架构版本：v${pack.schemaVersion}",
                "内容哈希：${pack.contentHash.ifBlank { "安装时计算" }}",
                "来源：${pack.provenanceSource ?: "未指定；安装时记录为本地导入"}",
                "原因代码：${result.reasonCode}",
                "可回滚：${stylePackManager.canRollback(pack.id)}",
            ),
            warnings = result.warnings + "预览期间未安装任何内容；请选择“导入”完成安装。",
            canImportNow = true,
        )
    }

    private fun previewTemplate(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "模板已准备好检查",
            body = "保存到模板库前，ClearCut 会先运行现有的模板兼容性检查。",
            details = baseDetails(item),
            warnings = emptyList(),
            canImportNow = true,
        )
    }

    private suspend fun previewEffectPack(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val validation = effectShareEngine.validateEffects(item.uri)
        val imported = validation.imported
            ?: return invalid(
                item = item,
                body = effectPackFailureMessage(validation.failure),
                warnings = validation.warnings,
                reasonCode = validation.reasonCode,
            )
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "效果包已验证",
            body = "请打开一个编辑项目，选择片段，然后从效果库导入并应用此效果包。",
            details = baseDetails(item) + listOf(
                "效果包：${imported.name}",
                "视频效果：${imported.effects.size}",
                "音频效果：${imported.audioEffects.size}",
                "调色：${if (imported.colorGrade != null) "已包含" else "未包含"}",
                "LUT: ${when {
                    imported.embeddedLut != null -> "已嵌入"
                    imported.colorGrade?.lutPath != null -> "本地引用"
                    else -> "未包含"
                }}",
                "Schema: v${validation.schemaVersion}",
                "内容哈希：${validation.contentHash ?: "旧版效果包将在导出时计算"}",
                "来源：${validation.provenanceSource ?: "未指定"}",
                "原因代码：${validation.reasonCode}",
            ),
            warnings = validation.warnings + "项目页未修改任何片段。",
            canImportNow = false,
        )
    }

    private suspend fun previewStabilizationProfile(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val result = stabilizationProfileManager.validateUri(item.uri)
        val profile = result.profile
            ?: return invalid(
                item = item,
                body = stabilizationProfileFailureMessage(result.failure),
                warnings = result.warnings,
                reasonCode = result.reasonCode,
            )
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "稳定配置已就绪",
            body = "${profile.name} 包含可复用的离线镜头、运动、裁剪和同步参数。",
            details = baseDetails(item) + listOf(
                "配置：${profile.name}",
                "镜头：${profile.lens.name}",
                "运动算法：${profile.motion.algorithm}",
                "裁剪缩放：${"%.2f".format(profile.cropScale)}",
                "同步偏移：${profile.syncOffsetMs} ms",
                "Schema: v${result.schemaVersion}",
                "内容哈希：${result.contentHash ?: "未记录"}",
                "来源：${result.provenanceSource ?: "未指定"}",
                "原因代码：${result.reasonCode}",
            ),
            warnings = result.warnings + "预览期间未启用任何配置；请选择“导入”将其设为当前离线配置。",
            canImportNow = true,
        )
    }

    private suspend fun importStabilizationProfile(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val result = stabilizationProfileManager.importFromUri(item.uri)
        val profile = result.profile
            ?: return invalid(
                item = item,
                body = stabilizationProfileFailureMessage(result.failure),
                warnings = result.warnings,
                reasonCode = result.reasonCode,
            )
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.IMPORTED,
            title = "稳定配置已启用",
            body = "下次离线稳定分析将使用 ${profile.name}。",
            details = baseDetails(item) + listOf(
                "配置：${profile.name}",
                "镜头：${profile.lens.name}",
                "运动算法：${profile.motion.algorithm}",
                "裁剪缩放：${"%.2f".format(profile.cropScale)}",
                "同步偏移：${profile.syncOffsetMs} ms",
                "原因代码：${result.reasonCode}",
            ),
            warnings = result.warnings,
            canImportNow = false,
        )
    }

    private fun previewLut(
        item: IncomingDocumentItem,
        parse: (File) -> LutEngine.Lut3D?,
    ): IncomingDocumentImportPreview {
        val tempFile = copyToPreviewFile(item) ?: return invalid(item, "ClearCut 无法复制此 LUT 进行验证。")
        return try {
            val lut = parse(tempFile)
                ?: return invalid(item, "此 LUT 格式无效，或使用了不支持的表结构。")
            IncomingDocumentImportPreview(
                item = item,
                status = IncomingDocumentImportStatus.READY,
                title = "LUT 已验证",
                body = "请在编辑项目中打开“调色”，将此 LUT 应用到所选片段。",
                details = baseDetails(item) + listOf(
                    "LUT 尺寸：${lut.size}x${lut.size}x${lut.size}",
                    "条目数：${lut.data.size / 3}",
                ),
                warnings = listOf("项目页未修改任何项目调色。"),
                canImportNow = false,
            )
        } finally {
            tempFile.delete()
        }
    }

    private fun previewOpenFxDescriptor(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val json = readText(item) ?: return invalid(item, "ClearCut 无法读取此描述文件。")
        val descriptor = OpenFxDescriptor.fromJson(json)
            ?: return invalid(item, "此 .ncfxd 文件不符合 ClearCut 的 OpenFX 描述文件架构。")
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "OpenFX 描述文件已验证",
            body = "ClearCut 可将此元数据与效果包一起保留，用于后续时间线交换。",
            details = baseDetails(item) + listOf(
                "ClearCut 效果：${descriptor.novaCutEffectId}",
                "OpenFX 效果：${descriptor.openfxId}",
                "参数数：${descriptor.parameters.size}",
            ),
            warnings = listOf("描述文件仅包含元数据；未安装任何运行时效果。"),
            canImportNow = false,
        )
    }

    private suspend fun previewProjectArchive(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val preview = ProjectArchive.previewArchive(context, item.uri)
        val report = preview.report
        if (!preview.valid) {
            return invalid(
                item = item,
                body = preview.errorMessage ?: "无法验证此归档。",
                warnings = report.warnings,
            )
        }
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "项目归档已验证",
            body = "请打开编辑项目，并使用“归档传输”导入来明确恢复此归档。",
            details = baseDetails(item) + listOf(
                "归档报告：${report.summary}",
                "声明的已打包媒体：${preview.packagedMedia}/${report.mediaTotal}",
                "架构版本：${report.schemaVersion}",
            ),
            warnings = report.warnings + listOf(
                "预览仅读取受限的项目元数据；未提取归档中的媒体。"
            ),
            canImportNow = false,
        )
    }

    private suspend fun previewTimelineImport(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val format = when (item.kind) {
            IncomingDocumentKind.TIMELINE_OTIO -> TimelineImportEngine.Format.OTIO
            IncomingDocumentKind.TIMELINE_FCPXML -> TimelineImportEngine.Format.FCPXML
            IncomingDocumentKind.TIMELINE_EDL -> TimelineImportEngine.Format.EDL
            IncomingDocumentKind.EDIT_DECISION_JSON -> TimelineImportEngine.Format.EDIT_DECISION_JSON
            else -> null
        } ?: return invalid(item, "未知的时间线交换格式。")
        val fidelity = timelineImportEngine.roundTripFidelity(format)
        val result = timelineImportEngine.import(item.uri, format = format)
        val report = result.fidelityReport
        val reportIssues = report?.issues.orEmpty().map { issue ->
            "${issue.severity.name.lowercase().replaceFirstChar { it.uppercase() }}: ${issue.message}"
        }
        val schemaTooNew = result.exchangeResult?.schemaTooNew == true
        val status = if (!schemaTooNew && report?.canProceed == true) {
            IncomingDocumentImportStatus.READY
        } else {
            IncomingDocumentImportStatus.BLOCKED
        }
        val captionCount = result.exchangeResult?.tracks.orEmpty()
            .sumOf { track -> track.clips.sumOf { clip -> clip.captions.size } }
        return IncomingDocumentImportPreview(
            item = item,
            status = status,
            title = if (schemaTooNew) {
                "${format.displayName} 需要更新版本的 ClearCut"
            } else if (report?.canProceed == true) {
                "${format.displayName} 导入预览已就绪"
            } else {
                "${format.displayName} 导入需要检查"
            },
            body = if (schemaTooNew) {
                "该剪辑决策文件在创建任何时间线状态前已被拒绝。请使用兼容的 ClearCut 架构版本重新导出。"
            } else {
                "ClearCut 已解析时间线并准备好原子化提交，尚未修改项目状态。请打开编辑项目，在接受报告和必要的重新链接后再应用。"
            },
            details = baseDetails(item) + listOf(
                "预计保真度：${fidelity.displayName}",
                fidelity.warningCopy,
                "已解析轨道：${result.exchangeResult?.tracks?.size ?: 0}",
                "已解析片段：${result.exchangeResult?.tracks.orEmpty().sumOf { it.clips.size }}",
                "时间线标记：${result.exchangeResult?.timelineMarkers?.size ?: 0}",
                "字幕：$captionCount",
                "文字叠加层：${result.exchangeResult?.textOverlays?.size ?: 0}",
                result.exchangeResult?.schemaVersion?.let { "架构版本：v$it" }
                    ?: "架构版本：不可用",
                "保真度报告：${report?.summary ?: "不可用"}",
                if (result.unresolvedMediaUris.isEmpty()) {
                    "未解析媒体：无"
                } else {
                    "未解析媒体：${result.unresolvedMediaUris.size} 个 — 提交前请先使用“重新链接媒体”"
                },
            ),
            warnings = (result.warnings + reportIssues).distinct(),
            canImportNow = false,
        )
    }

    private fun previewCaptionImport(item: IncomingDocumentItem): IncomingDocumentImportPreview {
        val format = CaptionImportEngine.formatFor(item.kind)
            ?: return invalid(item, "不支持的字幕格式。")
        val bytes = readBytes(item)
            ?: return invalid(
                item,
                "ClearCut 无法在导入大小限制内读取此字幕文件。",
            )
        val analysis = CaptionImportEngine.analyze(bytes, format)
        val details = baseDetails(item) + captionDetails(analysis)
        val failure = analysis.failure
        if (failure != null) {
            return IncomingDocumentImportPreview(
                item = item,
                status = IncomingDocumentImportStatus.INVALID,
                title = "字幕导入已阻止",
                body = captionFailureMessage(failure),
                details = details,
                warnings = analysis.warnings + "未修改任何项目数据。",
                canImportNow = false,
                captionImport = analysis,
            )
        }
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.READY,
            title = "字幕预览已就绪",
            body = "预览不会修改项目。请在所选片段上打开字幕编辑器，将此次导入作为一个可撤销操作应用。",
            details = details + "时间映射：源时间戳会减去所选片段在时间线中的起点，并裁切到该片段范围内。",
            warnings = analysis.warnings + "预览期间未修改任何项目数据。",
            canImportNow = false,
            captionImport = analysis,
        )
    }

    private fun validateReadable(item: IncomingDocumentItem): IncomingDocumentImportPreview? {
        if (item.uri.scheme != "content") {
            return invalid(item, "仅接受 content:// 文档授权。")
        }
        val knownSize = item.sizeBytes
        if (knownSize != null && knownSize > item.kind.maxBytes) {
            return invalid(item, "此文件超过 ClearCut 对 ${item.kind.displayName} 的导入大小限制。")
        }
        val readable = runCatching {
            context.contentResolver.openAssetFileDescriptor(item.uri, "r")?.use { descriptor ->
                descriptor.length != 0L
            } ?: false
        }.getOrDefault(false)
        return if (readable) null else invalid(item, "ClearCut 无法读取此文档授权。")
    }

    private fun copyToPreviewFile(item: IncomingDocumentItem): File? {
        val dir = File(context.cacheDir, "document-import-preview").apply { mkdirs() }
        val extension = PluginRegistry.kindForFileName(item.displayName)?.fileExtension
            ?: ".bin"
        val file = File(dir, "preview-${System.currentTimeMillis()}$extension")
        return try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                file.outputStream().use { output ->
                    copyWithLimit(input, output, item.kind.maxBytes)
                }
            } ?: return null
            file.takeIf { it.length() > 0L }
        } catch (_: Exception) {
            file.delete()
            null
        }
    }

    private fun readText(item: IncomingDocumentItem): String? {
        return try {
            context.contentResolver.openInputStream(item.uri)?.use { stream ->
                readUtf8WithByteLimit(stream, item.kind.maxBytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readBytes(item: IncomingDocumentItem): ByteArray? {
        return try {
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_READ_BUFFER_BYTES)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    if (output.size().toLong() > item.kind.maxBytes) return null
                }
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun captionDetails(analysis: CaptionImportEngine.Preview): List<String> {
        val encoding = analysis.encoding?.displayName ?: "未知"
        val confidence = "%.0f%%".format(java.util.Locale.US, analysis.languageConfidence * 100f)
        return listOf(
            "格式：${analysis.format.displayName}",
            "编码：$encoding",
            "字幕条数：${analysis.cues.size}",
            "时长：${formatDuration(analysis.durationMs)}",
            "语言判断：${analysis.language}（置信度 $confidence）",
            "重叠：${analysis.overlapCount}",
            "无效字幕：${analysis.invalidCueCount}",
        )
    }

    private fun captionFailureMessage(failure: CaptionImportEngine.Failure): String {
        return when (failure) {
            CaptionImportEngine.Failure.OVERSIZED -> "字幕文件超过 ClearCut 的导入大小限制。"
            CaptionImportEngine.Failure.EMPTY -> "字幕文件为空。"
            CaptionImportEngine.Failure.BINARY -> "此文件包含二进制内容，并非文本字幕文件。"
            CaptionImportEngine.Failure.UNSUPPORTED_ENCODING -> "字幕编码不受支持或格式损坏。请使用 UTF-8 或 UTF-16 文本。"
            CaptionImportEngine.Failure.INVALID_HEADER -> "WebVTT 文件必须以有效的 WEBVTT 头开始。"
            CaptionImportEngine.Failure.INVALID_CUES -> "一个或多个字幕条目无效，因此未导入该文件。"
            CaptionImportEngine.Failure.EXCESSIVE_CUES -> "字幕条目数量超过 ClearCut 单次导入上限。"
            CaptionImportEngine.Failure.NO_CUES -> "未找到有效字幕条目。"
        }
    }

    private fun baseDetails(item: IncomingDocumentItem): List<String> {
        return listOfNotNull(
            "文件：${item.displayName}",
            "文件类型：${item.kind.displayName}",
            "目标操作：${item.kind.targetAction}",
            item.mimeType?.let { "MIME 类型：$it" },
            item.sizeBytes?.let { "大小：${formatBytes(it)}" },
        )
    }

    private fun invalid(
        item: IncomingDocumentItem,
        body: String,
        warnings: List<String> = emptyList(),
        reasonCode: String? = null,
    ): IncomingDocumentImportPreview {
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.INVALID,
            title = "文档导入已阻止",
            body = body,
            details = baseDetails(item) + reasonCode?.let { listOf("原因代码：$it") }.orEmpty(),
            warnings = warnings.ifEmpty { listOf("未修改任何项目数据。") },
            canImportNow = false,
        )
    }

    private fun blocked(
        item: IncomingDocumentItem,
        body: String,
        warnings: List<String>,
    ): IncomingDocumentImportPreview {
        return IncomingDocumentImportPreview(
            item = item,
            status = IncomingDocumentImportStatus.BLOCKED,
            title = "${item.kind.displayName} 支持尚未完成",
            body = body,
            details = baseDetails(item),
            warnings = warnings,
            canImportNow = false,
        )
    }

    private fun stylePackFailureMessage(failure: StylePackFailure): String {
        return when (failure) {
            StylePackFailure.NONE -> "样式包导入失败。"
            StylePackFailure.UNREADABLE -> "ClearCut 无法读取此文件。"
            StylePackFailure.INVALID_JSON -> "文件不是有效的 JSON。"
            StylePackFailure.MISSING_REQUIRED_FIELDS -> "样式包缺少必填字段（id、name 或 styles）。"
            StylePackFailure.INVALID_SCHEMA -> "样式包 schemaVersion 必须为正整数。"
            StylePackFailure.WRONG_KIND -> "此文件声明了其他声明式包类型。"
            StylePackFailure.INCOMPATIBLE_VERSION -> "样式包需要更新版本的 ClearCut。"
            StylePackFailure.MISSING_MANIFEST_FIELDS -> "当前架构的样式包必须声明兼容性和来源元数据。"
            StylePackFailure.UNKNOWN_REQUIRED_CAPABILITY -> "样式包需要当前 ClearCut 版本不支持的能力。"
            StylePackFailure.INCOMPATIBLE_APP_VERSION -> "样式包需要更新版本的 ClearCut。"
            StylePackFailure.UNSAFE_CONTENT -> "样式包包含可执行或插件内容，ClearCut 已拒绝。"
            StylePackFailure.INVALID_STYLE_ENTRY -> "样式包包含无效的样式条目。"
            StylePackFailure.MISSING_CONTENT_HASH -> "当前架构的样式包必须包含内容哈希。"
            StylePackFailure.HASH_MISMATCH -> "样式包内容未通过完整性检查。"
            StylePackFailure.EMPTY_STYLES -> "样式包中没有样式。"
            StylePackFailure.DUPLICATE_ID -> "样式包包含重复的样式 ID。"
            StylePackFailure.OVERSIZED -> "样式包文件过大。"
            StylePackFailure.WRITE_FAILED -> "无法将样式包保存到设备存储。"
        }
    }

    private fun effectPackFailureMessage(failure: EffectShareEngine.EffectPackFailure): String {
        return when (failure) {
            EffectShareEngine.EffectPackFailure.NONE -> "效果包验证失败。"
            EffectShareEngine.EffectPackFailure.UNREADABLE -> "ClearCut 无法读取此效果包。"
            EffectShareEngine.EffectPackFailure.INVALID_JSON -> "效果包不是有效的 JSON。"
            EffectShareEngine.EffectPackFailure.INVALID_SCHEMA -> "效果包 schemaVersion 必须为正整数。"
            EffectShareEngine.EffectPackFailure.WRONG_KIND -> "此文件声明了其他声明式包类型。"
            EffectShareEngine.EffectPackFailure.INCOMPATIBLE_VERSION -> "效果包需要更新版本的 ClearCut。"
            EffectShareEngine.EffectPackFailure.MISSING_MANIFEST_FIELDS -> "当前架构的效果包必须声明兼容性和来源元数据。"
            EffectShareEngine.EffectPackFailure.UNKNOWN_REQUIRED_CAPABILITY -> "效果包需要当前 ClearCut 版本不支持的能力。"
            EffectShareEngine.EffectPackFailure.INCOMPATIBLE_APP_VERSION -> "效果包需要更新版本的 ClearCut。"
            EffectShareEngine.EffectPackFailure.UNSAFE_CONTENT -> "效果包包含可执行或插件内容，ClearCut 已拒绝。"
            EffectShareEngine.EffectPackFailure.MISSING_CONTENT_HASH -> "当前架构的效果包必须包含内容哈希。"
            EffectShareEngine.EffectPackFailure.HASH_MISMATCH -> "效果包内容未通过完整性检查。"
            EffectShareEngine.EffectPackFailure.INVALID_ENTRY -> "效果包包含不支持或无效的效果条目。"
            EffectShareEngine.EffectPackFailure.INVALID_LUT -> "效果包包含格式错误或不支持的嵌入式 LUT。"
        }
    }

    private fun stabilizationProfileFailureMessage(failure: StabilizationProfileFailure): String {
        return when (failure) {
            StabilizationProfileFailure.NONE -> "稳定配置验证失败。"
            StabilizationProfileFailure.UNREADABLE -> "ClearCut 无法读取此稳定配置。"
            StabilizationProfileFailure.INVALID_JSON -> "稳定配置不是有效的 JSON。"
            StabilizationProfileFailure.INVALID_SCHEMA -> "稳定配置 schemaVersion 无效。"
            StabilizationProfileFailure.WRONG_KIND -> "此文件声明了其他声明式包类型。"
            StabilizationProfileFailure.INCOMPATIBLE_VERSION,
            StabilizationProfileFailure.INCOMPATIBLE_APP_VERSION -> "稳定配置需要更新版本的 ClearCut。"
            StabilizationProfileFailure.MISSING_REQUIRED_METADATA -> "配置缺少必需的镜头、运动、裁剪或来源元数据。"
            StabilizationProfileFailure.UNSAFE_CONTENT -> "配置包含可执行或插件内容，ClearCut 已拒绝。"
            StabilizationProfileFailure.MISSING_CONTENT_HASH -> "当前架构的配置必须包含内容哈希。"
            StabilizationProfileFailure.HASH_MISMATCH -> "配置内容未通过完整性检查。"
            StabilizationProfileFailure.UNKNOWN_REQUIRED_CAPABILITY -> "配置需要当前 ClearCut 版本不支持的能力。"
        }
    }

    private fun templateImportFailureMessage(failure: TemplateImportFailure): String {
        return when (failure) {
            TemplateImportFailure.INCOMPATIBLE -> "模板需要更新版本的 ClearCut，或使用了当前不支持的工具。"
            TemplateImportFailure.OVERSIZED_FILE -> "模板文件过大。"
            TemplateImportFailure.INVALID_JSON,
            TemplateImportFailure.INVALID_STATE -> "模板文件无法读取。"
            TemplateImportFailure.UNREADABLE_FILE,
            TemplateImportFailure.WRITE_FAILED,
            TemplateImportFailure.NONE -> "模板导入失败。"
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kib = bytes / 1024.0
        if (kib < 1024.0) return "%.1f KB".format(kib)
        val mib = kib / 1024.0
        if (mib < 1024.0) return "%.1f MB".format(mib)
        return "%.2f GB".format(mib / 1024.0)
    }

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = durationMs / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    private companion object {
        const val DEFAULT_READ_BUFFER_BYTES = 16 * 1024
    }
}

package com.novacut.editor.engine

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.novacut.editor.engine.AppLog
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Platform handoff facade for YouTube / TikTok / Instagram / Threads.
 *
 * Strategy:
 *   1. When the OAuth creds are configured AND a native SDK/library is present,
 *      perform a resumable upload through the platform API.
 *   2. Otherwise fall back to a platform-branded share intent targeting the
 *      installed client app (requires the user to have it installed). This
 *      works offline and preserves user privacy with no ClearCut-side upload.
 *
 * Today only the share-intent fallback is wired. The OAuth resumable-upload
 * path requires platform partner approval (TikTok) or API-key setup (YT Data
 * API v3). Leaving the hook in the engine so the UI surface is stable and
 * we can ship the API integration incrementally without UI churn.
 */
@Singleton
class DirectPublishEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {

    enum class Target(
        val displayName: String,
        val packageName: String?,
        val hasAiDisclosureControl: Boolean = false
    ) {
        YOUTUBE("YouTube", "com.google.android.youtube", hasAiDisclosureControl = true),
        TIKTOK("TikTok", "com.zhiliaoapp.musically", hasAiDisclosureControl = true),
        INSTAGRAM("Instagram Reels", "com.instagram.android"),
        THREADS("Threads", "com.instagram.barcelona"),
        TWITTER_X("X", "com.twitter.android"),
        LINKEDIN("LinkedIn", "com.linkedin.android")
    }

    data class PublishMeta(
        val title: String,
        val description: String,
        val tags: List<String>,
        val chapters: String = "",
        val aiDisclosureSummary: String = "",
        val visibility: Visibility = Visibility.PRIVATE
    )

    enum class Visibility { PUBLIC, UNLISTED, PRIVATE }

    data class PlatformCapability(
        val target: Target,
        val shareHandoffLabel: String,
        val apiUpload: ApiUploadCapability,
        val requiresManualDisclosureReview: Boolean
    )

    data class ApiUploadCapability(
        val available: Boolean,
        val unavailableReason: String
    )

    data class Result(
        val intent: Intent?,
        val used: Method,
        val message: String,
        val capability: PlatformCapability? = null
    )
    enum class Method { API_UPLOAD, SHARE_INTENT, NONE }

    fun capabilityFor(target: Target): PlatformCapability = platformCapabilityFor(target)

    suspend fun publish(
        filePath: String,
        target: Target,
        meta: PublishMeta
    ): Result = withContext(Dispatchers.IO) {
        val capability = capabilityFor(target)
        val file = File(filePath)
        validatePublishableFile(file)?.let { message ->
            return@withContext Result(null, Method.NONE, message)
        }
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            AppLog.w(TAG, "FileProvider failed for ${RedactedLog.path(filePath)}", e)
            return@withContext Result(null, Method.NONE, "导出文件不在 ClearCut 可安全分享的位置")
        }
        val intent = buildShareIntent(uri, target, meta)
        val targetInstalled = target.packageName != null && isInstalled(target.packageName)
        if (targetInstalled) {
            intent.setPackage(target.packageName)
        }
        val launchIntent = if (targetInstalled) {
            intent
        } else {
            Intent.createChooser(intent, capability.shareHandoffLabel).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        Result(
            intent = launchIntent,
            used = Method.SHARE_INTENT,
            message = "正在打开 ${target.displayName}，由你确认并发布…",
            capability = capability
        )
    }

    private fun buildShareIntent(uri: Uri, target: Target, meta: PublishMeta): Intent {
        val safeMeta = normalizePublishMeta(meta)
        val body = buildPublishShareText(safeMeta, target)
        return Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            clipData = ClipData.newUri(context.contentResolver, safeMeta.title, uri)
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, safeMeta.title)
            putExtra(Intent.EXTRA_SUBJECT, safeMeta.title)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isInstalled(pkg: String): Boolean = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: Exception) { false }

    companion object { private const val TAG = "DirectPublishEngine" }
}

internal fun platformCapabilityFor(
    target: DirectPublishEngine.Target
): DirectPublishEngine.PlatformCapability {
    return DirectPublishEngine.PlatformCapability(
        target = target,
        shareHandoffLabel = "在 ${target.displayName} 中打开",
        apiUpload = DirectPublishEngine.ApiUploadCapability(
            available = false,
            unavailableReason = "API 上传需要 OAuth 授权、凭据存储、平台审批以及断点续传适配器。"
        ),
        requiresManualDisclosureReview = target.hasAiDisclosureControl
    )
}

private const val MAX_SHARE_TITLE_CHARS = 120
private const val MAX_SHARE_DESCRIPTION_CHARS = 4_000
private const val MAX_SHARE_CHAPTERS_CHARS = 4_000
private const val MAX_SHARE_AI_DISCLOSURE_CHARS = 1_000
private const val MAX_SHARE_TAGS = 30
private const val MAX_SHARE_TAG_CHARS = 48
private const val MAX_SHARE_BODY_CHARS = 8_000
private val SAFE_HASHTAG_CHARS = Regex("[^A-Za-z0-9_]")

internal fun validatePublishableFile(file: File): String? = when {
    !file.exists() -> "找不到导出文件"
    !file.isFile -> "导出路径不是视频文件"
    file.length() <= 0L -> "导出文件为空"
    !file.canRead() -> "无法读取导出文件"
    else -> null
}

internal fun buildPublishShareText(
    meta: DirectPublishEngine.PublishMeta,
    target: DirectPublishEngine.Target? = null
): String {
    val safeMeta = normalizePublishMeta(meta)
    return buildString {
        append(safeMeta.title)
        if (safeMeta.description.isNotBlank()) append("\n\n").append(safeMeta.description)
        if (safeMeta.aiDisclosureSummary.isNotBlank()) {
            append("\n\n")
                .append("AI 使用披露提醒：")
                .append(safeMeta.aiDisclosureSummary)
            if (target?.hasAiDisclosureControl == true) {
                append(" 请在发布前检查 ")
                    .append(target.displayName)
                    .append(" 的 AI 内容披露设置。")
            }
        }
        if (safeMeta.chapters.isNotBlank()) append("\n\n").append(safeMeta.chapters)
        if (safeMeta.tags.isNotEmpty()) {
            val tags = safeMeta.tags.joinToString(" ") { "#$it" }
            if (tags.isNotBlank()) append("\n\n").append(tags)
        }
    }.take(MAX_SHARE_BODY_CHARS).trimEnd()
}

internal fun normalizePublishMeta(
    meta: DirectPublishEngine.PublishMeta
): DirectPublishEngine.PublishMeta {
    return meta.copy(
        title = normalizeShareText(meta.title, fallback = "ClearCut 导出", maxChars = MAX_SHARE_TITLE_CHARS),
        description = normalizeShareText(
            raw = meta.description,
            fallback = "",
            maxChars = MAX_SHARE_DESCRIPTION_CHARS,
            preserveLineBreaks = true
        ),
        chapters = normalizeShareText(
            raw = meta.chapters,
            fallback = "",
            maxChars = MAX_SHARE_CHAPTERS_CHARS,
            preserveLineBreaks = true
        ),
        aiDisclosureSummary = normalizeShareText(
            raw = meta.aiDisclosureSummary,
            fallback = "",
            maxChars = MAX_SHARE_AI_DISCLOSURE_CHARS,
            preserveLineBreaks = false
        ),
        tags = meta.tags.asSequence()
            .map { tag -> tag.replace(SAFE_HASHTAG_CHARS, "").take(MAX_SHARE_TAG_CHARS) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(MAX_SHARE_TAGS)
            .toList()
    )
}

private fun normalizeShareText(
    raw: String,
    fallback: String,
    maxChars: Int,
    preserveLineBreaks: Boolean = false
): String {
    val cleaned = raw
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .map { char ->
            when {
                preserveLineBreaks && char == '\n' -> '\n'
                char.isISOControl() -> ' '
                else -> char
            }
        }
        .joinToString("")
    val normalized = if (preserveLineBreaks) {
        cleaned
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    } else {
        cleaned
            .replace(Regex("""[ \t\n]+"""), " ")
            .trim()
    }
    return normalized.ifBlank { fallback }.take(maxChars).trim().ifBlank { fallback.take(maxChars) }
}

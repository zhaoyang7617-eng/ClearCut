package com.novacut.editor.model

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.util.UUID

@Immutable
data class TextOverlay(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val fontFamily: String = "sans-serif",
    val fontSize: Float = 48f,
    val color: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x00000000,
    val strokeColor: Long = 0xFF000000,
    val strokeWidth: Float = 0f,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val alignment: TextAlignment = TextAlignment.CENTER,
    val positionX: Float = 0.5f,
    val positionY: Float = 0.5f,
    val startTimeMs: Long = 0L,
    val endTimeMs: Long = 3000L,
    val animationIn: TextAnimation = TextAnimation.NONE,
    val animationOut: TextAnimation = TextAnimation.NONE,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val shadowColor: Long = 0x80000000,
    val shadowOffsetX: Float = 0f,
    val shadowOffsetY: Float = 0f,
    val shadowBlur: Float = 0f,
    val glowColor: Long = 0x00000000,
    val glowRadius: Float = 0f,
    val letterSpacing: Float = 0f,
    val lineHeight: Float = 1.2f,
    val textPath: TextPath? = null,
    val templateId: String? = null,
    val keyframes: List<Keyframe> = emptyList(),
    val gradientStartColor: Long? = null,
    val gradientEndColor: Long? = null,
    val gradientAngle: Float = 0f,
    val wordStaggerMs: Long = 0L
) {
    init {
        require(fontSize > 0f) { "Font size must be positive" }
        require(text.isNotEmpty()) { "Text overlay cannot be empty" }
    }
}

@Immutable
data class ImageOverlay(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val positionX: Float = 0f,
    val positionY: Float = 0f,
    val scale: Float = 0.3f,
    val rotation: Float = 0f,
    val opacity: Float = 1.0f,
    val type: ImageOverlayType = ImageOverlayType.STICKER
) {
    init {
        require(startTimeMs < endTimeMs) { "startTimeMs must be < endTimeMs" }
        require(scale > 0f) { "scale must be positive" }
        require(opacity in 0f..1f) { "opacity must be between 0 and 1" }
    }
}

enum class ImageOverlayType { STICKER, GIF, IMAGE }

data class TextPath(
    val type: TextPathType,
    val points: List<MaskPoint> = emptyList(),
    val progress: Float = 1f
)

enum class TextPathType { STRAIGHT, CURVED, CIRCULAR, WAVE }

data class TextTemplate(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val category: TextTemplateCategory,
    val layers: List<TextOverlay>,
    val durationMs: Long = 3000L,
    val thumbnailRes: Int = 0
)

enum class TextTemplateCategory(val displayName: String) {
    LOWER_THIRD("下三分之一"),
    TITLE_CARD("标题卡"),
    END_SCREEN("片尾画面"),
    CALL_TO_ACTION("行动号召"),
    SOCIAL("社交媒体"),
    MINIMAL("极简")
}

enum class TextAlignment { LEFT, CENTER, RIGHT }

enum class TextAnimation(val displayName: String) {
    NONE("无"),
    FADE("淡入淡出"),
    SLIDE_UP("向上滑入"),
    SLIDE_DOWN("向下滑入"),
    SLIDE_LEFT("向左滑入"),
    SLIDE_RIGHT("向右滑入"),
    SCALE("缩放"),
    TYPEWRITER("打字机"),
    BOUNCE("弹跳"),
    SPIN("旋转"),
    BLUR_IN("模糊入场"),
    GLITCH("故障"),
    WAVE("波浪"),
    ELASTIC("弹性"),
    FLIP("翻转")
}

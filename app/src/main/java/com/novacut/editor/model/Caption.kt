package com.novacut.editor.model

import androidx.compose.runtime.Immutable
import java.util.UUID

@Immutable
data class Caption(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val words: List<CaptionWord> = emptyList(),
    val style: CaptionStyle = CaptionStyle()
) {
    init {
        require(endTimeMs >= startTimeMs) { "endTimeMs must be >= startTimeMs" }
    }
}

@Immutable
data class CaptionWord(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val confidence: Float = 1f
)

data class CaptionStyle(
    val type: CaptionStyleType = CaptionStyleType.SUBTITLE_BAR,
    val fontFamily: String = "sans-serif-medium",
    val fontSize: Float = 36f,
    val color: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0xCC000000,
    val highlightColor: Long = 0xFFFFD700,
    val positionY: Float = 0.85f,
    val outline: Boolean = true,
    val outlineColor: Long = 0xFF000000,
    val outlineWidth: Float = 2f,
    val shadow: Boolean = true
)

enum class CaptionStyleType {
    SUBTITLE_BAR,
    WORD_BY_WORD,
    KARAOKE,
    BOUNCE,
    TYPEWRITER,
    MINIMAL
}

enum class CaptionTemplateType(val displayName: String) {
    HIGH_CONTRAST("高对比度"),
    LARGE_TEXT("大字号"),
    REDUCED_MOTION("减少动效"),
    CLASSIC("经典"),
    KARAOKE("卡拉 OK"),
    WORD_BY_WORD("逐词"),
    BOUNCE("弹跳"),
    GLOW("发光"),
    OUTLINE("描边"),
    SHADOW_POP("阴影弹出"),
    GRADIENT("渐变"),
    TYPEWRITER("打字机"),
    NEON("霓虹"),
    COMIC("漫画"),
    MINIMAL("极简"),
    BOLD_CENTER("粗体居中"),
    LOWER_THIRD("下三分之一"),
    SUBTITLE("字幕")
}

enum class CaptionAccessibilityPreset(val displayName: String) {
    STANDARD("标准"),
    WCAG_AA_CONTRAST("WCAG AA 对比度"),
    LARGE_TEXT("大字号"),
    REDUCED_MOTION("减少动效")
}

data class CaptionStyleTemplate(
    val id: String = UUID.randomUUID().toString(),
    val type: CaptionTemplateType,
    val fontFamily: String = "sans-serif",
    val fontSize: Float = 24f,
    val textColor: Long = 0xFFFFFFFF,
    val backgroundColor: Long = 0x80000000,
    val outlineColor: Long = 0xFF000000,
    val outlineWidth: Float = 0f,
    val shadowColor: Long = 0x80000000,
    val shadowOffsetX: Float = 2f,
    val shadowOffsetY: Float = 2f,
    val positionY: Float = 0.85f,
    val animation: TextAnimation = TextAnimation.FADE,
    val highlightColor: Long = 0xFFFFD700,
    val wordByWord: Boolean = false,
    val accessibilityPreset: CaptionAccessibilityPreset = CaptionAccessibilityPreset.STANDARD
)

val CaptionStyleTemplate.isAccessibilityPreset: Boolean
    get() = accessibilityPreset != CaptionAccessibilityPreset.STANDARD

fun CaptionStyleTemplate.toCaptionStyleType(): CaptionStyleType = when {
    accessibilityPreset == CaptionAccessibilityPreset.REDUCED_MOTION -> CaptionStyleType.SUBTITLE_BAR
    type == CaptionTemplateType.KARAOKE -> CaptionStyleType.KARAOKE
    type == CaptionTemplateType.WORD_BY_WORD -> CaptionStyleType.WORD_BY_WORD
    type == CaptionTemplateType.BOUNCE -> CaptionStyleType.BOUNCE
    type == CaptionTemplateType.TYPEWRITER -> CaptionStyleType.TYPEWRITER
    type == CaptionTemplateType.MINIMAL -> CaptionStyleType.MINIMAL
    else -> CaptionStyleType.SUBTITLE_BAR
}

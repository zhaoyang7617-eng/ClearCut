package com.novacut.editor.model

import androidx.compose.runtime.Immutable
import java.util.UUID

@Immutable
data class Transition(
    val type: TransitionType,
    val durationMs: Long = 500L,
    val easing: TransitionEasing = TransitionEasing.LINEAR
) {
    init {
        require(durationMs > 0) { "Transition duration must be positive" }
    }
}

enum class TransitionEasing(val displayName: String) {
    LINEAR("线性"),
    EASE_IN("缓入"),
    EASE_OUT("缓出"),
    EASE_IN_OUT("缓入缓出")
}

enum class TransitionType(val displayName: String) {
    DISSOLVE("叠化"),
    FADE_BLACK("淡出至黑"),
    FADE_WHITE("淡出至白"),
    WIPE_LEFT("左擦除"),
    WIPE_RIGHT("右擦除"),
    WIPE_UP("上擦除"),
    WIPE_DOWN("下擦除"),
    SLIDE_LEFT("左滑"),
    SLIDE_RIGHT("右滑"),
    ZOOM_IN("放大"),
    ZOOM_OUT("缩小"),
    SPIN("旋转"),
    FLIP("翻转"),
    CUBE("立方体"),
    RIPPLE("波纹"),
    PIXELATE("像素化"),
    DIRECTIONAL_WARP("定向扭曲"),
    WIND("风吹"),
    MORPH("变形"),
    GLITCH("故障"),
    CIRCLE_OPEN("圆形展开"),
    CROSS_ZOOM("交叉缩放"),
    DREAMY("梦幻"),
    HEART("心形"),
    SWIRL("漩涡"),
    DOOR_OPEN("开门"),
    BURN("燃烧"),
    RADIAL_WIPE("径向擦除"),
    MOSAIC_REVEAL("马赛克揭示"),
    BOUNCE("弹跳"),
    LENS_FLARE("镜头光晕"),
    PAGE_CURL("卷页"),
    CROSS_WARP("交叉扭曲"),
    ANGULAR("角度切换"),
    KALEIDOSCOPE("万花筒"),
    SQUARES_WIRE("方格线"),
    COLOR_PHASE("色彩相位")
}

@Immutable
data class Keyframe(
    val timeOffsetMs: Long,
    val property: KeyframeProperty,
    val value: Float,
    val easing: Easing = Easing.LINEAR,
    val handleInX: Float = 0f,
    val handleInY: Float = 0f,
    val handleOutX: Float = 0f,
    val handleOutY: Float = 0f,
    val interpolation: KeyframeInterpolation = KeyframeInterpolation.BEZIER
)

enum class KeyframeProperty {
    POSITION_X, POSITION_Y, SCALE_X, SCALE_Y, ROTATION, OPACITY, VOLUME,
    ANCHOR_X, ANCHOR_Y, MASK_FEATHER, MASK_EXPANSION, MASK_OPACITY
}

enum class KeyframeInterpolation { LINEAR, BEZIER, HOLD }

enum class Easing {
    LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT, SPRING,
    BOUNCE, ELASTIC, BACK, CIRCULAR, EXPO, SINE, CUBIC
}

@Immutable
data class TimelineMarker(
    val id: String = UUID.randomUUID().toString(),
    val timeMs: Long,
    val label: String = "",
    val color: MarkerColor = MarkerColor.BLUE,
    val notes: String = ""
)

/** A half-open project-timeline interval used by range-based edit commands. */
@Immutable
data class TimelineRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0L) { "Timeline range start must be non-negative" }
        require(endMs > startMs) { "Timeline range end must be after its start" }
    }

    val durationMs: Long get() = endMs - startMs
}

enum class GlobalTransitionType(val displayName: String) {
    FADE_FROM_BLACK("从黑场淡入"),
    FADE_TO_BLACK("淡出至黑"),
    FADE_FROM_WHITE("从白场淡入"),
    FADE_TO_WHITE("淡出至白"),
}

@Immutable
data class GlobalTransition(
    val id: String = UUID.randomUUID().toString(),
    val type: GlobalTransitionType,
    val durationMs: Long = 1000L,
    val timelineAnchorMs: Long,
    val easing: TransitionEasing = TransitionEasing.EASE_IN_OUT
) {
    val endMs: Long get() = timelineAnchorMs + durationMs
}

enum class MarkerColor(val argb: Long) {
    RED(0xFFE78284), ORANGE(0xFFEF9F76), YELLOW(0xFFE5C890),
    GREEN(0xFFA6D189), BLUE(0xFF8CAAEE), PURPLE(0xFFCA9EE6)
}

data class MotionTrackingData(
    val id: String = UUID.randomUUID().toString(),
    val trackPoints: List<MotionTrackPoint> = emptyList(),
    val targetType: TrackTargetType = TrackTargetType.POINT,
    val isActive: Boolean = false
)

data class MotionTrackPoint(
    val timeOffsetMs: Long,
    val x: Float,
    val y: Float,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val rotation: Float = 0f,
    val confidence: Float = 1f
)

enum class TrackTargetType { POINT, SURFACE, FACE }

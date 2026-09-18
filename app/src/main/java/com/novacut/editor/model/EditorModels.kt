package com.novacut.editor.model

import androidx.compose.runtime.Immutable

data class ProjectTemplate(
    val id: String,
    val name: String,
    val category: TemplateCategory,
    val description: String,
    val aspectRatio: AspectRatio,
    val tracks: List<Track>,
    val textOverlays: List<TextOverlay> = emptyList(),
    val durationMs: Long
)

enum class TemplateCategory(val displayName: String) {
    VLOG("Vlog"),
    TUTORIAL("教程"),
    SHORT_FORM("短视频"),
    CINEMATIC("电影感"),
    SLIDESHOW("幻灯片"),
    PROMO("宣传"),
    BLANK("空白")
}

@Immutable
data class ProjectSnapshot(
    val id: String = java.util.UUID.randomUUID().toString(),
    val projectId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val label: String = "",
    val stateJson: String
)

data class ProxySettings(
    val enabled: Boolean = false,
    val resolution: ProxyResolution = ProxyResolution.QUARTER,
    val autoGenerate: Boolean = true
)

enum class ProxyResolution(val scale: Float, val label: String) {
    HALF(0.5f, "1/2"),
    QUARTER(0.25f, "1/4"),
    EIGHTH(0.125f, "1/8")
}

enum class SortMode(val label: String) {
    DATE_DESC("最近"),
    DATE_ASC("最早"),
    NAME_ASC("A-Z"),
    NAME_DESC("Z-A"),
    DURATION_DESC("最长")
}

/**
 * Subset filter applied over the project gallery. Orthogonal to SortMode —
 * the user can e.g. look at `RECENT_7D` projects sorted by `NAME_ASC`. The
 * filter logic treats each branch independently; composing with search is
 * left to the combining flow in the view model.
 */
enum class ProjectFilterMode(val label: String) {
    ALL("全部"),
    RECENT_7D("本周"),
    LONG("超过 1 分钟"),
    SHORT("少于 10 秒"),
    EMPTY("无片段")
}

enum class SpeedPresetType {
    BULLET_TIME,
    HERO_TIME,
    MONTAGE,
    JUMP_CUT,
    SMOOTH_RAMP_UP,
    SMOOTH_RAMP_DOWN,
    PULSE,
    FLASH,
    DREAMY,
    REWIND,
    TIME_FREEZE,
    FILM_REEL,
    HEARTBEAT,
    CRESCENDO
}

enum class SaveIndicatorState {
    HIDDEN, SAVING, SAVED, ERROR
}

data class TutorialStep(
    val id: String,
    val title: String,
    val description: String,
    val highlightArea: TutorialHighlight
)

enum class TutorialHighlight {
    TIMELINE, PREVIEW, TOOL_BAR, ADD_MEDIA, EXPORT, EFFECTS
}

data class UndoHistoryEntry(
    val index: Int,
    val description: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Immutable
data class DrawingPath(
    val points: List<Pair<Float, Float>>,
    val color: Long,
    val strokeWidth: Float
)

enum class StoryboardCardStatus(val displayName: String) {
    PLANNED("计划中"),
    FILMED("已拍摄"),
    EDITED("已剪辑"),
}

@Immutable
data class StoryboardCard(
    val id: String = java.util.UUID.randomUUID().toString(),
    val ordinal: Int,
    val shotText: String,
    val targetDurationMs: Long = 5_000L,
    val status: StoryboardCardStatus = StoryboardCardStatus.PLANNED,
    val mediaUri: android.net.Uri? = null,
)

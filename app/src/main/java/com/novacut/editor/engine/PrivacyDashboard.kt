package com.novacut.editor.engine

/**
 * R5.5c — Privacy dashboard data model.
 *
 * Single source of truth for every category of data ClearCut collects, where
 * the data lives, what user controls exist, and how the user can export or
 * delete it. The Settings → Privacy dashboard Composable consumes this
 * model directly so the displayed surface is automatically in sync with
 * what the engines actually do.
 *
 * The dashboard is **also** the source for any future Play Store data-safety
 * form changes — if a category is not represented here, it must not be
 * collected. New categories are added by appending a [Category] entry plus
 * a [DashboardEntry] with the engine that owns the data.
 *
 * Pure Kotlin. Tests verify the invariants the dashboard's UX depends on
 * (no on-by-default cloud collection, every entry has a delete action,
 * every entry references its retention policy).
 */
object PrivacyDashboard {

    enum class Category(val displayName: String) {
        PROJECT_CONTENT("项目内容（片段、叠加层、时间线、字幕）"),
        MEDIA_METADATA("媒体元数据（时长、编码、尺寸）"),
        ML_MODELS("已下载的 ML 模型（Whisper、MediaPipe）"),
        APP_PREFERENCES("应用偏好设置（主题、默认导出设置）"),
        TEMPLATE_LIBRARY("已保存的模板 / 效果包"),
        SETTINGS_RESET_REPORTS("设置重置报告（偏好恢复）"),
        DIAGNOSTIC_LOGS("诊断日志和导出故障摘要（已脱敏）"),
        CRASH_RECORDS("崩溃记录（致命异常线索）"),
        PROCESS_EXIT_HISTORY("进程退出历史（ANR、低内存、原生崩溃）"),
        CLOUD_GENERATIVE("云端生成视频调用（需授权）"),
        MEDIAPIPE_METRICS("MediaPipe 本机任务指标（Google，需授权）"),
        AI_USAGE_LEDGER("AI 使用记录（按项目保存的披露历史）"),
        OPT_IN_TELEMETRY("主动开启的使用遥测（Sentry / Glean）"),
        UPDATE_CHECK("应用更新检查（侧载 / GitHub Release 版本查询）"),
    }

    /**
     * Where the data physically lives.
     */
    enum class StorageLocation(val displayName: String) {
        DEVICE_INTERNAL("本设备 · 应用私有存储"),
        DEVICE_SHARED("本设备 · 可与其他应用共享"),
        CLOUD_ON_DEMAND("云服务（仅在明确触发时使用）"),
    }

    /**
     * Controls the user has over a category.
     *
     * @param canExport user can export a copy via the diagnostic ZIP /
     *   project archive / explicit per-category export.
     * @param canDelete user can wipe the category from on-device storage.
     * @param hasOptOut user can disable collection entirely from Settings.
     */
    data class Controls(
        val canExport: Boolean,
        val canDelete: Boolean,
        val hasOptOut: Boolean,
    )

    data class DashboardEntry(
        val category: Category,
        val location: StorageLocation,
        val controls: Controls,
        /**
         * Where the data is collected from / written by. Used so the UX can
         * say "Stored by VideoEngine, Project autosave" rather than a vague
         * "Stored locally".
         */
        val collectedBy: List<String>,
        /**
         * How long the data persists by default. Human-readable copy
         * intended for the dashboard row's secondary line.
         */
        val retentionPolicy: String,
        /**
         * Whether this category is collected by default. Cloud + telemetry
         * paths must be `false` — they require explicit consent.
         */
        val collectedByDefault: Boolean,
        /**
         * Where the controls in [controls] actually live, in the user's words.
         *
         * The dashboard advertises Export/Delete/Opt out per row. When the
         * dashboard itself cannot perform an action it must say where the
         * action is instead of rendering a control that does nothing, so every
         * entry names a reachable destination.
         */
        val controlLocation: String,
    )

    /**
     * Canonical dashboard rows. Keep ordered by Category enum declaration so
     * the displayed list is deterministic.
     */
    val entries: List<DashboardEntry> = listOf(
        DashboardEntry(
            category = Category.PROJECT_CONTENT,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("ProjectAutoSave", "ProjectDatabase", "ProjectArchive", "OverlayAssetStore"),
            retentionPolicy = "保存在本设备上，直到项目/媒体副本被删除或应用存储被清除。Android 云备份只包含项目文档（数据库和自动保存 JSON），因为 Auto Backup 对每个应用有 25 MB 上限，超过后会整体失败；时间线生成媒体（定格帧、旁白、TTS、降噪音频、稳定处理片段）只会通过设备间迁移或你主动导出的项目归档传输，因此云端恢复后的项目可能缺少这些媒体。",
            collectedByDefault = true,
            controlLocation = "项目页 → 项目菜单 → 导出归档，或移到回收站",
        ),
        DashboardEntry(
            category = Category.MEDIA_METADATA,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("MediaImportEngine", "MediaPickerSheet"),
            retentionPolicy = "当源片段从所有项目中移除后删除。",
            collectedByDefault = true,
            controlLocation = "编辑器 → 媒体管理器 → 从项目中移除片段",
        ),
        DashboardEntry(
            category = Category.ML_MODELS,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = false, canDelete = true, hasOptOut = true),
            collectedBy = listOf("ModelDownloadManager"),
            retentionPolicy = "一直保留，直到你在“设置 → AI 模型”中移除该模型。",
            collectedByDefault = false,
            controlLocation = "设置 → AI 模型 → 移除模型",
        ),
        DashboardEntry(
            category = Category.APP_PREFERENCES,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("SettingsRepository", "DataStore"),
            retentionPolicy = "一直保留，直到卸载应用或清除应用存储。",
            collectedByDefault = true,
            controlLocation = "设置 → 存储 → 重置偏好设置，或 Android 应用信息 → 清除存储",
        ),
        DashboardEntry(
            category = Category.TEMPLATE_LIBRARY,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("TemplateManager"),
            retentionPolicy = "一直保留，直到从模板面板中删除。",
            collectedByDefault = true,
            controlLocation = "项目页 → 模板 → 长按模板 → 删除",
        ),
        DashboardEntry(
            category = Category.SETTINGS_RESET_REPORTS,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("SettingsRepository", "SettingsResetReportStore", "DiagnosticExportEngine"),
            retentionPolicy = "偏好设置损坏恢复报告保存在本机 filesDir/diagnostics/settings-reset-report.jsonl，最多保留最近 16 次重置记录，仅在你主动导出诊断 ZIP 时加入。",
            collectedByDefault = true,
            controlLocation = "设置 → 诊断 → 导出诊断包",
        ),
        DashboardEntry(
            category = Category.DIAGNOSTIC_LOGS,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("DiagnosticExportEngine", "ExportIncidentStore"),
            retentionPolicy = "私有导出故障记录保存在 filesDir/diagnostics/export-incidents，最多 10 条。你主动生成的诊断 ZIP 默认只包含经伪匿名化的结构化摘要，并最多保留最近 3 个 ZIP；原始编码器错误文字需要在设置中明确授权。",
            collectedByDefault = false,
            controlLocation = "设置 → 诊断 → 导出诊断包",
        ),
        DashboardEntry(
            category = Category.CRASH_RECORDS,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("CrashRecordStore", "DiagnosticExportEngine"),
            retentionPolicy = "致命崩溃线索保存在本机 filesDir/diagnostics/crashes，最多保留最近 8 条，仅在你主动导出诊断 ZIP 时加入。",
            collectedByDefault = true,
            controlLocation = "设置 → 诊断 → 导出诊断包",
        ),
        DashboardEntry(
            category = Category.PROCESS_EXIT_HISTORY,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("ProcessExitRecorder", "DiagnosticExportEngine"),
            retentionPolicy = "Android 11+ 进程退出摘要保存在本机 filesDir/diagnostics/process-exit-history.json，最多保留最近 16 条不同记录，仅在你主动导出诊断 ZIP 时加入。",
            collectedByDefault = true,
            controlLocation = "设置 → 诊断 → 导出诊断包",
        ),
        DashboardEntry(
            category = Category.CLOUD_GENERATIVE,
            location = StorageLocation.CLOUD_ON_DEMAND,
            controls = Controls(canExport = false, canDelete = true, hasOptOut = true),
            collectedBy = listOf("GenerativeVideoPolicy"),
            retentionPolicy = "按服务提供方的政策保留；每次调用前都会在授权页面中说明。",
            collectedByDefault = false,
            controlLocation = "编辑器 → AI 工具 → 每次调用前显示的授权页面",
        ),
        DashboardEntry(
            category = Category.MEDIAPIPE_METRICS,
            location = StorageLocation.CLOUD_ON_DEMAND,
            controls = Controls(canExport = false, canDelete = true, hasOptOut = true),
            collectedBy = listOf("SegmentationEngine", "SmartReframeEngine", "MediaPipeUsageGate"),
            retentionPolicy = "默认关闭，只有在你明确同意对应版本的授权后才会启用。启用后，Google 的 MediaPipe Tasks SDK 会通过 Play Services DataTransport 向 Google 上传匿名性能指标（应用 ID/版本、任务/模式、调用/丢弃次数、延迟、初始化错误）。输入媒体（画面帧/像素）不会离开设备。在设置中撤回授权后，会关闭正在运行的任务，并阻止再次启动。",
            collectedByDefault = false,
            controlLocation = "设置 → 隐私 → MediaPipe 指标授权",
        ),
        DashboardEntry(
            category = Category.AI_USAGE_LEDGER,
            location = StorageLocation.DEVICE_INTERNAL,
            controls = Controls(canExport = true, canDelete = true, hasOptOut = false),
            collectedBy = listOf("AiUsageLedger", "ProjectAutoSave", "ExportDelegate", "DirectPublishEngine", "C2paExportEngine"),
            retentionPolicy = "仅保存在项目自动保存数据中；可在导出时的披露检查页面清除。导出可在本机写入 .ai-use.json 和未签名、不可验证的 .c2pa-draft-manifest.json 侧车文件。此版本没有 C2PA 签名或嵌入能力，此功能不会让任何媒体或哈希离开设备。",
            collectedByDefault = false,
            controlLocation = "编辑器 → 导出 → AI 使用披露检查",
        ),
        DashboardEntry(
            category = Category.OPT_IN_TELEMETRY,
            location = StorageLocation.CLOUD_ON_DEMAND,
            controls = Controls(canExport = false, canDelete = true, hasOptOut = true),
            collectedBy = listOf("(future) SentryAndroid", "(future) Mozilla Glean"),
            retentionPolicy = "保留期限由服务提供方决定；默认关闭，可在“设置 → 隐私”中切换。",
            collectedByDefault = false,
            controlLocation = "设置 → 隐私（目前尚未集成遥测服务提供方）",
        ),
        DashboardEntry(
            category = Category.UPDATE_CHECK,
            location = StorageLocation.CLOUD_ON_DEMAND,
            controls = Controls(canExport = false, canDelete = true, hasOptOut = true),
            collectedBy = listOf("UpdateChecker"),
            retentionPolicy = "不会保存任何数据。启用后，ClearCut 只会向公开的 GitHub Releases API 发起一次 TLS 请求，用最新标签与当前安装版本进行比较；不会自动下载或安装 APK。默认关闭，可在“设置 → 更新”中关闭以停止所有检查。",
            collectedByDefault = false,
            controlLocation = "设置 → 更新 → 检查更新开关",
        ),
    )

    /**
     * Return the entry for a category, or null if the category isn't tracked.
     */
    fun entryFor(category: Category): DashboardEntry? =
        entries.firstOrNull { it.category == category }

    /**
     * Categories that involve the network or external services. The dashboard
     * groups these into a "Cloud & Telemetry" section with extra prominence
     * for the opt-out toggles.
     */
    fun cloudOrTelemetryCategories(): List<DashboardEntry> =
        entries.filter { it.location == StorageLocation.CLOUD_ON_DEMAND }

    // --- Display-layer helpers (Batch 15) ---
    //
    // The Compose panel orders rows by risk so the user reads the most
    // disclosure-bearing entries first (cloud paths > opt-in collection >
    // device-only enabled-by-default > device-only opt-in). These helpers
    // give the panel a stable contract without rewriting the entries list.

    /**
     * Coarse-grained section the dashboard renders. Cloud/telemetry sit at
     * the top with prominent opt-out chips; on-device collected-by-default
     * comes next; on-device opt-in (the user already turned this on at some
     * point) renders last.
     */
    enum class Section(val displayName: String) {
        CLOUD_AND_TELEMETRY("云端与遥测"),
        ON_DEVICE_COLLECTED("保存在本设备上"),
        ON_DEVICE_OPT_IN("需主动开启的功能（默认关闭）"),
    }

    /**
     * Classify an entry into its display section.
     */
    fun sectionFor(entry: DashboardEntry): Section = when {
        entry.location == StorageLocation.CLOUD_ON_DEMAND -> Section.CLOUD_AND_TELEMETRY
        entry.collectedByDefault -> Section.ON_DEVICE_COLLECTED
        else -> Section.ON_DEVICE_OPT_IN
    }

    /**
     * Return the dashboard entries already sorted for display: cloud first,
     * then collected-by-default, then opt-in. Within a section the original
     * [Category]-enum order is preserved so the panel rendering is stable.
     */
    fun sortForDisplay(): List<DashboardEntry> {
        val rank = mapOf(
            Section.CLOUD_AND_TELEMETRY to 0,
            Section.ON_DEVICE_COLLECTED to 1,
            Section.ON_DEVICE_OPT_IN to 2,
        )
        return entries.withIndex()
            .sortedWith(
                compareBy<IndexedValue<DashboardEntry>> { rank[sectionFor(it.value)] }
                    .thenBy { it.index }
            )
            .map { it.value }
    }

    /**
     * Group the dashboard entries by [Section] for direct rendering as
     * Compose sections. The returned map's iteration order matches
     * [sortForDisplay] — cloud first, then collected-by-default, then
     * opt-in. Empty sections are omitted entirely so the panel doesn't
     * render an empty header.
     */
    fun groupForDisplay(): Map<Section, List<DashboardEntry>> {
        val out = linkedMapOf<Section, MutableList<DashboardEntry>>()
        for (entry in sortForDisplay()) {
            out.getOrPut(sectionFor(entry)) { mutableListOf() }.add(entry)
        }
        return out
    }

    /**
     * Short summary of what the user can do with an entry, suitable for a
     * single-line caption. The wording is intentionally action-oriented —
     * users want to know "what can I do here" not "what is this category".
     */
    fun controlSummary(entry: DashboardEntry): String {
        val parts = mutableListOf<String>()
        if (entry.controls.canExport) parts += "导出"
        if (entry.controls.canDelete) parts += "删除"
        if (entry.controls.hasOptOut) parts += "关闭"
        return if (parts.isEmpty()) "只读" else parts.joinToString(" · ")
    }
}

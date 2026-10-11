package me.rerere.rikkahub.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.IOException
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import io.pebbletemplates.pebble.PebbleEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.ai.AssistantResolver
import me.rerere.rikkahub.data.ai.mcp.McpServerConfig
import me.rerere.rikkahub.data.gemini.DENIED_MODEL_IDS
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_COMPRESS_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_OCR_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_SUGGESTION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TITLE_PROMPT
import me.rerere.rikkahub.data.ai.prompts.DEFAULT_TRANSLATION_PROMPT
import me.rerere.rikkahub.data.ai.prompts.LEARNING_MODE_PROMPT
import me.rerere.asr.ASRProviderSetting
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV1Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV2Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV3Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV4Migration
import me.rerere.rikkahub.data.datastore.migration.PreferenceStoreV5Migration
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.InjectionPosition
import me.rerere.rikkahub.data.model.Lorebook
import me.rerere.rikkahub.data.model.PromptInjection
import me.rerere.rikkahub.data.model.QuickMessage
import me.rerere.rikkahub.data.model.Tag
import me.rerere.rikkahub.data.sync.BackupItem
import me.rerere.rikkahub.data.sync.s3.S3Config
import me.rerere.rikkahub.subagent.SubAgentDefaults
import me.rerere.rikkahub.ui.theme.CustomTheme
import me.rerere.rikkahub.ui.theme.PresetThemes
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.rikkahub.utils.toMutableStateFlow
import me.rerere.search.SearchCommonOptions
import me.rerere.search.SearchServiceOptions
import me.rerere.tts.provider.TTSProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

enum class AutoCompactionThresholdMode {
    PERCENT,
    TOKENS,
}

/** Default automatic compaction summary target as a percentage of the active context. */
const val DEFAULT_CONTEXT_COMPACTION_TARGET_PERCENT = 1

private const val TAG = "PreferencesStore"

/**
 * Per-entry tolerant decode for the persisted `providers` list.
 *
 * Why this exists: the providers list is stored in DataStore as a single JSON array of
 * polymorphic [ProviderSetting] entries. A single-shot `decodeFromString<List<ProviderSetting>>`
 * throws on the entire list as soon as one element has an unknown polymorphic discriminator.
 *
 * Concrete trigger that motivated this: the never-shipped Phase-22A scaffolding seeded a
 * `"type":"local_llamacpp"` entry into DEFAULT_PROVIDERS for early test installs. Deleting
 * the `LlamaCppLocal` subclass would otherwise make decode-of-list throw on those entries
 * → user loses ALL their saved providers (API keys, custom models, the lot).
 *
 * Per-entry decode lets surviving entries land while the unknown one is logged and skipped.
 * Keep this even though `local_llamacpp` now ships: it's good hygiene for any future
 * polymorphic schema change (renamed types, removed types, etc).
 */
private fun decodeProvidersTolerant(raw: String): List<ProviderSetting> {
    if (raw.isBlank()) return emptyList()
    val array = runCatching {
        JsonInstant.parseToJsonElement(raw) as? JsonArray
    }.getOrNull() ?: return emptyList()
    return array.mapNotNull { element ->
        try {
            JsonInstant.decodeFromJsonElement<ProviderSetting>(element)
        } catch (e: SerializationException) {
            Log.w(TAG, "Skipping unrecognised provider entry during decode: ${e.message}")
            null
        }
    }
}

/**
 * `models` transform for the GeminiOAuth normalization branch below: de-duplicate by id, then
 * drop any entry whose modelId is in [DENIED_MODEL_IDS].
 *
 * The fetch-side filter in `GeminiProvider.fetchAvailableModels` only stops
 * `gemini-3.1-pro-high` being re-added; a copy already persisted before that filter existed
 * survives the merge in `mergeCodexModels` untouched. This runs on every settings load, so it
 * evicts the stale entry read-side, without a migration or rewriting the persisted JSON.
 */
private fun dropDeniedGeminiOAuthModels(models: List<Model>): List<Model> =
    models.distinctBy { model -> model.id }.filterNot { model -> model.modelId in DENIED_MODEL_IDS }

private const val SETTINGS_STORE_NAME = "settings"

// 读取失败时的最大重试次数
private const val READ_MAX_RETRIES = 3

@Volatile
private var settingsDataStore: DataStore<Preferences>? = null

// 进程内单例, 同一文件只能存在一个 DataStore 实例
private val Context.settingsStore: DataStore<Preferences>
    get() = settingsDataStore ?: synchronized(SettingsStore::class) {
        settingsDataStore ?: createSettingsDataStore(applicationContext).also { settingsDataStore = it }
    }

private fun createSettingsDataStore(context: Context): DataStore<Preferences> {
    val file = context.preferencesDataStoreFile(SETTINGS_STORE_NAME)
    return PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { exception ->
            // 文件已损坏无法解析, 先留一份原文件用于排查/抢救, 再重建为空
            Log.e(TAG, "Settings datastore corrupted, resetting", exception)
            runCatching {
                file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"))
            }.onFailure {
                Log.e(TAG, "Failed to backup corrupted settings file", it)
            }
            emptyPreferences()
        },
        migrations = listOf(
            PreferenceStoreV1Migration(),
            PreferenceStoreV2Migration(),
            PreferenceStoreV3Migration(),
            PreferenceStoreV4Migration(),
            PreferenceStoreV5Migration(),
        ),
        produceFile = { file },
    )
}

class SettingsStore(
    context: Context,
    scope: AppScope,
) : KoinComponent {
    companion object {
        // 版本号
        val VERSION = intPreferencesKey("data_version")

        // UI设置
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val THEME_ID = stringPreferencesKey("theme_id")
        val CUSTOM_THEMES = stringPreferencesKey("custom_themes")
        val DISPLAY_SETTING = stringPreferencesKey("display_setting")
        val NETWORK_SETTING = stringPreferencesKey("network_setting")
        val DEVELOPER_MODE = booleanPreferencesKey("developer_mode")

        // 模型选择
        val FAVORITE_MODELS = stringPreferencesKey("favorite_models")
        val SELECT_MODEL = stringPreferencesKey("chat_model")
        val FAST_MODEL = stringPreferencesKey("fast_model")
        val FAST_MODEL_REASONING_LEVEL = stringPreferencesKey("fast_model_reasoning_level")
        val TRANSLATE_MODEL = stringPreferencesKey("translate_model")
        val ENABLE_SUGGESTION = booleanPreferencesKey("enable_suggestion")
        val IMAGE_GENERATION_MODEL = stringPreferencesKey("image_generation_model")
        val VIDEO_GENERATION_MODEL = stringPreferencesKey("video_generation_model")
        val TITLE_PROMPT = stringPreferencesKey("title_prompt")
        val TRANSLATION_PROMPT = stringPreferencesKey("translation_prompt")
        val TRANSLATE_THINKING_BUDGET = intPreferencesKey("translate_thinking_budget")
        val SUGGESTION_PROMPT = stringPreferencesKey("suggestion_prompt")
        val OCR_MODEL = stringPreferencesKey("ocr_model")
        val OCR_PROMPT = stringPreferencesKey("ocr_prompt")
        val COMPRESS_MODEL = stringPreferencesKey("compress_model")
        val COMPRESS_PROMPT = stringPreferencesKey("compress_prompt")
        val ENABLE_AUTO_COMPACTION = booleanPreferencesKey("enable_auto_compaction")
        val AUTO_COMPACTION_THRESHOLD_MODE = stringPreferencesKey("auto_compaction_threshold_mode")
        val AUTO_COMPACTION_THRESHOLD_PERCENT = intPreferencesKey("auto_compaction_threshold_percent")
        val AUTO_COMPACTION_THRESHOLD_TOKENS_K = intPreferencesKey("auto_compaction_threshold_tokens_k")
        val AUTO_COMPACTION_KEEP_RECENT_TOOL_CALLS = intPreferencesKey("auto_compaction_keep_recent_tool_calls")
        val CONTEXT_COMPACTION_TARGET_TOKENS_K = intPreferencesKey("context_compaction_target_tokens_k")
        val RESPONSE_STREAM_MAX_RETRIES = intPreferencesKey("response_stream_max_retries")

        // 提供商
        val PROVIDERS = stringPreferencesKey("providers")
        // IDs of built-in providers the user explicitly deleted; the re-seed pass
        // skips these so deletions are sticky across app restarts.
        val DELETED_BUILTIN_PROVIDER_IDS = stringPreferencesKey("deleted_builtin_provider_ids")
        // Names of bundled skills the user explicitly deleted; the seed pass skips these
        // (see Settings.deletedBundledSkills). Missing key -> emptySet(), no migration.
        val DELETED_BUNDLED_SKILLS = stringPreferencesKey("deleted_bundled_skills")

        // 助手
        val SELECT_ASSISTANT = stringPreferencesKey("select_assistant")
        val ASSISTANTS = stringPreferencesKey("assistants")
        val ASSISTANT_TAGS = stringPreferencesKey("assistant_tags")
        // 这两个字段此前只存在于 `Settings` 数据类里, 没有对应 key, 也就没进逐键读写管道:
        // 值只活在 settingsFlow 的内存快照中, 本进程内看着生效, 重启/升级后又回到默认值
        // (子智能体归档夹显示「不归入」; 默认技能播种标记归零). 由
        // SettingsPersistenceCoverageTest 守住这条不变量。
        val SUB_AGENT_ARCHIVE_FOLDERS = stringPreferencesKey("sub_agent_archive_folders")
        val AUTO_ENABLED_DEFAULT_SKILLS = stringPreferencesKey("auto_enabled_default_skills")
        /**

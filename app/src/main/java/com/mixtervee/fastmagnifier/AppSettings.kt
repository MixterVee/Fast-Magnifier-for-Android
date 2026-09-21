package com.mixtervee.fastmagnifier

import android.content.Context

class AppSettings(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        val OVERVIEW_DURATIONS_MS = longArrayOf(3500L, 4500L, 6000L)
        val AREA_ENHANCE_BOOSTS = floatArrayOf(1.25f, 1.45f, 1.65f)
        val SPEECH_RATES = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f)


        const val DEFAULT_OVERVIEW_INDEX = 1
        const val DEFAULT_AREA_ENHANCE_INDEX = 1
        const val DEFAULT_SPEECH_RATE_INDEX = 1
        const val DEFAULT_KEEP_SCREEN_AWAKE = false

        private const val PREFS_NAME = "fast_magnifier_settings"
        private const val KEY_OVERVIEW_INDEX = "overview_duration_index"
        private const val KEY_AREA_ENHANCE_INDEX = "area_enhance_index"
        private const val KEY_SPEECH_RATE_INDEX = "speech_rate_index"
        private const val KEY_KEEP_SCREEN_AWAKE = "keep_screen_awake"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var overviewIndex: Int
        get() = prefs.getInt(KEY_OVERVIEW_INDEX, DEFAULT_OVERVIEW_INDEX)
            .coerceIn(OVERVIEW_DURATIONS_MS.indices)
        set(value) {
            prefs.edit().putInt(KEY_OVERVIEW_INDEX, value.coerceIn(OVERVIEW_DURATIONS_MS.indices)).apply()
        }

    var areaEnhanceIndex: Int
        get() = prefs.getInt(KEY_AREA_ENHANCE_INDEX, DEFAULT_AREA_ENHANCE_INDEX)
            .coerceIn(AREA_ENHANCE_BOOSTS.indices)
        set(value) {
            prefs.edit().putInt(KEY_AREA_ENHANCE_INDEX, value.coerceIn(AREA_ENHANCE_BOOSTS.indices)).apply()
        }

    var speechRateIndex: Int
        get() = prefs.getInt(KEY_SPEECH_RATE_INDEX, DEFAULT_SPEECH_RATE_INDEX)
            .coerceIn(SPEECH_RATES.indices)
        set(value) {
            prefs.edit().putInt(KEY_SPEECH_RATE_INDEX, value.coerceIn(SPEECH_RATES.indices)).apply()
        }

    var keepScreenAwake: Boolean
        get() = prefs.getBoolean(KEY_KEEP_SCREEN_AWAKE, DEFAULT_KEEP_SCREEN_AWAKE)
        set(value) {
            prefs.edit().putBoolean(KEY_KEEP_SCREEN_AWAKE, value).apply()
        }

    val overviewDurationMs: Long
        get() = OVERVIEW_DURATIONS_MS[overviewIndex]

    val areaEnhanceBoost: Float
        get() = AREA_ENHANCE_BOOSTS[areaEnhanceIndex]

    val speechRate: Float
        get() = SPEECH_RATES[speechRateIndex]

    val overviewLabels: Array<String>
        get() = arrayOf(
            appContext.getString(R.string.overview_3_5),
            appContext.getString(R.string.overview_4_5),
            appContext.getString(R.string.overview_6)
        )

    val areaEnhanceLabels: Array<String>
        get() = arrayOf(
            appContext.getString(R.string.area_gentle),
            appContext.getString(R.string.area_normal),
            appContext.getString(R.string.area_strong)
        )

    val speechRateLabels: Array<String>
        get() = arrayOf(
            appContext.getString(R.string.speech_slow),
            appContext.getString(R.string.speech_normal),
            appContext.getString(R.string.speech_faster),
            appContext.getString(R.string.speech_fast)
        )

    val overviewLabel: String
        get() = overviewLabels[overviewIndex]

    val areaEnhanceLabel: String
        get() = areaEnhanceLabels[areaEnhanceIndex]

    val speechRateLabel: String
        get() = speechRateLabels[speechRateIndex]

    val keepScreenAwakeLabel: String
        get() = appContext.getString(if (keepScreenAwake) R.string.on else R.string.off)

    fun resetDefaults() {
        prefs.edit()
            .putInt(KEY_OVERVIEW_INDEX, DEFAULT_OVERVIEW_INDEX)
            .putInt(KEY_AREA_ENHANCE_INDEX, DEFAULT_AREA_ENHANCE_INDEX)
            .putInt(KEY_SPEECH_RATE_INDEX, DEFAULT_SPEECH_RATE_INDEX)
            .putBoolean(KEY_KEEP_SCREEN_AWAKE, DEFAULT_KEEP_SCREEN_AWAKE)
            .apply()
    }
}

package com.cloudvault.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.color.DynamicColors

enum class AppTheme(val id: String, val displayName: String, val icon: String) {
    SYSTEM_DEFAULT("system", "System Default / Material You", "🎨"),
    OBSIDIAN_DARK("obsidian", "Obsidian Dark (Default)", "🌌"),
    AMOLED_BLACK("amoled", "AMOLED Pure Black", "⬛"),
    LIGHT("light", "Light Mode", "☀️"),
    MIDNIGHT_PURPLE("midnight_purple", "Midnight Neon Purple", "🔮"),
    EMERALD_NORD("emerald_nord", "Emerald Forest Green", "🌲"),
    SUNSET_AMBER("sunset_amber", "Sunset Amber Gold", "🌅"),
    CRIMSON_ROSE("crimson_rose", "Dracula Crimson Rose", "🌹"),
    OCEAN_SAPPHIRE("ocean_sapphire", "Ocean Sapphire Blue", "🌊");

    companion object {
        fun fromId(id: String): AppTheme {
            return entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: SYSTEM_DEFAULT
        }
    }
}

object ThemePreferences {
    private const val PREFS_NAME = "cloudvault_theme_prefs"
    private const val KEY_THEME = "app_theme_mode"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTheme(context: Context): AppTheme {
        val id = getPrefs(context).getString(KEY_THEME, AppTheme.SYSTEM_DEFAULT.id) ?: AppTheme.SYSTEM_DEFAULT.id
        return AppTheme.fromId(id)
    }

    fun setTheme(context: Context, theme: AppTheme) {
        getPrefs(context).edit().putString(KEY_THEME, theme.id).apply()
    }

    fun applyThemeOnAppStart(app: CloudVaultApp) {
        val theme = getTheme(app)
        when (theme) {
            AppTheme.SYSTEM_DEFAULT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                if (DynamicColors.isDynamicColorAvailable()) {
                    DynamicColors.applyToActivitiesIfAvailable(app)
                }
            }
            AppTheme.LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
            else -> {
                // All dark variants
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            }
        }
    }

    fun applyThemeToActivity(activity: Activity) {
        val theme = getTheme(activity)
        when (theme) {
            AppTheme.SYSTEM_DEFAULT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                activity.setTheme(R.style.Theme_CloudVault)
                if (DynamicColors.isDynamicColorAvailable()) {
                    DynamicColors.applyIfAvailable(activity)
                }
            }
            AppTheme.AMOLED_BLACK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_Amoled)
            }
            AppTheme.LIGHT -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                activity.setTheme(R.style.Theme_CloudVault_Light)
            }
            AppTheme.MIDNIGHT_PURPLE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_MidnightPurple)
            }
            AppTheme.EMERALD_NORD -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_EmeraldNord)
            }
            AppTheme.SUNSET_AMBER -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_SunsetAmber)
            }
            AppTheme.CRIMSON_ROSE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_CrimsonRose)
            }
            AppTheme.OCEAN_SAPPHIRE -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault_OceanSapphire)
            }
            AppTheme.OBSIDIAN_DARK -> {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                activity.setTheme(R.style.Theme_CloudVault)
            }
        }
    }
}

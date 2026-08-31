package com.cloudvault.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.View
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
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

data class ThemePalette(
    val bgDark: Int,
    val bgSurface: Int,
    val bgSurfaceElevated: Int,
    val cardBorder: Int,
    val accentPrimary: Int,
    val accentBright: Int,
    val accentGlow: Int,
    val statusPillBg: Int,
    val statusPillBorder: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textMuted: Int,
    val isLight: Boolean = false
)

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

    fun getPalette(context: Context): ThemePalette {
        return when (getTheme(context)) {
            AppTheme.OBSIDIAN_DARK, AppTheme.SYSTEM_DEFAULT -> ThemePalette(
                bgDark = Color.parseColor("#080E18"),
                bgSurface = Color.parseColor("#0F172A"),
                bgSurfaceElevated = Color.parseColor("#162234"),
                cardBorder = Color.parseColor("#1E2C3F"),
                accentPrimary = Color.parseColor("#0284C7"),
                accentBright = Color.parseColor("#38BDF8"),
                accentGlow = Color.parseColor("#2638BDF8"),
                statusPillBg = Color.parseColor("#102A45"),
                statusPillBorder = Color.parseColor("#38BDF8"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#94A3B8"),
                textMuted = Color.parseColor("#64748B"),
                isLight = false
            )
            AppTheme.AMOLED_BLACK -> ThemePalette(
                bgDark = Color.parseColor("#000000"),
                bgSurface = Color.parseColor("#0A0A0A"),
                bgSurfaceElevated = Color.parseColor("#141414"),
                cardBorder = Color.parseColor("#222222"),
                accentPrimary = Color.parseColor("#38BDF8"),
                accentBright = Color.parseColor("#7DD3FC"),
                accentGlow = Color.parseColor("#2638BDF8"),
                statusPillBg = Color.parseColor("#141414"),
                statusPillBorder = Color.parseColor("#38BDF8"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#A1A1AA"),
                textMuted = Color.parseColor("#71717A"),
                isLight = false
            )
            AppTheme.LIGHT -> ThemePalette(
                bgDark = Color.parseColor("#F8FAFC"),
                bgSurface = Color.parseColor("#FFFFFF"),
                bgSurfaceElevated = Color.parseColor("#F1F5F9"),
                cardBorder = Color.parseColor("#E2E8F0"),
                accentPrimary = Color.parseColor("#0284C7"),
                accentBright = Color.parseColor("#0369A1"),
                accentGlow = Color.parseColor("#200284C7"),
                statusPillBg = Color.parseColor("#E0F2FE"),
                statusPillBorder = Color.parseColor("#7DD3FC"),
                textPrimary = Color.parseColor("#0F172A"),
                textSecondary = Color.parseColor("#475569"),
                textMuted = Color.parseColor("#94A3B8"),
                isLight = true
            )
            AppTheme.MIDNIGHT_PURPLE -> ThemePalette(
                bgDark = Color.parseColor("#0B0819"),
                bgSurface = Color.parseColor("#130F2A"),
                bgSurfaceElevated = Color.parseColor("#1C163D"),
                cardBorder = Color.parseColor("#2D235C"),
                accentPrimary = Color.parseColor("#9333EA"),
                accentBright = Color.parseColor("#C084FC"),
                accentGlow = Color.parseColor("#26C084FC"),
                statusPillBg = Color.parseColor("#241847"),
                statusPillBorder = Color.parseColor("#C084FC"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#C4B5FD"),
                textMuted = Color.parseColor("#8B5CF6"),
                isLight = false
            )
            AppTheme.EMERALD_NORD -> ThemePalette(
                bgDark = Color.parseColor("#051610"),
                bgSurface = Color.parseColor("#0A241B"),
                bgSurfaceElevated = Color.parseColor("#103528"),
                cardBorder = Color.parseColor("#1A4C3A"),
                accentPrimary = Color.parseColor("#059669"),
                accentBright = Color.parseColor("#34D399"),
                accentGlow = Color.parseColor("#2634D399"),
                statusPillBg = Color.parseColor("#0F382B"),
                statusPillBorder = Color.parseColor("#34D399"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#A7F3D0"),
                textMuted = Color.parseColor("#6EE7B7"),
                isLight = false
            )
            AppTheme.SUNSET_AMBER -> ThemePalette(
                bgDark = Color.parseColor("#140C06"),
                bgSurface = Color.parseColor("#20140A"),
                bgSurfaceElevated = Color.parseColor("#2D1C0F"),
                cardBorder = Color.parseColor("#422A17"),
                accentPrimary = Color.parseColor("#D97706"),
                accentBright = Color.parseColor("#FBBF24"),
                accentGlow = Color.parseColor("#26FBBF24"),
                statusPillBg = Color.parseColor("#331F0F"),
                statusPillBorder = Color.parseColor("#FBBF24"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#FDE68A"),
                textMuted = Color.parseColor("#F59E0B"),
                isLight = false
            )
            AppTheme.CRIMSON_ROSE -> ThemePalette(
                bgDark = Color.parseColor("#14070A"),
                bgSurface = Color.parseColor("#200C12"),
                bgSurfaceElevated = Color.parseColor("#2E121B"),
                cardBorder = Color.parseColor("#481B2B"),
                accentPrimary = Color.parseColor("#E11D48"),
                accentBright = Color.parseColor("#FB7185"),
                accentGlow = Color.parseColor("#26FB7185"),
                statusPillBg = Color.parseColor("#35111F"),
                statusPillBorder = Color.parseColor("#FB7185"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#FECDD3"),
                textMuted = Color.parseColor("#F43F5E"),
                isLight = false
            )
            AppTheme.OCEAN_SAPPHIRE -> ThemePalette(
                bgDark = Color.parseColor("#060D1E"),
                bgSurface = Color.parseColor("#0C1836"),
                bgSurfaceElevated = Color.parseColor("#142552"),
                cardBorder = Color.parseColor("#1F3879"),
                accentPrimary = Color.parseColor("#1D4ED8"),
                accentBright = Color.parseColor("#60A5FA"),
                accentGlow = Color.parseColor("#2660A5FA"),
                statusPillBg = Color.parseColor("#12275A"),
                statusPillBorder = Color.parseColor("#60A5FA"),
                textPrimary = Color.parseColor("#FFFFFF"),
                textSecondary = Color.parseColor("#BFDBFE"),
                textMuted = Color.parseColor("#3B82F6"),
                isLight = false
            )
        }
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

        val palette = getPalette(activity)
        activity.window.statusBarColor = palette.bgDark
        activity.window.navigationBarColor = palette.bgDark
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val flags = activity.window.decorView.systemUiVisibility
            activity.window.decorView.systemUiVisibility = if (palette.isLight) {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
        }
    }

    fun applyThemeToMainActivity(activity: Activity) {
        val palette = getPalette(activity)
        val decor = activity.window.decorView

        // 1. Window and Root Backgrounds
        activity.window.decorView.setBackgroundColor(palette.bgDark)
        decor.findViewById<View>(android.R.id.content)?.setBackgroundColor(palette.bgDark)
        decor.findViewById<View>(R.id.layoutMainRoot)?.setBackgroundColor(palette.bgDark)
        decor.findViewById<View>(R.id.layoutNormalTopBar)?.setBackgroundColor(palette.bgDark)
        decor.findViewById<View>(R.id.layoutSelectionTopBar)?.setBackgroundColor(palette.bgSurface)
        decor.findViewById<View>(R.id.rvMediaGrid)?.setBackgroundColor(palette.bgDark)

        // 2. Divider
        decor.findViewById<View>(R.id.dividerTabs)?.setBackgroundColor(palette.cardBorder)

        // 3. Status Banner
        decor.findViewById<MaterialCardView>(R.id.cardStatusBanner)?.let { card ->
            card.setCardBackgroundColor(palette.statusPillBg)
            card.strokeColor = palette.statusPillBorder
        }
        decor.findViewById<TextView>(R.id.tvStatus)?.setTextColor(palette.accentBright)

        // 4. Search input & Top Action Bar Buttons
        decor.findViewById<EditText>(R.id.etSearch)?.let { et ->
            et.setTextColor(palette.textPrimary)
            et.setHintTextColor(palette.textMuted)
        }
        decor.findViewById<MaterialButton>(R.id.btnSettings)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(palette.bgSurfaceElevated)
            btn.strokeColor = ColorStateList.valueOf(palette.cardBorder)
            btn.setTextColor(palette.textPrimary)
        }

        // 5. Action bar buttons
        decor.findViewById<MaterialButton>(R.id.btnSortFilter)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(palette.bgSurfaceElevated)
            btn.strokeColor = ColorStateList.valueOf(palette.cardBorder)
            btn.setTextColor(palette.textSecondary)
        }
        decor.findViewById<MaterialButton>(R.id.btnStartSelect)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(palette.bgSurfaceElevated)
            btn.strokeColor = ColorStateList.valueOf(palette.cardBorder)
            btn.setTextColor(palette.accentBright)
        }
        decor.findViewById<MaterialButton>(R.id.btnGridToggle)?.let { btn ->
            btn.backgroundTintList = ColorStateList.valueOf(palette.bgSurfaceElevated)
            btn.strokeColor = ColorStateList.valueOf(palette.cardBorder)
            btn.setTextColor(palette.textSecondary)
        }

        // 6. FAB Upload button
        decor.findViewById<MaterialButton>(R.id.fabUpload)?.let { fab ->
            fab.backgroundTintList = ColorStateList.valueOf(palette.accentPrimary)
        }

        // 7. Section Title
        decor.findViewById<TextView>(R.id.tvSectionTitle)?.setTextColor(palette.textPrimary)
    }
}

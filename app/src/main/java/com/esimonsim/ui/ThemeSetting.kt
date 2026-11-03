package com.esimonsim.ui

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.esimonsim.apdu.CardAccess

/** The app's light or dark look, under Settings › Theme. */
enum class ThemeChoice(val label: String) { PHONE("Same as phone"), LIGHT("Light"), DARK("Dark") }

/** Where the Theme setting is kept and how screens get it. Android 12 and newer keep an app's own night mode
 *  themselves ([UiModeManager.setApplicationNightMode]), from the splash screen on, so nothing is read at start there.
 *  Older versions get it as an override of the activity's configuration in attachBaseContext, before anything reads a
 *  resource; isSystemInDarkTheme, dialogs, sheets and the system bars' icons all go by that. MainActivity and
 *  AboutActivity only: the scanner is always dark. Kept in card access's prefs file, which every start reads anyway. */
object ThemeSetting {
    private const val KEY = "theme"
    @Volatile private var cached: ThemeChoice? = null

    fun get(context: Context): ThemeChoice = cached ?: read(context).also { cached = it }

    private fun prefs(context: Context) = context.getSharedPreferences(CardAccess.PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context) = prefs(context).getString(KEY, null)
        ?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } } ?: ThemeChoice.PHONE

    /** The line under [choice] in the Theme dialog. Android 9 and older have no dark mode of their own, but some
     *  phones' makers add one. */
    fun help(choice: ThemeChoice, sdk: Int = Build.VERSION.SDK_INT): String? = when {
        choice != ThemeChoice.PHONE -> null
        sdk >= 29 -> "Follows Dark theme in your phone's settings."
        else -> "Light, unless your phone has a dark mode of its own."
    }

    /** Saves [choice] and shows it: Android 12 and newer relaunch the app's screens themselves, older ones recreate
     *  this one, which keeps the view model and what's saved on screen. */
    fun set(activity: Activity, choice: ThemeChoice) {
        cached = choice
        prefs(activity).edit(commit = true) { if (choice == ThemeChoice.PHONE) remove(KEY) else putString(KEY, choice.name) }
        if (Build.VERSION.SDK_INT >= 31) tell(activity, choice) else activity.recreate()
    }

    /** Android 11 and older: what attachBaseContext applies, or null to follow the phone. */
    fun override(context: Context): Configuration? = if (Build.VERSION.SDK_INT >= 31) null else overrideFor(get(context))

    /** Only the night bits: the rest of an override configuration left undefined keeps the phone's. */
    internal fun overrideFor(choice: ThemeChoice): Configuration? = if (choice == ThemeChoice.PHONE) null else
        Configuration().apply {
            // Before Android 8 a new Configuration says font scale 1, which would undo the phone's text size.
            fontScale = 0f
            uiMode = if (choice == ThemeChoice.DARK) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }

    /** Android 12 and newer, off the main thread: hands Android a stored choice again, for a phone updated from 11,
     *  where only this app knew it. The same mode again changes nothing. */
    fun resync(context: Context) {
        if (Build.VERSION.SDK_INT >= 31) get(context).takeIf { it != ThemeChoice.PHONE }?.let { tell(context, it) }
    }

    /** Automatic takes the app's own mode away again, so the phone's applies. */
    internal fun nightMode(choice: ThemeChoice) = when (choice) {
        ThemeChoice.PHONE -> UiModeManager.MODE_NIGHT_AUTO
        ThemeChoice.LIGHT -> UiModeManager.MODE_NIGHT_NO
        ThemeChoice.DARK -> UiModeManager.MODE_NIGHT_YES
    }

    @RequiresApi(31)
    private fun tell(context: Context, choice: ThemeChoice) {
        runCatching { context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(nightMode(choice)) }
    }
}

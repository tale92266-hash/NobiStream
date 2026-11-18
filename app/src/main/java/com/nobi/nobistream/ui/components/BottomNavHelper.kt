package com.nobi.nobistream.ui.components

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.navigation.NavigationBarView
import com.nobi.nobistream.R
import com.nobi.nobistream.ui.home.MainActivity
import com.nobi.nobistream.ui.search.SearchActivity
import com.nobi.nobistream.ui.settings.SettingsActivity

/** The four bottom-nav destinations — the existing screens only. */
enum class NavTab { HOME, SEARCH, LIBRARY, SETTINGS }

/**
 * v8 bottom navigation wiring, shared by Main, Search and Settings.
 *
 * Selected tab: filled icon + accent tint + accent pill indicator.
 * Unselected tabs: outlined icon + secondary tint. Tab switches
 * haptic-tick (CLOCK_TICK). Cross-activity switches reuse the
 * existing activity (REORDER_TO_FRONT, no slide animation) so a tab
 * tap feels instant, never like a new screen opening.
 *
 * [onLocalTab] handles tabs the host activity owns itself (Main
 * owns HOME and LIBRARY as in-place containers): return true when
 * the tab was handled locally and no navigation should happen.
 */
object BottomNavHelper {

    private val ICONS = mapOf(
        R.id.nav_home to (R.drawable.ic_home to R.drawable.ic_home_filled),
        R.id.nav_search to (R.drawable.ic_search to R.drawable.ic_search_filled),
        R.id.nav_library to (R.drawable.ic_library to R.drawable.ic_library_filled),
        R.id.nav_settings to (R.drawable.ic_settings to R.drawable.ic_settings_filled)
    )
    private val TAB_FOR_ITEM = mapOf(
        R.id.nav_home to NavTab.HOME,
        R.id.nav_search to NavTab.SEARCH,
        R.id.nav_library to NavTab.LIBRARY,
        R.id.nav_settings to NavTab.SETTINGS
    )
    private val ITEM_FOR_TAB = mapOf(
        NavTab.HOME to R.id.nav_home,
        NavTab.SEARCH to R.id.nav_search,
        NavTab.LIBRARY to R.id.nav_library,
        NavTab.SETTINGS to R.id.nav_settings
    )

    fun setup(
        activity: AppCompatActivity,
        nav: NavigationBarView,
        selected: NavTab,
        onLocalTab: ((NavTab) -> Boolean)? = null
    ) {
        applyIcons(nav, selected)
        // Detach first: setting selectedItemId fires whatever
        // listener is already attached (from a previous setup
        // call), which would navigate while we are only syncing
        // the selection. Selection is set BEFORE the new listener.
        nav.setOnItemSelectedListener(null)
        nav.selectedItemId = ITEM_FOR_TAB.getValue(selected)
        nav.setOnItemSelectedListener { item ->
            val tab = TAB_FOR_ITEM[item.itemId]
                ?: return@setOnItemSelectedListener false
            if (tab == selected) return@setOnItemSelectedListener true
            // v9.1 #20: haptics through the shared helper.
            Haptics.tick(nav)
            if (onLocalTab?.invoke(tab) == true) {
                applyIcons(nav, tab)
                return@setOnItemSelectedListener true
            }
            navigate(activity, tab)
            true
        }
    }

    private fun applyIcons(nav: NavigationBarView, selected: NavTab) {
        val selectedItem = ITEM_FOR_TAB.getValue(selected)
        for ((itemId, pair) in ICONS) {
            val res = if (itemId == selectedItem) pair.second else pair.first
            nav.menu.findItem(itemId)?.icon =
                ContextCompat.getDrawable(nav.context, res)
        }
    }

    private fun navigate(activity: AppCompatActivity, tab: NavTab) {
        val intent = when (tab) {
            NavTab.HOME -> Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_HOME)
            NavTab.LIBRARY -> Intent(activity, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_LIBRARY)
            NavTab.SEARCH -> Intent(activity, SearchActivity::class.java)
            NavTab.SETTINGS -> Intent(activity, SettingsActivity::class.java)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        activity.startActivity(intent)
        activity.overridePendingTransition(0, 0)
    }
}

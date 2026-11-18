package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.BuildConfig
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.TelegramUser
import com.nobi.nobistream.databinding.ActivitySettingsBinding
import com.nobi.nobistream.ui.components.BottomNavHelper
import com.nobi.nobistream.ui.components.ConfirmSheet
import com.nobi.nobistream.ui.components.InfoSheet
import com.nobi.nobistream.ui.components.NavTab
import com.nobi.nobistream.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v3 Settings: grouped rows (Playback / Startup / Telegram / Storage /
 * Application), 56dp app bar with back, 64dp rows with icons, segmented
 * cache size, exposed-dropdown default filter, account info (avatar, name,
 * @username, phone — the user's own account, shown to themselves; no codes
 * or secrets anywhere), version, About + open-source licences, logout with
 * confirmation. No setting removed, no fake setting invented.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /**
         * v8: when true, Settings was opened standalone from Login
         * (not as a bottom-nav tab) — the bottom nav stays hidden
         * and the toolbar back button stays visible.
         */
        const val EXTRA_STANDALONE = "standalone"
    }

    private lateinit var binding: ActivitySettingsBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private val filterOptions = listOf("All", "480p", "720p", "1080p", "4K")

    // v6 subtitles: option lists (same hardcoded-list precedent as filterOptions).
    // v9.1 #26: background is now an opacity choice inside the same
    // dropdown ("Black 50%" / "Black 80%" / "Transparent") — no
    // separate opacity setting.
    private val subtitleSizeOptions = listOf("Small", "Medium", "Large")
    private val subtitleColorOptions = listOf("White", "Yellow", "Cyan")
    private val subtitleBackgroundOptions = listOf("Black 50%", "Black 80%", "Transparent")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // v8: Settings is a top-level bottom-nav tab — no back
        // button, bottom nav shown. EXCEPT standalone-from-Login:
        // no bottom nav, back button stays.
        if (intent.getBooleanExtra(EXTRA_STANDALONE, false)) {
            binding.bottomNav.root.visibility = View.GONE
            binding.toolbar.setNavigationOnClickListener { finish() }
        } else {
            binding.toolbar.navigationIcon = null
            BottomNavHelper.setup(this, binding.bottomNav.root, NavTab.SETTINGS)
        }
        binding.versionText.text =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        updateStatus()
        // v9.1 #26: one-shot migration — old background values map
        // onto the new opacity options ("Semi-black" → "Black 50%",
        // "Black" → "Black 80%"). New installs already store the
        // new values, so this runs once and then no-ops.
        lifecycleScope.launch {
            val stored = repository.settingsStore.subtitleBackground.first()
            val migrated = when (stored) {
                "Semi-black" -> "Black 50%"
                "Black" -> "Black 80%"
                else -> null
            }
            if (migrated != null) {
                repository.settingsStore.setSubtitleBackground(migrated)
            }
        }
        refreshCacheUsed()
        binding.saveApiButton.setOnClickListener { saveApi() }
        binding.clearSavedButton.setOnClickListener {
            locator.credentialsStore.clearSaved()
            updateStatus()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        binding.cacheSizeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val sizeMb = if (checkedId == R.id.cache1gb) 1000 else 500
            lifecycleScope.launch {
                repository.settingsStore.setCacheSizeMb(sizeMb)
            }
        }
        binding.clearCacheButton.setOnClickListener {
            // v8: Clear Cache confirms via a bottom sheet with a
            // destructive red CTA (not a raw dialog).
            ConfirmSheet.show(
                this,
                getString(R.string.v8_clear_cache_title),
                getString(R.string.v8_clear_cache_body),
                getString(R.string.v8_clear_cache_confirm)
            ) {
                lifecycleScope.launch(Dispatchers.IO) {
                    repository.clearCache()
                    withContext(Dispatchers.Main) {
                        refreshCacheUsed()
                        Toast.makeText(
                            this@SettingsActivity,
                            R.string.settings_cache_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        binding.autoplaySwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setAutoplay(checked)
            }
        }
        binding.openLastEpisodeSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setOpenLastEpisode(checked)
            }
        }
        binding.smartFilterSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setSmartFilter(checked)
            }
        }
        // v6 subtitles: always-show switch + style dropdowns.
        binding.subtitleAlwaysShowSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setSubtitleAlwaysShow(checked)
            }
        }
        val subtitleSizeAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown_dark,
            subtitleSizeOptions
        )
        binding.subtitleSizeInput.setAdapter(subtitleSizeAdapter)
        binding.subtitleSizeInput.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                repository.settingsStore.setSubtitleSize(subtitleSizeOptions[position])
            }
        }
        val subtitleColorAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown_dark,
            subtitleColorOptions
        )
        binding.subtitleColorInput.setAdapter(subtitleColorAdapter)
        binding.subtitleColorInput.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                repository.settingsStore.setSubtitleColor(subtitleColorOptions[position])
            }
        }
        val subtitleBackgroundAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown_dark,
            subtitleBackgroundOptions
        )
        binding.subtitleBackgroundInput.setAdapter(subtitleBackgroundAdapter)
        binding.subtitleBackgroundInput.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                repository.settingsStore.setSubtitleBackground(subtitleBackgroundOptions[position])
            }
        }
        val filterAdapter = ArrayAdapter(
            this,
            R.layout.item_dropdown_dark,
            filterOptions
        )
        binding.defaultFilterInput.setAdapter(filterAdapter)
        binding.defaultFilterInput.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                repository.settingsStore.setDefaultFilter(filterOptions[position])
            }
        }
        binding.licencesButton.setOnClickListener {
            // v8: licences text is an info bottom sheet (§4.9),
            // not a raw AlertDialog.
            // v9.1 #34: expandable sections, one per library.
            InfoSheet.showLicences(this)
        }
        binding.logoutButton.setOnClickListener {
            // v8: Logout confirms via a bottom sheet with a
            // destructive red CTA (not a raw dialog).
            ConfirmSheet.show(
                this,
                getString(R.string.v8_logout_title),
                getString(R.string.settings_logout_confirm),
                getString(R.string.v8_logout_confirm)
            ) { doLogout() }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.autoplay.collect { value ->
                    if (binding.autoplaySwitch.isChecked != value) {
                        binding.autoplaySwitch.isChecked = value
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.smartFilter.collect { value ->
                    if (binding.smartFilterSwitch.isChecked != value) {
                        binding.smartFilterSwitch.isChecked = value
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.subtitleAlwaysShow.collect { value ->
                    if (binding.subtitleAlwaysShowSwitch.isChecked != value) {
                        binding.subtitleAlwaysShowSwitch.isChecked = value
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.subtitleSize.collect { size ->
                    val current = binding.subtitleSizeInput.text?.toString().orEmpty()
                    if (current != size) {
                        binding.subtitleSizeInput.setText(size, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.subtitleColor.collect { color ->
                    val current = binding.subtitleColorInput.text?.toString().orEmpty()
                    if (current != color) {
                        binding.subtitleColorInput.setText(color, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.subtitleBackground.collect { background ->
                    val current = binding.subtitleBackgroundInput.text?.toString().orEmpty()
                    if (current != background) {
                        binding.subtitleBackgroundInput.setText(background, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.openLastEpisode.collect { value ->
                    if (binding.openLastEpisodeSwitch.isChecked != value) {
                        binding.openLastEpisodeSwitch.isChecked = value
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.cacheSizeMb.collect { sizeMb ->
                    val wantedId = if (sizeMb >= 1000) R.id.cache1gb else R.id.cache500
                    if (binding.cacheSizeGroup.checkedButtonId != wantedId) {
                        binding.cacheSizeGroup.check(wantedId)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.defaultFilter.collect { filter ->
                    val current = binding.defaultFilterInput.text?.toString().orEmpty()
                    if (current != filter) {
                        binding.defaultFilterInput.setText(filter, false)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.currentUser.collect { user -> updateAccount(user) }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.authState.collect { state ->
                    if (state == AuthState.LOGGED_OUT) {
                        updateAccount(null)
                    }
                }
            }
        }
    }

    private fun updateAccount(user: TelegramUser?) {
        if (user == null) {
            binding.accountName.setText(R.string.settings_account_not_signed_in)
            binding.accountStatus.setText(R.string.settings_account_offline)
            return
        }
        binding.accountName.text = user.displayName
        // v8: the account row shows the Telegram display name plus
        // the PHONE NUMBER first (then @username) — the phone is
        // what identifies the account; a bare @username is not.
        val details = listOfNotNull(
            user.phoneNumber?.takeIf { it.isNotBlank() },
            user.username?.takeIf { it.isNotBlank() }?.let { "@$it" }
        )
        binding.accountStatus.text = if (details.isEmpty()) {
            getString(R.string.settings_account_connected)
        } else {
            details.joinToString(" • ")
        }
    }

    override fun onResume() {
        super.onResume()
        // v9.1 #11: the cache-used bar can move while Settings is
        // open (a download finishes in the background) — refresh
        // on every return.
        refreshCacheUsed()
    }

    /**
     * v9.1 #11: cache-used bar — bytes actually used by the
     * media3 / ExoPlayer / image caches, against the selected
     * limit (500 MB / 1 GB). Read on IO (it walks the cache dirs).
     */
    private fun refreshCacheUsed() {
        lifecycleScope.launch(Dispatchers.IO) {
            val used = repository.getCacheUsedBytes()
            val limitMb = repository.settingsStore.cacheSizeMb.first()
            withContext(Dispatchers.Main) {
                val limitBytes = limitMb * 1024L * 1024L
                binding.cacheUsedBar.max = 100
                binding.cacheUsedBar.progress =
                    if (limitBytes > 0) ((used * 100L) / limitBytes).toInt().coerceIn(0, 100)
                    else 0
                binding.cacheUsedText.text = getString(
                    R.string.settings_cache_used,
                    com.nobi.nobistream.core.FormatUtils.formatSize(used),
                    com.nobi.nobistream.core.FormatUtils.formatSize(limitBytes)
                )
            }
        }
    }

    private fun updateStatus() {
        val credentials = locator.credentialsStore
        binding.apiStatus.setText(
            when {
                credentials.hasSaved -> R.string.settings_status_saved
                credentials.hasBuiltIn -> R.string.settings_status_builtin
                else -> R.string.settings_status_missing
            }
        )
        binding.clearSavedButton.visibility =
            if (credentials.hasSaved && credentials.hasBuiltIn) View.VISIBLE else View.GONE
    }

    private fun saveApi() {
        val idText = binding.apiIdEdit.text?.toString()?.trim().orEmpty()
        val hash = binding.apiHashEdit.text?.toString()?.trim().orEmpty()
        val id = idText.toIntOrNull()
        if (id == null || id == 0 || hash.length < 10) {
            Toast.makeText(this, R.string.common_error_generic, Toast.LENGTH_LONG).show()
            return
        }
        locator.credentialsStore.save(id, hash)
        binding.apiIdEdit.text?.clear()
        binding.apiHashEdit.text?.clear()
        updateStatus()
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
    }

    private fun doLogout() {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.logout()
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SettingsActivity, LoginActivity::class.java))
                finish()
            }
        }
    }
}

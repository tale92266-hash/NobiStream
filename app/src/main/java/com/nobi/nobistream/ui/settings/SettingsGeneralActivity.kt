package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
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
import com.nobi.nobistream.core.FormatUtils
import com.nobi.nobistream.core.TelegramUser
import com.nobi.nobistream.databinding.ActivitySettingsGeneralBinding
import com.nobi.nobistream.ui.components.ConfirmSheet
import com.nobi.nobistream.ui.components.InfoSheet
import com.nobi.nobistream.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v9.9 General settings sub-screen: Account (row + Log out +
 * "Reset saved credentials" — visible only when saved credentials
 * exist), Startup, Storage, Application. Same controls, behaviour
 * and defaults as the old single Settings screen; only the address
 * changed. No API field anywhere.
 */
class SettingsGeneralActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsGeneralBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsGeneralBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.versionText.text =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        updateResetVisibility()
        refreshCacheUsed()
        binding.resetSavedButton.setOnClickListener {
            // v9.9: the old API section's one useful function — go
            // back to the built-in credentials. Confirm sheet, then
            // the existing clearSaved(); no credential value is ever
            // displayed or logged.
            ConfirmSheet.show(
                this,
                getString(R.string.settings_reset_credentials_title),
                getString(R.string.settings_reset_credentials_body),
                getString(R.string.settings_reset_credentials)
            ) {
                locator.credentialsStore.clearSaved()
                updateResetVisibility()
                Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            }
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
                            this@SettingsGeneralActivity,
                            R.string.settings_cache_cleared,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        binding.openLastEpisodeSwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setOpenLastEpisode(checked)
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
            binding.accountLetter.text = ""
            binding.accountName.setText(R.string.settings_account_not_signed_in)
            binding.accountStatus.setText(R.string.settings_account_offline)
            return
        }
        binding.accountLetter.text = user.displayName.firstOrNull()?.toString().orEmpty()
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
                    FormatUtils.formatSize(used),
                    FormatUtils.formatSize(limitBytes)
                )
            }
        }
    }

    /** v9.9: the reset row exists only when saved credentials exist
     * AND built-ins exist to fall back to — the same condition as
     * the old "Use built-in credentials instead" button. */
    private fun updateResetVisibility() {
        val credentials = locator.credentialsStore
        binding.resetSavedButton.visibility =
            if (credentials.hasSaved && credentials.hasBuiltIn) View.VISIBLE else View.GONE
    }

    private fun doLogout() {
        lifecycleScope.launch(Dispatchers.IO) {
            repository.logout()
            withContext(Dispatchers.Main) {
                startActivity(Intent(this@SettingsGeneralActivity, LoginActivity::class.java))
                finish()
            }
        }
    }
}

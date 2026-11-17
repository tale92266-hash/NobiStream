package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
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
import com.nobi.nobistream.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
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

    private lateinit var binding: ActivitySettingsBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private val filterOptions = listOf("All", "480p", "720p", "1080p", "4K")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.versionText.text =
            "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        updateStatus()
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
            lifecycleScope.launch(Dispatchers.IO) {
                repository.clearCache()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@SettingsActivity,
                        R.string.settings_cache_cleared,
                        Toast.LENGTH_SHORT
                    ).show()
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
        val filterAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            filterOptions
        )
        binding.defaultFilterInput.setAdapter(filterAdapter)
        binding.defaultFilterInput.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                repository.settingsStore.setDefaultFilter(filterOptions[position])
            }
        }
        binding.licencesButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_licences)
                .setMessage(R.string.settings_licences_body)
                .setPositiveButton(R.string.common_ok, null)
                .show()
        }
        binding.logoutButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setMessage(R.string.settings_logout_confirm)
                .setPositiveButton(R.string.common_ok) { _, _ -> doLogout() }
                .setNegativeButton(R.string.home_cancel, null)
                .show()
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
        val details = listOfNotNull(
            user.username?.takeIf { it.isNotBlank() }?.let { "@$it" },
            user.phoneNumber?.takeIf { it.isNotBlank() }
        )
        binding.accountStatus.text = if (details.isEmpty()) {
            getString(R.string.settings_account_connected)
        } else {
            details.joinToString(" • ")
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

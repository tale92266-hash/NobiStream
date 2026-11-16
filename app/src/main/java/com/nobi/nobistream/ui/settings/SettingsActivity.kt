package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.databinding.ActivitySettingsBinding
import com.nobi.nobistream.ui.login.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings: API credentials, cache, playback, account. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private val filterOptions = listOf("All", "480p", "720p", "1080p", "4K")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        updateStatus()
        binding.saveApiButton.setOnClickListener { saveApi() }
        binding.clearSavedButton.setOnClickListener {
            locator.credentialsStore.clearSaved()
            updateStatus()
            Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
        binding.cacheSizeGroup.setOnCheckedChangeListener { _, checkedId ->
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
        val spinnerAdapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            filterOptions
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        binding.defaultFilterSpinner.adapter = spinnerAdapter
        binding.defaultFilterSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    lifecycleScope.launch {
                        repository.settingsStore.setDefaultFilter(filterOptions[position])
                    }
                }

                override fun onNothingSelected(parent: AdapterView<*>) = Unit
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
                    if (binding.cacheSizeGroup.checkedRadioButtonId != wantedId) {
                        binding.cacheSizeGroup.check(wantedId)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.settingsStore.defaultFilter.collect { filter ->
                    val position = filterOptions.indexOf(filter).takeIf { it >= 0 } ?: 0
                    if (binding.defaultFilterSpinner.selectedItemPosition != position) {
                        binding.defaultFilterSpinner.setSelection(position)
                    }
                }
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

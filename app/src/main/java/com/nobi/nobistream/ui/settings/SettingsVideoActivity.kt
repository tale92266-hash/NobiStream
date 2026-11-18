package com.nobi.nobistream.ui.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.databinding.ActivitySettingsVideoBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v9.9 Video Settings sub-screen: Playback (Autoplay, Smart Filter,
 * Default quality filter, Preferred quality — NEW) + Subtitles
 * (always-show, size, colour, background + burned-in note). Same
 * controls, behaviour and defaults as the old single Settings
 * screen; only the address changed. A dropdown pick applies
 * immediately with a short "Saved" toast; no restart, no re-login.
 */
class SettingsVideoActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsVideoBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private val filterOptions = listOf("All", "480p", "720p", "1080p", "4K")

    // v9.9 Preferred quality: Highest available (default — today's
    // behaviour, no cap) caps the copy Smart Filter picks; 4K / 1080p
    // / 720p / 480p pick the best copy at or below that quality.
    private val preferredQualityOptions =
        listOf("Highest available", "4K", "1080p", "720p", "480p")

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
        binding = ActivitySettingsVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
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
        binding.autoplaySwitch.setOnCheckedChangeListener { _, checked ->
            lifecycleScope.launch {
                repository.settingsStore.setAutoplay(checked)
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
        wireDropdown(
            binding.subtitleSizeInput, subtitleSizeOptions
        ) { repository.settingsStore.setSubtitleSize(it) }
        wireDropdown(
            binding.subtitleColorInput, subtitleColorOptions
        ) { repository.settingsStore.setSubtitleColor(it) }
        wireDropdown(
            binding.subtitleBackgroundInput, subtitleBackgroundOptions
        ) { repository.settingsStore.setSubtitleBackground(it) }
        wireDropdown(
            binding.defaultFilterInput, filterOptions
        ) { repository.settingsStore.setDefaultFilter(it) }
        wireDropdown(
            binding.preferredQualityInput, preferredQualityOptions
        ) { repository.settingsStore.setPreferredQuality(it) }
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
                repository.settingsStore.preferredQuality.collect { quality ->
                    val current = binding.preferredQualityInput.text?.toString().orEmpty()
                    if (current != quality) {
                        binding.preferredQualityInput.setText(quality, false)
                    }
                }
            }
        }
    }

    /**
     * One exposed dropdown: adapter + pick → persist + short "Saved"
     * toast. The field updates immediately from the DataStore collect
     * above; no restart.
     */
    private fun wireDropdown(
        input: android.widget.AutoCompleteTextView,
        options: List<String>,
        save: suspend (String) -> Unit
    ) {
        input.setAdapter(ArrayAdapter(this, R.layout.item_dropdown_dark, options))
        input.setOnItemClickListener { _, _, position, _ ->
            lifecycleScope.launch {
                save(options[position])
                Toast.makeText(
                    this@SettingsVideoActivity,
                    R.string.settings_saved,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}

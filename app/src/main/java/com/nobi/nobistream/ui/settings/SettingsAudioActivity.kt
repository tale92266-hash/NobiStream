package com.nobi.nobistream.ui.settings

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.nobi.nobistream.databinding.ActivitySettingsAudioBinding

/**
 * v9.9 Audio Settings sub-screen: one read-only "Supported audio"
 * card (Dolby Digital, Dolby Digital Plus, Dolby TrueHD, DTS, AAC,
 * FLAC, Opus, MP3 — each decoded in the app — plus the honest
 * stereo-downmix note). Information, not a setting: there is no
 * toggle to invent here.
 */
class SettingsAudioActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsAudioBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsAudioBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
    }
}

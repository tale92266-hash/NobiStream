package com.nobi.nobistream.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.core.TelegramUser
import com.nobi.nobistream.databinding.ActivitySettingsBinding
import com.nobi.nobistream.ui.components.BottomNavHelper
import com.nobi.nobistream.ui.components.NavTab
import kotlinx.coroutines.launch

/**
 * v9.9 Settings HUB: account card + three navigation rows (General /
 * Video Settings / Audio Settings). This activity owns NO controls
 * any more — every setting lives on its own sub-screen, with the same
 * behaviour, persistence and defaults as the old single screen.
 *
 * No API field exists anywhere in the new Settings UI — the api_id /
 * api_hash fields, Save button and status line are deleted. Saved
 * credentials are still honoured by ApiCredentialsStore, and the one
 * useful function of the old API section (going back to the built-in
 * credentials) lives on as the "Reset saved credentials" row in
 * General, shown only when saved credentials exist.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        /**
         * v8: when true, Settings was opened standalone from Login
         * (not as a bottom-nav tab) — the bottom nav stays hidden
         * and the toolbar back button stays visible. Dormant in
         * v9.9: Login no longer opens Settings (API entry lives
         * inside the login flow), but the mode stays for any
         * standalone opening that remains.
         */
        const val EXTRA_STANDALONE = "standalone"
    }

    private lateinit var binding: ActivitySettingsBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // v8: Settings is a top-level bottom-nav tab — no back
        // button, bottom nav shown. EXCEPT standalone: no bottom
        // nav, back button stays.
        if (intent.getBooleanExtra(EXTRA_STANDALONE, false)) {
            binding.bottomNav.root.visibility = View.GONE
            binding.toolbar.setNavigationOnClickListener { finish() }
        } else {
            binding.toolbar.navigationIcon = null
            BottomNavHelper.setup(this, binding.bottomNav.root, NavTab.SETTINGS)
        }
        binding.generalRow.setOnClickListener {
            startActivity(Intent(this, SettingsGeneralActivity::class.java))
        }
        binding.videoRow.setOnClickListener {
            startActivity(Intent(this, SettingsVideoActivity::class.java))
        }
        binding.audioRow.setOnClickListener {
            startActivity(Intent(this, SettingsAudioActivity::class.java))
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
}

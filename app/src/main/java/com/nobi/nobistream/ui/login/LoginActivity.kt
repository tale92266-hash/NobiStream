package com.nobi.nobistream.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.databinding.ActivityLoginBinding
import com.nobi.nobistream.ui.home.MainActivity
import com.nobi.nobistream.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Launcher screen: Telegram phone number -> code -> 2FA password. */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private var starting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.continueButton.setOnClickListener { sendPhone() }
        binding.verifyButton.setOnClickListener { sendCode() }
        binding.passwordButton.setOnClickListener { sendPassword() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.authState.collect { render(it) }
            }
        }
        maybeStart()
    }

    override fun onResume() {
        super.onResume()
        maybeStart()
    }

    private fun maybeStart() {
        if (starting) return
        if (!locator.credentialsStore.hasCredentials()) {
            binding.apiNeededCard.visibility = View.VISIBLE
            return
        }
        binding.apiNeededCard.visibility = View.GONE
        starting = true
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = repository.ensureStarted()
            withContext(Dispatchers.Main) {
                binding.progressBar.visibility = View.GONE
                if (!ok) {
                    starting = false
                    showError(getString(R.string.login_error))
                }
            }
        }
    }

    private fun render(state: AuthState) {
        when (state) {
            AuthState.READY -> {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            AuthState.NEED_PHONE -> showStep(phone = true, code = false, password = false)
            AuthState.NEED_CODE -> showStep(phone = false, code = true, password = false)
            AuthState.NEED_PASSWORD -> showStep(phone = false, code = false, password = true)
            AuthState.NEED_API_KEYS, AuthState.LOGGED_OUT ->
                showStep(phone = true, code = false, password = false)
            AuthState.UNKNOWN -> Unit
        }
    }

    private fun showStep(phone: Boolean, code: Boolean, password: Boolean) {
        binding.progressBar.visibility = View.GONE
        binding.phoneLayout.visibility = if (phone) View.VISIBLE else View.GONE
        binding.continueButton.visibility = if (phone) View.VISIBLE else View.GONE
        binding.codeInputLayout.visibility = if (code) View.VISIBLE else View.GONE
        binding.verifyButton.visibility = if (code) View.VISIBLE else View.GONE
        binding.passwordInputLayout.visibility = if (password) View.VISIBLE else View.GONE
        binding.passwordButton.visibility = if (password) View.VISIBLE else View.GONE
    }

    private fun sendPhone() {
        val digits = binding.phoneEdit.text?.toString()?.trim().orEmpty()
        if (digits.length < 10) {
            showError("Enter a valid phone number.")
            return
        }
        val phone = if (digits.startsWith("+")) digits else "+91$digits"
        runTelegramCall { repository.sendPhoneNumber(phone) }
    }

    private fun sendCode() {
        val code = binding.codeEdit.text?.toString()?.trim().orEmpty()
        if (code.isEmpty()) {
            showError("Enter the verification code.")
            return
        }
        runTelegramCall { repository.sendCode(code) }
    }

    private fun sendPassword() {
        val password = binding.passwordEdit.text?.toString().orEmpty()
        if (password.isEmpty()) {
            showError("Enter your two-step verification password.")
            return
        }
        runTelegramCall { repository.sendPassword(password) }
    }

    private fun runTelegramCall(block: suspend () -> Unit) {
        hideError()
        binding.progressBar.visibility = View.VISIBLE
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                block()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    showError(e.message ?: getString(R.string.login_error))
                }
            }
        }
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorText.visibility = View.GONE
    }
}

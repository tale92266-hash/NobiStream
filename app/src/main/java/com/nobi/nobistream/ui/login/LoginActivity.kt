package com.nobi.nobistream.ui.login

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import androidx.activity.enableEdgeToEdge
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

/**
 * v8 Login: Telegram phone number -> code -> 2FA password, with a
 * 3-step stepper (Phone → Code → Password), a +91 chip, a resend-code
 * button with a 30s countdown, and a shake on a wrong code.
 *
 * NOTE: this is no longer the launcher — LauncherActivity owns the
 * cold-start session check (spec §7, login-flash fix) and only
 * routes here when there is no signed-in session. The auth redirect
 * in MainActivity stays as a backstop.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val locator get() = (application as NobiStreamApplication).serviceLocator
    private val repository get() = locator.repository

    private var starting = false

    /** v8: resend-code cooldown (30s, Telegram's own pace). */
    private var resendTimer: CountDownTimer? = null
    private var resendSecondsLeft = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.openSettingsButton.setOnClickListener {
            // v8: opened standalone from Login (no signed-in session)
            // — Settings hides the bottom nav and shows its back
            // button instead of the tab bar.
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.EXTRA_STANDALONE, true)
            )
        }
        binding.continueButton.setOnClickListener { sendPhone() }
        binding.verifyButton.setOnClickListener { sendCode() }
        binding.passwordButton.setOnClickListener { sendPassword() }
        binding.resendCodeButton.setOnClickListener { resendCode() }
        // Verification step: back to the phone step (change number).
        // The code length is never hardcoded — any code Telegram sent works,
        // and paste works through the standard EditText long-press menu.
        binding.changeNumberButton.setOnClickListener {
            stopResendCountdown()
            showStep(phone = true, code = false, password = false)
        }
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

    override fun onDestroy() {
        resendTimer?.cancel()
        resendTimer = null
        super.onDestroy()
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
                stopResendCountdown()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
            AuthState.NEED_PHONE -> showStep(phone = true, code = false, password = false)
            AuthState.NEED_CODE -> {
                showStep(phone = false, code = true, password = false)
                // A fresh code was just sent — start the cooldown so
                // the button can't be hammered.
                if (resendSecondsLeft <= 0) startResendCountdown()
            }
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
        binding.codeActions.visibility = if (code) View.VISIBLE else View.GONE
        binding.passwordInputLayout.visibility = if (password) View.VISIBLE else View.GONE
        binding.passwordButton.visibility = if (password) View.VISIBLE else View.GONE
        setStep(when {
            password -> 2
            code -> 1
            else -> 0
        })
    }

    /** v8 stepper: steps before the active one are done, the active
     * one is accent, later ones stay muted. Numbers never change. */
    private fun setStep(activeIndex: Int) {
        val circles = listOf(
            binding.stepCirclePhone to binding.stepLabelPhone,
            binding.stepCircleCode to binding.stepLabelCode,
            binding.stepCirclePassword to binding.stepLabelPassword
        )
        circles.forEachIndexed { index, (circle, label) ->
            val doneOrActive = index <= activeIndex
            circle.setBackgroundResource(
                if (doneOrActive) R.drawable.bg_step_circle_active
                else R.drawable.bg_step_circle
            )
            label.setTextColor(
                getColor(
                    if (doneOrActive) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    // ---- v8 resend-code countdown (30s) ----

    private fun startResendCountdown() {
        resendTimer?.cancel()
        resendSecondsLeft = 30
        binding.resendCodeButton.isEnabled = false
        binding.resendCodeButton.text =
            getString(R.string.login_resend_countdown, resendSecondsLeft)
        resendTimer = object : CountDownTimer(30_000L, 1_000L) {
            override fun onTick(millisUntilFinished: Long) {
                resendSecondsLeft = (millisUntilFinished / 1_000L).toInt()
                binding.resendCodeButton.text =
                    getString(R.string.login_resend_countdown, resendSecondsLeft)
            }

            override fun onFinish() {
                resendSecondsLeft = 0
                binding.resendCodeButton.isEnabled = true
                binding.resendCodeButton.text = getString(R.string.login_resend)
            }
        }.start()
    }

    private fun stopResendCountdown() {
        resendTimer?.cancel()
        resendTimer = null
        resendSecondsLeft = 0
        binding.resendCodeButton.isEnabled = true
        binding.resendCodeButton.text = getString(R.string.login_resend)
    }

    /** v8: ask Telegram to send the login code again (new
     * TelegramGateway.resendCode → TdApi.ResendAuthenticationCode). */
    private fun resendCode() {
        if (resendSecondsLeft > 0) return
        hideError()
        binding.resendCodeButton.isEnabled = false
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = runCatching { repository.resendCode() }.isSuccess
            withContext(Dispatchers.Main) {
                if (ok) {
                    startResendCountdown()
                } else {
                    binding.resendCodeButton.isEnabled = true
                    // Generic message only — never a TDLib error string.
                    showError(getString(R.string.login_resend_failed))
                }
            }
        }
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
        // v8: a wrong code gets the friendly string + a shake of the
        // code field — never TDLib's raw "PHONE_CODE_INVALID".
        runTelegramCall(
            block = { repository.sendCode(code) },
            errorText = { getString(R.string.login_code_wrong) },
            onError = { shakeCodeField() }
        )
    }

    private fun sendPassword() {
        val password = binding.passwordEdit.text?.toString().orEmpty()
        if (password.isEmpty()) {
            showError("Enter your two-step verification password.")
            return
        }
        runTelegramCall { repository.sendPassword(password) }
    }

    /** v8: short horizontal shake on the code field. */
    private fun shakeCodeField() {
        val shake = ObjectAnimator.ofFloat(
            binding.codeInputLayout, "translationX",
            0f, -12f, 12f, -8f, 8f, 0f
        )
        shake.duration = 400
        shake.start()
    }

    private fun runTelegramCall(
        block: suspend () -> Unit,
        errorText: (Exception) -> String = { e ->
            e.message ?: getString(R.string.login_error)
        },
        onError: (() -> Unit)? = null
    ) {
        hideError()
        binding.progressBar.visibility = View.VISIBLE
        // No double submission while a call is in flight.
        setActionButtonsEnabled(false)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                block()
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    setActionButtonsEnabled(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    setActionButtonsEnabled(true)
                    showError(errorText(e))
                    onError?.invoke()
                }
            }
        }
    }

    private fun setActionButtonsEnabled(enabled: Boolean) {
        binding.continueButton.isEnabled = enabled
        binding.verifyButton.isEnabled = enabled
        binding.passwordButton.isEnabled = enabled
        binding.changeNumberButton.isEnabled = enabled
        binding.resendCodeButton.isEnabled = enabled && resendSecondsLeft <= 0
    }

    private fun showError(message: String) {
        binding.errorText.text = message
        binding.errorText.visibility = View.VISIBLE
    }

    private fun hideError() {
        binding.errorText.visibility = View.GONE
    }
}

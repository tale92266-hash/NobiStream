package com.nobi.nobistream.ui.login

import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.CountDownTimer
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nobi.nobistream.NobiStreamApplication
import com.nobi.nobistream.R
import com.nobi.nobistream.core.AuthState
import com.nobi.nobistream.databinding.ActivityLoginBinding
import android.widget.Toast
import com.nobi.nobistream.ui.home.MainActivity
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

    /** v9.1 #2: the six OTP boxes (lazy — the binding exists from onCreate). */
    private val otpBoxes: List<EditText> by lazy {
        listOf(
            binding.otpBox0, binding.otpBox1, binding.otpBox2,
            binding.otpBox3, binding.otpBox4, binding.otpBox5
        )
    }
    /** Guard against TextWatcher recursion while distributing a paste. */
    private var otpFilling = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // v9.9 (§4.3): no Settings button on Login any more — when
        // no credentials exist at all, the api_id / api_hash fields
        // inside the apiNeededCard above are the first step.
        binding.saveApiButton.setOnClickListener { saveApi() }
        binding.continueButton.setOnClickListener { sendPhone() }
        binding.verifyButton.setOnClickListener { sendCode() }
        binding.passwordButton.setOnClickListener { sendPassword() }
        binding.resendCodeButton.setOnClickListener { resendCode() }
        // v9.1 #2: six OTP boxes — type flows forward, DEL on an
        // empty box goes back, paste distributes the digits.
        wireOtpBoxes()
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
            // v9.9: no credentials at all — the API entry card is the
            // first step; the phone step stays hidden until the
            // credentials are saved (sending a phone number with no
            // TDLib client would only error).
            binding.apiNeededCard.visibility = View.VISIBLE
            binding.phoneLayout.visibility = View.GONE
            binding.continueButton.visibility = View.GONE
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
            AuthState.NEED_API_KEYS ->
                // No credentials at all: the API entry card is the
                // first step, not the phone step.
                maybeStart()
            AuthState.LOGGED_OUT ->
                showStep(phone = true, code = false, password = false)
            AuthState.UNKNOWN -> Unit
        }
    }

    private fun showStep(phone: Boolean, code: Boolean, password: Boolean) {
        binding.progressBar.visibility = View.GONE
        binding.phoneLayout.visibility = if (phone) View.VISIBLE else View.GONE
        binding.continueButton.visibility = if (phone) View.VISIBLE else View.GONE
        binding.otpBoxesContainer.visibility = if (code) View.VISIBLE else View.GONE
        binding.verifyButton.visibility = if (code) View.VISIBLE else View.GONE
        binding.codeActions.visibility = if (code) View.VISIBLE else View.GONE
        binding.passwordInputLayout.visibility = if (password) View.VISIBLE else View.GONE
        binding.passwordButton.visibility = if (password) View.VISIBLE else View.GONE
        if (code) {
            // Fresh code step: focus the first box (empty or not —
            // the user may be re-typing after a wrong code).
            binding.otpBox0.requestFocus()
        }
        setStep(when {
            password -> 2
            code -> 1
            else -> 0
        })
    }

    /** v8 stepper: steps before the active one are done, the active
     * one is accent, later ones stay muted. Numbers never change.
     * v9.1 #2: a DONE step shows a check ("✓") instead of its
     * number, so the stepper reads as progress, not a static list. */
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
            circle.text = when {
                index < activeIndex -> "✓"
                else -> (index + 1).toString()
            }
            label.setTextColor(
                getColor(
                    if (doneOrActive) R.color.text_primary else R.color.text_secondary
                )
            )
        }
    }

    /**
     * v9.1 #2: six OTP boxes.
     * - Typing a digit advances to the next box.
     * - Pasting (or typing fast into one box) distributes the
     *   digits from the current box onward.
     * - DEL on an empty box moves back and clears the previous box.
     * - DONE on the last box submits.
     * No maxLength on the boxes — the watcher handles multi-digit
     * input, so a paste is never clipped. The code length is never
     * hardcoded: otpCode() joins whatever boxes are filled (a
     * 5-digit Telegram code leaves the last box empty, and works).
     */
    private fun wireOtpBoxes() {
        otpBoxes.forEachIndexed { index, box ->
            box.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (otpFilling) return
                    val text = s?.toString().orEmpty()
                    if (text.length > 1) {
                        // Paste / fast typing: distribute the digits.
                        val digits = text.filter { it.isDigit() }
                        otpFilling = true
                        for (j in digits.indices) {
                            val target = index + j
                            if (target < otpBoxes.size) {
                                otpBoxes[target].setText(digits[j].toString())
                            }
                        }
                        otpFilling = false
                        val nextEmpty = otpBoxes.indexOfFirst { it.text.isNullOrEmpty() }
                        val focusIndex = if (nextEmpty >= 0) nextEmpty else otpBoxes.lastIndex
                        otpBoxes[focusIndex].requestFocus()
                        otpBoxes[focusIndex].setSelection(
                            otpBoxes[focusIndex].text?.length ?: 0
                        )
                    } else if (text.length == 1) {
                        if (index + 1 < otpBoxes.size) {
                            otpBoxes[index + 1].requestFocus()
                        }
                    }
                }
            })
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_DEL &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    box.text.isNullOrEmpty() && index > 0
                ) {
                    otpBoxes[index - 1].setText("")
                    otpBoxes[index - 1].requestFocus()
                    true
                } else {
                    false
                }
            }
        }
        otpBoxes.last().setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                sendCode()
                true
            } else {
                false
            }
        }
    }

    /** v9.1 #2: the joined OTP — whatever boxes are filled, in order. */
    private fun otpCode(): String {
        return otpBoxes.joinToString("") { it.text?.toString().orEmpty() }
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

    /** v9.9 (§4.3): save the API credentials entered in the login
     * flow — same validation as the old Settings save, same
     * encrypted store. No credential value is ever displayed or
     * logged; the fields are cleared right after saving. */
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
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        maybeStart()
    }

    private fun sendPhone() {
        val digits = binding.phoneEdit.text?.toString()?.trim().orEmpty()
        if (digits.length < 10) {
            showError("Enter a valid phone number.")
            return
        }
        val phone = if (digits.startsWith("+")) digits else "+91$digits"
        runTelegramCall(block = { repository.sendPhoneNumber(phone) })
    }

    private fun sendCode() {
        val code = otpCode()
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
        runTelegramCall(block = { repository.sendPassword(password) })
    }

    /** v8: short horizontal shake on the code field (v9.1: the OTP box row). */
    private fun shakeCodeField() {
        val shake = ObjectAnimator.ofFloat(
            binding.otpBoxesContainer, "translationX",
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

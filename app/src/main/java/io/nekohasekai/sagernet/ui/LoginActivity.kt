package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.banshan.BanShanSession
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutLoginBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The mandatory sign-in (the desktop's LoginWindow): the account is checked against the BanShan login server, the
 * subscription it answers with is imported into its own protected group, and the app opens. [MainActivity] sends the
 * user here while no completed login is stored, and the group itself is not editable, deletable or shareable.
 */
class LoginActivity : ThemedActivity() {

    companion object {
        /** Opened from the account entry: the form is shown even though a login is stored. */
        const val EXTRA_RELOGIN = "relogin"
    }

    private lateinit var binding: LayoutLoginBinding
    private var loginJob: Job? = null
    private var busy = false

    /** The account entry, with a stored login: sign in again or switch to another account. */
    private var reLogin = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        reLogin = intent.getBooleanExtra(EXTRA_RELOGIN, false) && BanShanSession.isLoggedIn()
        if (!reLogin && BanShanSession.isLoggedIn()) {
            enterApp()
            return
        }

        binding = LayoutLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        if (reLogin) setUpReLogin()

        if (DataStore.banshanUsername.isBlank()) {
            binding.username.requestFocus()
        } else {
            binding.username.setText(DataStore.banshanUsername)
            binding.password.requestFocus()
        }
        binding.login.setOnClickListener { signIn() }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                signIn()
                true
            } else {
                false
            }
        }
    }

    /** The account entry: the current account and what signing in again does to its servers. */
    private fun setUpReLogin() {
        supportActionBar?.apply {
            setTitle(R.string.banshan_account)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        binding.subtitle.setText(R.string.banshan_login_subtitle_relogin)
        binding.current.isVisible = true
        binding.current.text = getString(
            R.string.banshan_account_current,
            DataStore.banshanUsername.ifBlank { getString(R.string.banshan_account_unknown) },
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        loginJob?.cancel()
        super.onDestroy()
    }

    private fun signIn() {
        if (busy) return
        val userName = binding.username.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        if (userName.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.banshan_login_empty))
            if (userName.isEmpty()) binding.username.requestFocus() else binding.password.requestFocus()
            return
        }

        hideKeyboard()
        showError("")
        setBusy(true)
        loginJob = lifecycleScope.launch {
            try {
                val outcome = BanShanSession.signIn(userName, password) { stage ->
                    onMainDispatcher { showStage(stage) }
                }
                if (!isActive || isFinishing || isDestroyed) return@launch
                if (outcome.success) {
                    Toast.makeText(this@LoginActivity, outcome.message, Toast.LENGTH_SHORT).show()
                    enterApp()
                } else {
                    showError(outcome.message)
                    setBusy(false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w("BanShan login failed", e)
                if (isFinishing || isDestroyed) return@launch
                showError(getString(R.string.banshan_login_failed))
                setBusy(false)
            }
        }
    }

    private fun setBusy(value: Boolean) {
        if (isFinishing || isDestroyed) return
        busy = value
        binding.usernameLayout.isEnabled = !value
        binding.passwordLayout.isEnabled = !value
        binding.login.isEnabled = !value
        binding.progress.isVisible = value
        if (value) showStage(BanShanSession.Stage.SIGNING_IN)
    }

    private fun showStage(stage: BanShanSession.Stage) {
        if (isFinishing || isDestroyed) return
        binding.status.setText(
            if (stage == BanShanSession.Stage.SIGNING_IN) {
                R.string.banshan_login_signing_in
            } else {
                R.string.banshan_login_downloading
            }
        )
    }

    private fun showError(message: String) {
        if (isFinishing || isDestroyed) return
        binding.error.isVisible = message.isNotEmpty()
        binding.error.text = message
    }

    private fun hideKeyboard() {
        val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        manager.hideSoftInputFromWindow(binding.root.windowToken, 0)
    }

    private fun enterApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }
}

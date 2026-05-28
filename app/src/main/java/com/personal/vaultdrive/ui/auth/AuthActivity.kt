package com.personal.vaultdrive.ui.auth
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.vaultdrive.core.session.AuthResult
import com.personal.vaultdrive.core.session.TokenManager
import com.personal.vaultdrive.databinding.ActivityAuthBinding
import com.personal.vaultdrive.ui.browser.BrowserActivity
import kotlinx.coroutines.launch

class AuthActivity : AppCompatActivity() {
    private lateinit var b: ActivityAuthBinding
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.btnSignIn.setOnClickListener { signIn() }
    }
    private fun signIn() {
        b.progress.visibility = View.VISIBLE
        b.btnSignIn.isEnabled = false
        lifecycleScope.launch {
            when (val r = TokenManager.signIn(this@AuthActivity)) {
                is AuthResult.Success -> { startActivity(Intent(this@AuthActivity, BrowserActivity::class.java)); finish() }
                is AuthResult.Error -> { b.progress.visibility = View.GONE; b.btnSignIn.isEnabled = true; b.tvError.text = r.msg; b.tvError.visibility = View.VISIBLE }
                is AuthResult.Cancelled -> { b.progress.visibility = View.GONE; b.btnSignIn.isEnabled = true }
            }
        }
    }
}

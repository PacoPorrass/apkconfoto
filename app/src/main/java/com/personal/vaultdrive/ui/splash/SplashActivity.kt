package com.personal.vaultdrive.ui.splash
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.personal.vaultdrive.core.security.Prefs
import com.personal.vaultdrive.core.session.TokenManager
import com.personal.vaultdrive.ui.auth.AuthActivity
import com.personal.vaultdrive.ui.browser.BrowserActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        lifecycleScope.launch {
            val start = System.currentTimeMillis()
            var msalReady = false
            TokenManager.init(this@SplashActivity) { msalReady = true }
            val timeout = System.currentTimeMillis() + 3000
            while (!msalReady && System.currentTimeMillis() < timeout) delay(50)
            val elapsed = System.currentTimeMillis() - start
            if (elapsed < 1200) delay(1200 - elapsed)
            startActivity(Intent(this@SplashActivity, if (Prefs.isTokenValid()) BrowserActivity::class.java else AuthActivity::class.java))
            finish()
        }
    }
}

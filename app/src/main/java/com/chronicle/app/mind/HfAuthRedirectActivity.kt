package com.chronicle.app.mind

import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import android.os.Bundle
import com.chronicle.app.ChronicleApp
import kotlinx.coroutines.launch

class HfAuthRedirectActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data
        if (uri?.scheme != "chronicle" || uri.host != "oauth" || uri.path != "/huggingface") {
            finish()
            return
        }
        lifecycleScope.launch {
            try {
                (application as ChronicleApp).container.hfAuth.finishOAuth(
                    uri.getQueryParameter("code").orEmpty(), uri.getQueryParameter("state").orEmpty())
                startActivity(android.content.Intent(this@HfAuthRedirectActivity,
                    com.chronicle.app.MainActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (_: Throwable) {
                // The guided sheet lets the user retry; never log an OAuth callback.
            } finally { finish() }
        }
    }
}

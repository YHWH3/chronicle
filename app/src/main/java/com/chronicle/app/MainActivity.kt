package com.chronicle.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chronicle.app.media.OrphanSweep
import com.chronicle.app.reminders.Notifier
import com.chronicle.app.ui.nav.ChronicleRoot
import com.chronicle.app.ui.theme.ChronicleTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.ensureChannels(this)
        val container = (application as ChronicleApp).container
        // BE-18 — sweep orphaned takes + abandoned model temps off the UI
        // thread; a sweep failure must never hold startup hostage.
        container.appScope.launch {
            runCatching { OrphanSweep.run(applicationContext, container.journalRepository) }
        }
        setContent {
            // FE-12 — hold the first compose frame until DataStore's first
            // emission: rendering SYSTEM before prefs arrive flashes the
            // wrong palette on cold start. While null, the window's own
            // `ink` background shows instead of a wrong-theme frame.
            val prefs by container.prefsRepository.prefs
                .collectAsStateWithLifecycle(initialValue = null)
            prefs?.let { p ->
                ChronicleTheme(mode = p.themeMode) {
                    ChronicleRoot(container)
                }
            }
        }
    }
}

package dk.cbavf.studio

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dk.cbavf.studio.debug.Debug
import dk.cbavf.studio.foreground.Foreground
import dk.cbavf.studio.edition.EditionWrapper
import dk.cbavf.studio.ui.StudioApp

/** Images another app shared into the studio, with the sharing app's package if Android tells us. */
data class Incoming(val uris: List<Uri>, val fromPackage: String?)

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateListOf<Incoming>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) receive(intent)
        // Nothing runs while the studio is off screen: every loop, sensor and socket follows this one flag.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> Foreground.gate.onStart()
                Lifecycle.Event.ON_STOP -> {
                    Foreground.gate.onStop()
                    Debug.i("studio", "off screen: loops paused (${Foreground.gate.stops})")
                }
                else -> Unit
            }
        })
        setContent { EditionWrapper { StudioApp(incoming) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent)
    }

    private fun receive(intent: Intent?) {
        // From the palette notification: a room to jump to, or a word typed into its "Go to…" field.
        intent?.getStringExtra(dk.cbavf.studio.palette.Palette.EXTRA_ROOM)?.let { dk.cbavf.studio.palette.PaletteBus.requested = it }
        dk.cbavf.studio.palette.Palette.queryOf(intent)?.let { q ->
            dk.cbavf.studio.palette.PaletteSearch.match(q, dk.cbavf.studio.ui.Room.entries.map { it.title })
                ?.let { dk.cbavf.studio.palette.PaletteBus.requested = it }
        }
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) incoming += Incoming(uris, referrer?.host?.takeIf { it != packageName })
    }
}

package dk.cbavf.studio.palette

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dk.cbavf.studio.MainActivity
import dk.cbavf.studio.R

/** A room asked for from outside the app (the palette notification); the studio switches to it and clears this. */
object PaletteBus {
    var requested by mutableStateOf<String?>(null)
}

/**
 * The command palette in the notification shade: one ongoing notification that says which room and project you are in, with
 * quick jumps to a few rooms and a "Go to…" field where you type a room name. Needs the notification permission on Android 13+.
 */
object Palette {
    const val EXTRA_ROOM = "palette.room"
    const val ACTION_SEARCH = "dk.cbavf.studio.PALETTE_SEARCH"
    const val KEY = "palette.query"
    private const val CHANNEL = "palette"
    private const val ID = 4207

    /** Shows (or updates) the notification for [room] in [project]. */
    fun show(context: Context, room: String, project: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Command palette", NotificationManager.IMPORTANCE_LOW))
        }
        fun open(code: Int, target: String?): PendingIntent {
            val i = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (target != null) i.putExtra(EXTRA_ROOM, target)
            return PendingIntent.getActivity(context, code, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        val icon = Icon.createWithResource(context, R.drawable.ic_palette)
        // The search field's result comes back to the activity, so no receiver has to start an activity from a notification.
        val searchIntent = Intent(context, MainActivity::class.java).setAction(ACTION_SEARCH)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val searchPi = PendingIntent.getActivity(context, 99, searchIntent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val search = Notification.Action.Builder(icon, "Go to…", searchPi)
            .addRemoteInput(RemoteInput.Builder(KEY).setLabel("Go to a room").build())
            .build()
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(icon)
            .setContentTitle("Moiré Studio · $room")
            .setContentText(project)
            .setContentIntent(open(1, null))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(search)
            .addAction(Notification.Action.Builder(icon, "Draw", open(2, "Draw")).build())
            .addAction(Notification.Action.Builder(icon, "Camera", open(3, "Camera")).build())
            .addAction(Notification.Action.Builder(icon, "Develop", open(4, "Develop")).build())
            .build()
        runCatching { nm.notify(ID, n) }
    }

    fun hide(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    /** The room a typed query asks for, from the intent the search field sent. */
    fun queryOf(intent: Intent?): String? =
        if (intent?.action == ACTION_SEARCH) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY)?.toString() else null
}

/** The Quick Settings tile: one tap opens the studio. */
class PaletteTileService : TileService() {
    override fun onStartListening() {
        qsTile?.let { it.state = Tile.STATE_INACTIVE; it.updateTile() }
    }

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

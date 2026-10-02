package com.chronicle.app.media

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.chronicle.app.MainActivity
import com.chronicle.app.R
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * BE-13 — minimal foreground-service shell that keeps the process (and the
 * microphone capture) alive while a take is being recorded. On API 31+ a
 * backgrounded app without a mic-type FGS receives silence from the audio
 * stack, so takes used to truncate quietly the moment the screen locked.
 *
 * This service owns no recorder state — [TakeRecorder] lives in the app
 * container and the ViewModel drives it. The service only:
 *  - holds foreground importance so a backgrounded/locked phone keeps
 *    delivering real mic input,
 *  - shows the mandatory ongoing "Recording…" notification (tap opens the
 *    app; the Stop action emits [Event.STOP_REQUESTED]),
 *  - holds audio focus for the take so a call or a competing recorder
 *    surfaces as [Event.INTERRUPTED] instead of writing zeros.
 *
 * The service is only ever started while the app is foreground (the start
 * call sites are recording-state effects driven by a UI tap), so Android 12
 * background-start restrictions do not apply.
 */
class RecordingService : Service() {

    /** Requests the recording owner acts on — delivered through [events]. */
    enum class Event {
        /** Notification "Stop" action — finish and keep the take normally. */
        STOP_REQUESTED,

        /** Audio focus was pulled (call, another recorder) — drop the take. */
        INTERRUPTED,
    }

    private var focusRequest: AudioFocusRequest? = null
    private var interrupted = false

    // Any focus loss while recording means the mic is no longer ours — the
    // rest of the take would capture silence. Reported once per run.
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change < 0 && !interrupted) {
            interrupted = true
            _events.tryEmit(Event.INTERRUPTED)
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            _events.tryEmit(Event.STOP_REQUESTED)
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        requestAudioFocus()
        // A killed service must not resurrect a phantom keep-alive.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        focusRequest?.let { req ->
            runCatching {
                (getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                    .abandonAudioFocusRequest(req)
            }
        }
        focusRequest = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun goForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun requestAudioFocus() {
        if (focusRequest != null) return
        interrupted = false
        // MEDIA + SPEECH: claiming a comms usage could push some devices
        // into call-mode routing; we only want the loss signal.
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(
                focusListener,
                Handler(Looper.getMainLooper()),
            )
            .build()
        val manager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // Granted or not, capture continues — this only buys us the loss signal.
        if (manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            focusRequest = request
        }
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName)
                ?: Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recording…")
            .setContentText("Your words stay on this device.")
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recording",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while a take is being captured"
            setSound(null, null)
            enableVibration(false)
        }
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "chronicle_recording"
        private const val NOTIFICATION_ID = 4109
        private const val ACTION_STOP = "com.chronicle.app.media.RECORDING_STOP"

        private val _events = MutableSharedFlow<Event>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        /**
         * One-shot signals for the recording owner (TodayViewModel). Handlers
         * re-check the recording state, so a stale buffered event can never
         * kill a healthy take.
         */
        val events: SharedFlow<Event> = _events

        /**
         * Starts the keep-alive service. No-op without RECORD_AUDIO — on
         * API 34+ a microphone-type FGS may only start while the permission
         * is granted, and the recorder already reports a missing permission
         * through [RecordStart.PermissionMissing].
         */
        fun start(context: Context) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val intent = Intent(context, RecordingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stops the keep-alive — idempotent, safe when never started. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RecordingService::class.java))
        }
    }
}

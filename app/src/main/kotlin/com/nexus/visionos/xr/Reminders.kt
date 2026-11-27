package com.nexus.visionos.xr

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.nexus.visionos.MainActivity
import com.nexus.visionos.R
import com.nexus.xr.CalendarEvent
import com.nexus.xr.EventCodec

/** Calendar entries and their reminders, kept only on this phone. */
object EventStore {
    @Volatile var version = 0
        private set
    /** While spatial mode is on screen it shows reminders itself. */
    @Volatile var shownInSpatial = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("events", Context.MODE_PRIVATE)

    @Synchronized fun all(context: Context) = EventCodec.decode(prefs(context).getString("list", "").orEmpty())

    @Synchronized private fun save(context: Context, events: List<CalendarEvent>) {
        prefs(context).edit().putString("list", EventCodec.encode(events)).apply()
        version++
    }

    @Synchronized fun add(context: Context, atMillis: Long, title: String): CalendarEvent {
        val event = CalendarEvent(
            maxOf(System.currentTimeMillis(), (all(context).maxOfOrNull { it.id } ?: 0) + 1), atMillis, title.trim().take(200),
            // A time that has already passed is only a note in the calendar.
            rung = atMillis <= System.currentTimeMillis()
        )
        save(context, EventCodec.add(all(context), event))
        if (!event.rung) schedule(context, event)
        return event
    }

    @Synchronized fun remove(context: Context, id: Long) {
        save(context, all(context).filter { it.id != id })
        context.getSystemService(AlarmManager::class.java).cancel(alarm(context, id))
    }

    @Synchronized fun markRung(context: Context, id: Long) =
        save(context, all(context).map { if (it.id == id) it.copy(rung = true) else it })

    /** Sets every waiting alarm again; Android forgets them when the phone restarts. */
    fun scheduleAll(context: Context) = all(context).filter { !it.rung }.forEach { schedule(context, it) }

    private fun alarm(context: Context, id: Long) = PendingIntent.getBroadcast(
        context, id.hashCode(), Intent(context, ReminderReceiver::class.java).setAction(ACTION_RING).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** An inexact alarm needs no special permission; Android may deliver it a few minutes late. */
    private fun schedule(context: Context, event: CalendarEvent) {
        context.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.atMillis, alarm(context, event.id))
    }

    const val ACTION_RING = "com.nexus.visionos.REMIND"
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Reminders missed while the phone was off are shown now.
                EventCodec.due(EventStore.all(context), System.currentTimeMillis()).forEach { notify(context, it) }
                EventStore.scheduleAll(context)
            }
            EventStore.ACTION_RING -> {
                if (EventStore.shownInSpatial) return
                val id = intent.getLongExtra("id", 0)
                EventStore.all(context).firstOrNull { it.id == id && !it.rung }?.let { notify(context, it) }
            }
        }
    }

    private fun notify(context: Context, event: CalendarEvent) {
        val allowed = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!allowed) {
            // Left unshown, so spatial mode shows it next time.
            Log.w("NexusXR", "Reminder waiting: notifications are not allowed")
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.reminders), NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(
            event.id.hashCode(),
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_ui_calendar)
                .setContentTitle(context.getString(R.string.reminder))
                .setContentText(event.title)
                // The text is hidden on a locked screen.
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        EventStore.markRung(context, event.id)
    }

    private companion object { const val CHANNEL = "reminders" }
}

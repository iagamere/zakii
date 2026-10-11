package com.abdo.ps4monitor
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Receives the buttons of the download cards (Pause / Resume / Delete). The work itself is Notifier.onAction -> Ops / DownloadMonitor. */
class NotifActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val id = intent.getStringExtra("id") ?: return
        Notifier.onAction(action, id)
    }
}

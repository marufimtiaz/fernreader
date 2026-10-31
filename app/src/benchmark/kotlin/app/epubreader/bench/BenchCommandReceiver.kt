package app.epubreader.bench

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/** Harness commands (see bench/README.md). Dispatched on the main thread. */
class BenchCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: return
        val arg = intent.getStringExtra("arg") ?: ""
        Probe.ev("cmd", "$cmd $arg")
        Handler(Looper.getMainLooper()).post { BenchCommands.dispatch(cmd, arg) }
    }
}

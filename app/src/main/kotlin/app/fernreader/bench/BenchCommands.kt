package app.fernreader.bench

import java.util.concurrent.ConcurrentHashMap

/** Bench command handlers (registered by MainActivity only when Probe is enabled, i.e. in the benchmark build). */
object BenchCommands {
    private val handlers = ConcurrentHashMap<String, (String) -> Unit>()
    fun register(cmd: String, h: (String) -> Unit) { handlers[cmd] = h }
    fun dispatch(cmd: String, arg: String) {
        BenchMarker.toggle()
        handlers[cmd]?.invoke(arg) ?: Probe.ev("cmd_unknown", cmd)
    }
}

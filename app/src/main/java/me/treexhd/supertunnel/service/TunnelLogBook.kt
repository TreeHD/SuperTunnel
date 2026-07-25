package me.treexhd.supertunnel.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small in-process diagnostic timeline. Never put credentials or payload bodies here. */
object TunnelLogBook {
    private val clock = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
    private val entries = MutableStateFlow<List<String>>(emptyList())
    val lines = entries.asStateFlow()

    fun clear() { entries.value = emptyList() }
    fun add(message: String) {
        val time = requireNotNull(clock.get()).format(Date())
        entries.value = (entries.value + "$time  $message").takeLast(200)
    }
}

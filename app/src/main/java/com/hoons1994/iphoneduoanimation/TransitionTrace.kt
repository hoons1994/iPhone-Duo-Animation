package com.hoons1994.iphoneduoanimation

import android.os.SystemClock
import java.util.ArrayDeque

/** Bounded in-memory metadata only. No screen images or external transmission. */
class TransitionTrace {
    private val rows = ArrayDeque<String>()
    @Synchronized fun add(event: String, detail: String = "") {
        if (rows.size >= 4096) rows.removeFirst()
        fun quote(value: String) = "\"" + value.replace("\"", "\"\"").replace('\n', ' ') + "\""
        rows.addLast("${SystemClock.elapsedRealtime()},${quote(event)},${quote(detail)}")
    }
    @Synchronized fun csv(): String = "elapsed_ms,event,detail\n" + rows.joinToString("\n") + "\n"
}

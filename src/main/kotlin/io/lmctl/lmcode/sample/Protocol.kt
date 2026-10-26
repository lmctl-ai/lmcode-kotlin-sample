package io.lmctl.lmcode.sample

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

object Protocol {
    // Protocol writes stdout with LF newlines; bytes >= 0x20 pass through unchanged,
    // preserving raw note bytes (NUL escapes, valid UTF-8, and non-UTF-8 bytes).
    fun printLf(line: String) {
        val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
        System.out.write(bytes, 0, bytes.size)
        System.out.flush()
    }

    fun success(ledger: Ledger): Int {
        val out = ByteArrayOutputStream()
        out.write("""{"ok": true, "result": {"balance": ${ledger.balance}, "entries": ${ledger.entries}, "note": """".toByteArray(StandardCharsets.US_ASCII))
        for (b in ledger.note.bytes()) {
            val u = b.toInt() and 0xFF
            when (u) {
                '"'.code -> out.write(byteArrayOf('\\'.code.toByte(), '"'.code.toByte()))
                '\\'.code -> out.write(byteArrayOf('\\'.code.toByte(), '\\'.code.toByte()))
                '\n'.code -> out.write(byteArrayOf('\\'.code.toByte(), 'n'.code.toByte()))
                '\r'.code -> out.write(byteArrayOf('\\'.code.toByte(), 'r'.code.toByte()))
                '\t'.code -> out.write(byteArrayOf('\\'.code.toByte(), 't'.code.toByte()))
                in 0..0x1F -> {
                    val hex = String.format(Locale.ROOT, "\\u%04x", u).toByteArray(StandardCharsets.US_ASCII)
                    out.write(hex)
                }
                else -> out.write(u)
            }
        }
        out.write("\"}}\n".toByteArray(StandardCharsets.US_ASCII))
        System.out.write(out.toByteArray())
        System.out.flush()
        return 0
    }
}

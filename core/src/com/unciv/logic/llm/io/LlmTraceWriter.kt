package com.unciv.logic.llm.io

import java.io.Closeable
import java.io.Writer

class LlmTraceWriter(
    private val writer: Writer,
    private val codec: LlmJsonCodec = LlmJsonCodec()
) : Closeable {
    private var closed = false

    fun writeHeader(header: LlmTraceHeader) {
        writeLine(codec.encodeHeader(header))
    }

    fun writeStep(record: LlmTraceRecord) {
        writeLine(codec.encodeTraceRecord(record))
    }

    private fun writeLine(line: String) {
        check(!closed) { "Trace writer is closed" }
        writer.write(line)
        writer.write("\n")
        writer.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        writer.close()
    }
}

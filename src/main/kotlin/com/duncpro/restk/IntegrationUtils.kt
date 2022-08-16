package com.duncpro.restk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.lang.ArithmeticException
import java.lang.RuntimeException
import java.util.stream.Collectors
import java.util.stream.Stream

fun parseQueryParams(s: String?): Map<String, List<String>> {
    val params = HashMap<String, List<String>>()
    if (s == null) return params
    val pairs = s.split("&").toTypedArray()
    for (item in pairs) {
        if (item.isBlank()) continue
        val pair = item.split("=").toTypedArray()
        params.merge(pair[0], listOf(pair[1])) { a: List<String>, b: List<String> ->
            Stream.concat(a.stream(), b.stream())
                .collect(Collectors.toList())
        }
    }
    return params
}

suspend fun pipeFlowToOutputStream(flow: Flow<Byte>, outputStream: OutputStream) {
    flow
        .onEach { withContext(Dispatchers.IO) { outputStream.write(it.toInt()) } }
        .onCompletion { withContext(Dispatchers.IO) { outputStream.close() } }
        .collect()
}

suspend fun ResponseBodyContainer.collect(): ByteArray = when (this) {
    is AutoChunkedResponseBodyContainer ->  this.data.toCollection(ArrayList()).toByteArray()
    is FullResponseBodyContainer -> {
        val buffer = ByteArray(try {
            Math.toIntExact(this.contentLength)
        } catch (e: ArithmeticException) {
            throw RuntimeException("Cannot collect response body to single ByteArray because it is too big.", e)
        })
        this.data.collectIndexed { index, value -> buffer[index] = value }
        buffer
    }
}
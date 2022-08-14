package com.duncpro.restk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.OutputStream
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
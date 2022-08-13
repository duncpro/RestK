package com.duncpro.restk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream
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

fun CoroutineScope.consumeInputStreamAsChannel(inputStream: InputStream): Channel<Byte> {
    val channel = Channel<Byte>(Channel.UNLIMITED)
    val pipeJob = launch(Dispatchers.IO) {
        inputStream.use {
            var b: Int
            do {
                b = inputStream.read()
                if (b != -1) channel.send(b.toByte())
            } while (b != -1)
        }
    }
    pipeJob.invokeOnCompletion(channel::close)
    return channel
}

suspend fun pipeFlowToOutputStream(flow: Flow<Byte>, outputStream: OutputStream) {
    flow
        .onEach { withContext(Dispatchers.IO) { outputStream.write(it.toInt()) } }
        .onCompletion { withContext(Dispatchers.IO) { outputStream.close() } }
        .collect()
}
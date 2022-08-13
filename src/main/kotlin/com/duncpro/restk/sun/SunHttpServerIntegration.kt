package com.duncpro.restk.sun

import com.duncpro.jroute.HttpMethod
import com.duncpro.jroute.router.Router
import com.duncpro.restk.EndpointGroup
import com.duncpro.restk.ResponseBodyContainer
import com.duncpro.restk.ResponseBodyContainer.AutoChunkedResponseBodyContainer
import com.duncpro.restk.ResponseBodyContainer.FullResponseBodyContainer
import com.duncpro.restk.handleRequest
import com.duncpro.restk.parseQueryParams
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consume
import kotlinx.coroutines.flow.*
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.*
import java.util.Collections.emptyList
import java.util.stream.Collectors
import java.util.stream.Stream

const val SYSTEM_DEFAULT_BACKLOG = -1

private val HttpExchange.isRequestBodyChunked: Boolean get() {
    return (this.requestHeaders["Transfer-Encoding"] ?: emptyList())
        .map { encoding -> Objects.equals(encoding, "chunked") }
        .firstOrNull() ?: false
}

private val HttpExchange.requestContentLength: Int? get() {
    return (this.requestHeaders["Content-Length"] ?: emptyList())
        .map(Integer::parseInt)
        .firstOrNull()
}

private val HttpExchange.hasRequestBody: Boolean get() {
    if (this.requestContentLength == null) return isRequestBodyChunked
    if (this.requestContentLength != 0) return true
    return false
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

/**
 * Creates a new [HttpServer] which handles requests using the given [Router].
 * The caller must invoke [HttpServer.start] on the returned [HttpServer] to begin accepting connections.
 * This function wraps Java's built-in blocking HTTP server, which does not take advantage of coroutines.
 * As such, it is not suitable for use in production. This function is explicitly intended for use
 * when testing applications locally. Consider using a different HTTP server implementation when operating
 * in th real world.
 */
fun httpServerOf(router: Router<EndpointGroup>, address: InetSocketAddress? = null, backlog: Int = SYSTEM_DEFAULT_BACKLOG): HttpServer {
    val httpServer = HttpServer.create(address, backlog)
    httpServer.executor = Dispatchers.IO.asExecutor()

    httpServer.createContext("/") { exchange ->
        runBlocking {
            val response = handleRequest(
                method = HttpMethod.valueOf(exchange.requestMethod.uppercase()),
                path = exchange.requestURI.path,
                query = parseQueryParams(exchange.requestURI.query),
                header = exchange.requestHeaders,
                body = if (exchange.hasRequestBody) consumeInputStreamAsChannel(exchange.requestBody) else null,
                router
            )

            val contentLength = when (response.body) {
                is AutoChunkedResponseBodyContainer -> 0L
                is FullResponseBodyContainer -> response.body.contentLength
                null -> -1L
            }
            exchange.sendResponseHeaders(response.statusCode, contentLength)
            if (response.body != null) pipeFlowToOutputStream(response.body.data, exchange.responseBody)
            exchange.close()
        }
    }

    return httpServer
}
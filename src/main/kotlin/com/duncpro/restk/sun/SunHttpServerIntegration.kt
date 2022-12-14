package com.duncpro.restk.sun

import com.duncpro.jroute.rest.HttpMethod
import com.duncpro.jroute.rest.RestRouter
import com.duncpro.restk.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.InetSocketAddress
import java.util.*
import java.util.Collections.emptyList
import kotlin.collections.HashMap

private val logger = LoggerFactory.getLogger("com.duncpro.restk.SunHttpServerIntegrationKt")

const val SYSTEM_DEFAULT_BACKLOG = -1

private val HttpExchange.isRequestBodyChunked: Boolean get() {
    return (this.requestHeaders["Transfer-Encoding"] ?: emptyList())
        .map { encoding -> Objects.equals(encoding, "chunked") }
        .firstOrNull() ?: false
}

private val HttpExchange.requestContentLength: Long? get() {
    return (this.requestHeaders["Content-Length"] ?: emptyList())
        .map(String::toLong)
        .firstOrNull()
}

private val HttpExchange.hasRequestBody: Boolean get() {
    if (this.requestContentLength == null) return isRequestBodyChunked
    if (this.requestContentLength != 0L) return true
    return false
}

/**
 * Creates a new [HttpServer] which handles requests using the given [RestRouter].
 * The caller must invoke [HttpServer.start] on the returned [HttpServer] to begin accepting connections.
 * This function wraps Java's built-in blocking HTTP server, which does not take advantage of coroutines.
 * As such, it is not suitable for use in production. This function is explicitly intended for use
 * when testing applications locally. Consider using a different HTTP server implementation when operating
 * in th real world.
 */
fun httpServerOf(router: RestRouter<ContentEndpointGroup>, address: InetSocketAddress? = null, backlog: Int = SYSTEM_DEFAULT_BACKLOG): HttpServer {
    val httpServer = HttpServer.create(address, backlog)
    httpServer.executor = Dispatchers.IO.asExecutor()

    httpServer.createContext("/") { exchange ->
        @Suppress("ConvertTryFinallyToUseCall")
        try {
            runBlocking {
                val response = try {
                    handleRequest(
                        method = HttpMethod.valueOf(exchange.requestMethod.uppercase()),
                        path = exchange.requestURI.path,
                        query = parseQueryParams(exchange.requestURI.query),
                        header = exchange.requestHeaders,
                        body = when (exchange.hasRequestBody) {
                            true -> BlockingRequestBody(exchange.requestContentLength, exchange.requestBody)
                            false -> EmptyRequestBody
                        },
                        router
                    )
                } catch (e: Exception) {
                    logger.error("Unhandled exception in request handler", e)
                    RestResponse(500, HashMap(), null)
                }

                exchange.responseHeaders.putAll(response.header)

                val contentLength = when (response.body) {
                    is AutoChunkedResponseBodyContainer -> 0L
                    is FullResponseBodyContainer -> response.body.contentLength
                    null -> -1L
                }

                try {
                    exchange.sendResponseHeaders(response.statusCode, contentLength)
                    if (response.body != null) pipeFlowToOutputStream(response.body.data, exchange.responseBody)
                } catch (e: IOException) {
                    logger.info("Client disconnected prematurely and therefore did not receive the response.", e)
                }
            }
        } finally {
            exchange.close()
        }

    }

    return httpServer
}
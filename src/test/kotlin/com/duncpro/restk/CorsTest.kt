package com.duncpro.restk

import com.duncpro.jroute.rest.HttpMethod
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class CorsTest {
    @Test
    fun `test simple get`() = runBlocking {
        val endpoint = RestEndpoint(HttpMethod.GET, "/hello", emptySet(), null) { request ->
            responseOf { statusCode = 200 }
        }

        val response = handleRequest(
            method = HttpMethod.GET,
            path = "/hello",
            query = emptyMap(),
            header = mapOf(
                Pair("authorization", listOf("abc123")),
                Pair("origin", listOf("https://example.com"))
            ),
            body = MemoryRequestBody(ByteBuffer.wrap("Hello World!".toByteArray())),
            router = createRouter(
                endpoints = setOf(endpoint),
                corsPolicy = CorsPolicies.public()
            )
        )

        assertEquals(200, response.statusCode)
        assertEquals("https://example.com", response.header["Access-Control-Allow-Origin"]?.firstOrNull())
    }

    @Test
    fun `test preflight`() = runBlocking {
        val put = RestEndpoint(HttpMethod.PUT, "/hello", emptySet(), null) { request ->
            responseOf { statusCode = 200 }
        }
        val post = RestEndpoint(HttpMethod.POST, "/hello", emptySet(), null) { request ->
            responseOf { statusCode = 200 }
        }

        val response = handleRequest(
            method = HttpMethod.OPTIONS,
            path = "/hello",
            query = emptyMap(),
            header = mapOf(
               Pair("origin", listOf("https://example.com"))
            ),
            body = MemoryRequestBody(ByteBuffer.wrap("Hello World!".toByteArray())),
            router = createRouter(
                endpoints = setOf(put, post),
                corsPolicy = CorsPolicies.public()
            )
        )

        assertEquals(204, response.statusCode)
        assertEquals("https://example.com", response.header["Access-Control-Allow-Origin"]?.firstOrNull())
        println(response.header)
    }
}
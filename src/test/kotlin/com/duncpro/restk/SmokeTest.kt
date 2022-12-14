package com.duncpro.restk

import com.duncpro.jroute.rest.HttpMethod.POST
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.time.Instant

class SmokeTest {
    @BeforeEach
    fun setLogLevel() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out")
    }

    @Test
    fun `can handle simple text request-response`() = runBlocking {
        val endpoint = RestEndpoint(
            method = POST,
            route = "/users/{userId}/docs/{docId}",
            consumeContentType = setOf(ContentTypes.Text.PLAIN),
            produceContentType = ContentTypes.Text.HTML,
            handler = { request ->
                val userId = request.path("userId").asString()
                val docId = request.path("docId").asString()
                val authToken = request.header("authorization").asString()
                val newDocContent = request.body.asString()

                if (userId == "duncan" && docId == "xyz") throw RestException(409)
                if (authToken != "abc123") throw RestException(401)

                responseOf {
                    statusCode = 201
                    body("<h1>${userId}: ${docId}</h1><p>${newDocContent}</p>", ContentTypes.Text.PLAIN)
                    header("Timestamp", Instant.now().toEpochMilli())
                }
            }
        )

        val anotherEndpoint = RestEndpoint(
            method = POST,
            route = "/festivals/{festivalId}/something",
            consumeContentType = setOf(ContentTypes.Text.PLAIN),
            produceContentType = ContentTypes.Text.HTML,
            handler = { request ->
                responseOf { statusCode = 200 }
            }
        )

        val response = handleRequest(
            method = POST,
            path = "/users/wasd/docs/readme.md",
            query = emptyMap(),
            header = mapOf(
                Pair("authorization", listOf("abc123")),
                Pair("content-type", listOf("text/plain")),
                Pair("accept", listOf("text/html"))
            ),
            body = MemoryRequestBody(ByteBuffer.wrap("Hello World!".toByteArray())),
            router = createRouter(
                endpoints = setOf(endpoint, anotherEndpoint),
                corsPolicy = CorsPolicies.public()
            )
        )

        assertEquals(201, response.statusCode)
    }
}
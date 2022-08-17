package com.duncpro.restk.sun

import com.duncpro.jroute.rest.HttpMethod
import com.duncpro.restk.*
import com.duncpro.restk.ContentType
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class SunHttpServerIntegrationTest {
    @BeforeEach
    fun setLogLevel() {
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "info")
        System.setProperty("org.slf4j.simpleLogger.logFile", "System.out")
    }

    @Test
    fun `can handle requests`() {
        val GreetingEndpoint = RestEndpoint(
            method = HttpMethod.POST,
            route = "/greeting",
            consumeContentType = setOf(ContentTypes.Text.PLAIN),
            produceContentType = ContentTypes.Text.PLAIN,
            handler = { request ->
                val user = request.query("user").asString()
                val echo = request.body.asString()
                responseOf {
                    statusCode = 200
                    body("Hello $user: $echo", ContentTypes.Text.PLAIN)
                }
            })

        val server = httpServerOf(createRouter(endpoints = setOf(GreetingEndpoint), corsPolicy = null), InetSocketAddress(8088))
        server.start()
        val client = HttpClient(CIO)
        runBlocking {
            val response = client.post {
                url("http://localhost:8088/greeting?user=duncan")
                header("content-type", "text/plain")
                header("accept", "text/plain; charset=UTF-8")
                setBody("Example")
            }
            println(response)
            assertTrue(response.status.isSuccess())
        }
        server.stop(0)
    }
}
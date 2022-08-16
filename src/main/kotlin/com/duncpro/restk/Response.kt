package com.duncpro.restk

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.nio.charset.Charset

data class RestResponse internal constructor(
    val statusCode: Int,
    val header: Map<String, List<String>>,
    val body: ResponseBodyContainer?
)

/**
 * This class encapsulates the asynchronous byte flow representing the response to some request.
 * This class cannot be instantiated directly. Instead, use [FullResponseBodyContainer] or [AutoChunkedResponseBodyContainer].
 */
sealed class ResponseBodyContainer private constructor(val data: Flow<Byte>)

/**
 * The [ResponseBodyContainer] used when the length of the response body is known ahead of time.
 */
class FullResponseBodyContainer(data: Flow<Byte>, val contentLength: Long): ResponseBodyContainer(data)

/**
 * The [ResponseBodyContainer] used when the length of the response is not known ahead of time.
 * It is up to the underlying HTTP server platform to decide how the response body should be sent.
 * In general, Chunked Transfer Encoding is used, but that is not a requirement, the platform may
 * choose to buffer the response in memory and then send it using standard non-chunked encoding.
 */
class AutoChunkedResponseBodyContainer(data: Flow<Byte>): ResponseBodyContainer(data)

class ResponseBuilderContext internal constructor(
    var statusCode: Int = 200,
    var header: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var body: ResponseBodyContainer? = null
)

fun ResponseBuilderContext.body(string: String, contentType: ContentType) {
    val bytes = string.toByteArray(contentType.charset)
    contentType(contentType)
    this.body = FullResponseBodyContainer(
        contentLength = bytes.size.toLong(),
        data = flow { emitAll(bytes.asSequence().asFlow()) }
    )
}

fun ResponseBuilderContext.header(key: String, value: String) {
    val values = header.getOrPut(key) { mutableListOf() }
    values.add(value)
}

fun ResponseBuilderContext.header(key: String, value: Int) = this.header(key, value.toString())
fun ResponseBuilderContext.header(key: String, value: Boolean) = this.header(key, value.toString())
fun ResponseBuilderContext.header(key: String, value: Long) = this.header(key, value.toString())
fun ResponseBuilderContext.header(key: String, value: Double) = this.header(key, value.toString())
fun ResponseBuilderContext.contentType(contentType: ContentType) = this.header("Content-Type", contentType.toString())


fun responseOf(builder: ResponseBuilderContext.() -> Unit): RestResponse {
    val context = ResponseBuilderContext()
    builder(context)
    return RestResponse(context.statusCode, context.header, context.body)
}
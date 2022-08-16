package com.duncpro.restk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.toCollection
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.SortedMap
import java.util.TreeMap

sealed interface RequestBody {
    fun asFlow(): Flow<Byte>
    suspend fun asByteBuffer(): ByteBuffer
    val contentLength: Long?
}

class MemoryRequestBody(private val data: ByteBuffer): RequestBody {
    override fun asFlow(): Flow<Byte> = flow {
        while (data.hasRemaining()) {
            emit(data.get())
        }
    }

    override suspend fun asByteBuffer(): ByteBuffer { return this.data; }

    override val contentLength: Long get() = data.remaining().toLong()
}

object EmptyRequestBody: RequestBody {
    override fun asFlow(): Flow<Byte> = flow {  }
    override suspend fun asByteBuffer(): ByteBuffer = ByteBuffer.allocate(0)
    override val contentLength: Long = 0
}

abstract class DelegateToFlowBody(override val contentLength: Long?): RequestBody {
    override suspend fun asByteBuffer(): ByteBuffer {
        return contentLength?.let { length ->
            val buffer = ByteBuffer.allocate(kotlin.run {
                try {
                    Math.toIntExact(length)
                } catch (e: ArithmeticException) {
                    throw RestException(statusCode = 413, message = "Request length overflows integer and can therefore" +
                            " not be represented as a single ByteBuffer.")
                }
            })
            this.asFlow().collect(buffer::put)
            buffer
        } ?: ByteBuffer.wrap(asFlow().toCollection(ArrayList()).toByteArray())
    }
}

class AsyncRequestBody(
    private val channel: Channel<Byte>,
    contentLength: Long?
): DelegateToFlowBody(contentLength) {
    override fun asFlow(): Flow<Byte> = channel.consumeAsFlow()
}

class BlockingRequestBody(contentLength: Long?, private val inputStream: InputStream): DelegateToFlowBody(contentLength)  {
    override fun asFlow(): Flow<Byte> = flow {
        inputStream.use {
            var b: Int
            do {
                b = inputStream.read()
                if (b != -1) emit(b.toByte())
            } while (b != -1)
        }
    }.flowOn(Dispatchers.IO)
}

class RequestBodyReference internal constructor(private val contentType: ContentType?, private val raw: RequestBody) {
    /**
     * Returns the raw request body  as an asynchronous flow of binary data as well as the content-type declared in the
     * request (if any). If this request contained no data, the flow will immediately end without emitting a single byte,
     * no exception is thrown.
     */
    fun asFlow(): Pair<ContentType?, Flow<Byte>> = Pair(contentType, raw.asFlow())

    /**
     * Returns the raw request body as a [ByteBuffer], as well as the content-type declared in the request (if any).
     * If this request contained no data, the [ByteBuffer] will be empty.
     */
    suspend fun asByteBuffer(): Pair<ContentType?, ByteBuffer> = Pair(contentType, raw.asByteBuffer())
}

/**
 * Returns the raw request body as a [ByteArray], as well as the content-type declared in the request (if any).
 * If this request contained no data, the [ByteArray] will be empty. This method should only be used as a last
 * resort when integrating with pre-existing code that does not take advantage of [ByteBuffer].
 */
suspend fun RequestBodyReference.asByteArray(): Pair<ContentType?, ByteArray> {
    val (contentType, source) = asByteBuffer()
    val destination = ByteArray(source.remaining())
    source.get(destination)
    return Pair(contentType, destination)
}

/**
 * Deserializes the request body into a [String]. If the Content-Type header declares a charset, that charset is used
 * during deserialization, otherwise [Charsets.UTF_8] is assumed.
 */
suspend fun RequestBodyReference.asString(): String {
    val (contentType, data) = asByteArray()
    return String(data, contentType?.charset ?: Charsets.UTF_8)
}

class RestRequest internal constructor(
    val path: Map<String, String>,
    val query: Map<String, List<String>>,
    header: Map<String, List<String>>, // Should be case-insensitive
    internal val rawBody: RequestBody
) {
    val header by lazy {
        val map: SortedMap<String, List<String>> = TreeMap(String.CASE_INSENSITIVE_ORDER)
        map.putAll(header)
        return@lazy map
    }
}

fun RestRequest.path(parameter: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.PATH_PARAMETER, parameter, this.path[parameter])

fun RestRequest.header(key: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.HEADER, key, this.header[key]?.firstOrNull())

fun RestRequest.query(key: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.QUERY, key, this.query[key]?.firstOrNull())

val RestRequest.body: RequestBodyReference get() = RequestBodyReference(this.contentType(), this.rawBody)


fun RestRequest.contentType(): ContentType? = header["Content-Type"]
    ?.firstOrNull { it.isNotBlank() }
    ?.let {
        try {
            ContentType.parse(it)
        } catch (e: MalformedContentTypeStringException) {
            throw RestException(statusCode = 400, message = "Malformed Content Type", cause = e)
        }
    }

fun RestRequest.accepts(): Set<QualifiableContentType> = header["Accept"]
    ?.firstOrNull()
    ?.split(",")
    ?.map {
        try {
            QualifiableContentType.parse(it)
        } catch (e: MalformedContentTypeStringException) {
            throw RestException(statusCode = 400, message = "Malformed Accept Header", cause = e)
        }
    }
    ?.toSet()
    ?: emptySet()

package com.duncpro.restk

import com.duncpro.jroute.HttpMethod
import com.duncpro.jroute.Path
import com.duncpro.jroute.route.ParameterizedRoute
import com.duncpro.jroute.route.Route
import com.duncpro.jroute.router.Router
import com.duncpro.jroute.router.RouterResult
import com.duncpro.jroute.router.TreeRouter
import com.duncpro.restk.ResponseBodyContainer.FullResponseBodyContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.ByteBuffer
import java.nio.charset.Charset

private val logger: Logger = LoggerFactory.getLogger("com.duncpro.restk")

/**
 * Intended for use within [RequestHandler] functions, or functions which are used exclusively
 * within [RequestHandler]s. This exception is caught within [handleRequest] and used to create
 * an erroneous HTTP response. Deserialization methods, such as [RequestBodyContainer.asString],
 * [RestStringFieldValueReference.asDouble] and similar will throw this exception.
 * When caught by [handleRequest], the stacktrace will be printed using the SLF4j "com.duncpro.restk" logger
 * on [Level.INFO].
 */
class RestException(val statusCode: Int = 400, cause: Throwable? = null, message: String? = null): Exception(message, cause)

/**
 * [RequestBodyContainer] serves as an extension-point for body deserialization methods.
 * When using custom data formats, consider creating an extension method on this class which
 * deserializes the response body. For an example implementation see [RequestBodyContainer.asString].
 */
class RequestBodyContainer internal constructor(val bodyChannel: Channel<Byte>?)

fun RequestBodyContainer.asChannel(): Channel<Byte> {
    if (bodyChannel == null) throw RestException(statusCode = 400, message = "Expected request to contain a body" +
            " but none was provided byt he client")
    return bodyChannel
}

/**
 * Deserializes the response body into a [String].
 * @throws RestException if the byte channel can not be deserialized into a [String] using the given charset.
 *  The exception contains an HTTP Status Code of 400 Bad Request.
 */
suspend fun RequestBodyContainer.asString(charset: Charset = Charset.defaultCharset()): String {
    val builder = ArrayList<Byte>()
    try {
        this.asChannel()
            .consumeAsFlow()
            .toCollection(builder)
    } catch (e: IllegalArgumentException) {
        throw RestException(400, e)
    }

    return String(builder.toByteArray(), charset)
}

enum class RestStringFieldType {
    HEADER,
    QUERY,
    PATH_PARAMETER
}

/**
 * This class serves as an extension-point for HTTP String Field deserializers.
 * Headers, Query Parameters, and Path Parameters are HTTP String Fields.
 * When using custom data formats consider writing an extension method for this class which
 * deserializes the String into a more ergonomic data type.
 */
data class RestStringFieldValueReference internal constructor(
    val type: RestStringFieldType,
    val name: String,

    /**
     * See also [asString], which returns a [String] if the field value was provided in the request,
     * or throws [RestException] if the client failed to provide the field value.
     */
    val value: String?
)

/**
 * If the client provided a value for the field in the request, this function returns the [String] value.
 * If the client failed to provide a value, then [RestException] is thrown.
 */
fun RestStringFieldValueReference.asString(): String = value
    ?: throw RestException(
        statusCode = 400,
        message = "Expected $type $name but it was not present within the request"
    )

/**
 * Parses the [String] value given in the client's request for this field into an [Int].
 * @throws RestException if the client did not provide a value for this field in the request or
 *  if the value is not deserializable into an integer.
 */
fun RestStringFieldValueReference.asInt(): Int {
    try {
        return Integer.parseInt(asString())
    } catch (e: NumberFormatException) {
        throw RestException(400, e, "Expected $type $name to be an integer")
    }
}

/**
 * Parses the [String] value given in the client's request for this field into a [Boolean].
 * @throws RestException if the client did not provide a value for this field in the request. or
 *  if the value is not deserializable into [Boolean].
 */
fun RestStringFieldValueReference.asBoolean(): Boolean {
    try {
        return asString().toBooleanStrict()
    } catch (e: IllegalArgumentException) {
        throw RestException(400, e, "Expected $type $name to be a boolean")
    }
}

/**
 * Parses the [String] value given in the client's request for this field into a [Double].
 * @throws RestException if the client did not provide a value for this field in the request or if the value is not
 *  deserializable into [Double].
 */
fun RestStringFieldValueReference.asDouble(): Double {
    try {
        return asString().toDouble()
    } catch (e: NumberFormatException) {
        throw RestException(400, e, "Expected $type $name to be a decimal")
    }
}

/**
 * Parses the [String] value given in the client's request for this field into a [Long].
 * @throws RestException if the client did not provide a value for this field in the request or if the value is not
 *  deserializable into [Long].
 */
fun RestStringFieldValueReference.asLong(): Long {
    try {
        return asString().toLong()
    } catch (e: NumberFormatException) {
        throw RestException(400, e, "Expected $type $name to be a long")
    }
}

data class RestResponse internal constructor(
    val statusCode: Int,
    val header: Map<String, List<String>>,
    val body: ResponseBodyContainer?
)

data class RestRequest internal constructor(
    val path: Map<String, String>,
    val query: Map<String, List<String>>,
    val header: Map<String, List<String>>,
    val body: RequestBodyContainer
)

/**
 * Returns a [RestStringFieldValueReference] representing the path parameter named [parameter].
 * This function never throws an exception. If the path parameter does not exist a [RestException] will be
 * thrown when attempting to dereference the [RestStringFieldValueReference] through [RestStringFieldValueReference.asString],
 * [RestStringFieldValueReference.asDouble], and similar functions.
 *
 * The identically named field (path) contains an unmodifiable map of all path parameters.
 */
fun RestRequest.path(parameter: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.PATH_PARAMETER, parameter, this.path[parameter])

/**
 * Returns a [RestStringFieldValueReference] representing the first value given by the client for the header [key].
 * This function never throws an exception. If a header value was not provided by the client a [RestException] will be
 * thrown when attempting to dereference the [RestStringFieldValueReference] through [RestStringFieldValueReference.asString],
 * [RestStringFieldValueReference.asDouble], and similar functions.
 *
 * The identically named field (header) contains an unmodifiable map of all headers given by the client.
 */
fun RestRequest.header(key: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.HEADER, key.lowercase(), this.header[key]?.firstOrNull())

/**
 * Returns a [RestStringFieldValueReference] representing the first value given by the client for the query parameter [key].
 * This function never throws an exception. If a query argument was not provided by the client a [RestException] will be
 * thrown when attempting to dereference the [RestStringFieldValueReference] through [RestStringFieldValueReference.asString],
 * [RestStringFieldValueReference.asDouble], and similar functions.
 *
 * The identically named field (header) contains an unmodifiable map of all query arguments given by the client.
 */
fun RestRequest.query(key: String): RestStringFieldValueReference
        = RestStringFieldValueReference(RestStringFieldType.QUERY, key, this.query[key]?.firstOrNull())

/**
 * This class encapsulates the asynchronous byte flow representing the response to some request.
 * This class cannot be instantiated directly. Instead, use [FullResponseBodyContainer] or [AutoChunkedResponseBodyContainer].
 */
sealed class ResponseBodyContainer private constructor(val data: Flow<Byte>) {
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
}


class ResponseBuilderContext internal constructor(
    var statusCode: Int = 200,
    var header: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var body: ResponseBodyContainer? = null
)

/**
 * Uses the given [String] as the body of the [ResponseBuilderContext].
 * This function encodes the given [String] using the given [Charset] and wraps the result in a [FullResponseBodyContainer].
 * There is no need to manually set a "content-length" header from within application code.
 * That will be handled by the underlying HTTP server platform.
 *
 * This method does not set the "content-type" response header. If the [RestEndpoint] which is producing
 * the [RestResponse] represented by this [ResponseBuilderContext] only declared a single producable
 * content type, then the content-type response header will automatically be set by the [handleRequest]
 * method. If [RestEndpoint.produceContentType] contains more than one element, then the response content-type header
 * will not automatically be set, in which case it is the responsibility of the caller of this method to set.
 */
fun ResponseBuilderContext.body(string: String, charset: Charset = Charset.defaultCharset()) {
    val bytes = string.toByteArray(charset)
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


fun responseOf(builder: ResponseBuilderContext.() -> Unit): RestResponse {
    val context = ResponseBuilderContext()
    builder(context)
    return RestResponse(context.statusCode, context.header, context.body)
}

typealias RequestHandler = suspend (RestRequest) -> RestResponse

class RestEndpoint internal constructor(
    val method: HttpMethod,
    val route: String,
    val consumeContentType: Set<String>,
    val produceContentType: Set<String>,
    val handler: RequestHandler
)

fun endpointOf(method: HttpMethod, route: String, consumes: Set<String> = emptySet(),
               produces: Set<String> = emptySet(), handler: RequestHandler): RestEndpoint {
    return RestEndpoint(method, route, consumes, produces, handler)
}


internal data class EndpointPosition internal constructor(val method: HttpMethod, val route: ParameterizedRoute)

/**
 * Represents a set of like-endpoints. That is, endpoints which exist at the same route, and share the same HTTP Verb.
 *
 * Naively one might think that it should be impossible to have multiple endpoints at the same position, but there
 * is in-fact a use case for this scenario. One endpoint might be responsible for handling JSON formatted responses,
 * that is content type "application/json", and another might be responsible for handling HTMl formatted responses,
 * that is content type "text/html".
 *
 * The function [handleRequest] will automatically perform content-negotiation and match the request to the endpoint
 * corresponding to the content-type and accept headers included within the request.
 *
 * [EndpointGroup]s are created automatically, by [routerOf].
 */
class EndpointGroup internal constructor(
    internal val position: EndpointPosition,
    internal val likeEndpoints: Set<RestEndpoint>
)

/**
 * Creates a [Router] containing the given [RestEndpoint]s. The value returned by this function
 * should be passed to [handleRequest] or [handleInMemoryRequest] along with the details
 * of the inbound request.
 */
fun routerOf(vararg endpoints: RestEndpoint): Router<EndpointGroup> {
    val router = TreeRouter<EndpointGroup>()
    endpoints
        .groupBy { EndpointPosition(it.method, ParameterizedRoute.parse(it.route)) }
        .map { (position, likeEndpoints) -> EndpointGroup(position, likeEndpoints.toSet()) }
        .forEach { endpointGroup -> router.addRoute(endpointGroup.position.method, endpointGroup.position.route,
            endpointGroup) }
    return router
}

/**
 * Uses the given [Router] to handle an inbound HTTP/REST request.
 * If no route matches the request, a [RestResponse] with status code 404 Not Found will be returned.
 * If a matching route is found (in terms of HTTP Verb and Path), but content negotiation fails,
 * a [RestResponse] HTTP 400 Bad Request will be returned. If the delegated [RequestHandler] ([RestEndpoint]) throws a
 * [RestException], an [RestResponse] with status code equal to [RestException.statusCode] will be returned. If any
 * other exception occurs, it will not be caught and will bubble up to the caller. Use [handleInMemoryRequest] instead,
 * if the HTTP server platform being used passes the request body as a [ByteArray] or [ByteBuffer] instead of a [Channel].
 * If the HTTP server platform being used is blocking, then [pipeFlowToOutputStream] and [consumeInputStreamAsChannel],
 * may be helpful.
 */
suspend fun handleRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: Channel<Byte>?,
    router: Router<EndpointGroup>
): RestResponse {
    val (_, endpointGroup) = router.route(method, path).orElse(null) ?: return RestResponse(404, emptyMap(), null)
    val sanitizedHeader = header.mapKeys { entry -> entry.key.lowercase() }
    val requestBodyType: String? = sanitizedHeader["content-type"]?.firstOrNull()?.lowercase()
    val acceptableResponseBodyTypes: Set<String> = (sanitizedHeader["accept"] ?: emptyList()).asSequence()
        .flatMap { headerValue -> headerValue.split(",") }
        .map { headerValue -> headerValue.trim().lowercase() }
        .toSet()


    val matchingEndpoint = endpointGroup.likeEndpoints
        .filter { endpoint ->
            (endpoint.consumeContentType.isEmpty() && requestBodyType == null)
                    || endpoint.consumeContentType.map(String::lowercase).contains(requestBodyType)
        }
        .firstOrNull { endpoint -> (endpoint.produceContentType.isEmpty() && acceptableResponseBodyTypes.isEmpty())
                || (endpoint.produceContentType.map(String::lowercase) intersect acceptableResponseBodyTypes).isNotEmpty() }
        ?: return RestResponse(400, emptyMap(), null) // TODO: More specialized error response

    val request = RestRequest(
        path = endpointGroup.position.route.extractVariablesMap(Path(path)),
        query = query,
        header = sanitizedHeader,
        body = RequestBodyContainer(body)
    )

    return try {
        var response = matchingEndpoint.handler(request)
        // Add implicits
        // Automatically set response content-type header if possible
        if (matchingEndpoint.produceContentType.size == 1 && response.body != null) {
            response = response.copy(header = response.header + Pair("content-type",
                listOf(matchingEndpoint.produceContentType.first())))
        }
        return response
    } catch (e: RestException) {
        logger.info("An error occurred while processing request.", e)
        RestResponse(e.statusCode, emptyMap(), null)
    }
}

suspend fun handleInMemoryRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: ByteBuffer?,
    router: Router<EndpointGroup>
): RestResponse = coroutineScope {
    if (body == null) return@coroutineScope handleRequest(method, path, query, header, null, router)
    val bodyChannel = Channel<Byte>(body.limit())
    val writeToChannelJob = launch {
        while (body.hasRemaining()) {
            bodyChannel.send(body.get())
        }
    }
    writeToChannelJob.invokeOnCompletion(bodyChannel::close)
    return@coroutineScope handleRequest(method, path, query, header, bodyChannel, router)
}

internal operator fun <T> RouterResult<T>.component1(): Route = this.route
internal operator fun <T> RouterResult<T>.component2(): T = this.endpoint


package com.duncpro.restk

import com.duncpro.jroute.rest.HttpMethod
import com.duncpro.jroute.Path
import com.duncpro.jroute.rest.RestResource
import com.duncpro.jroute.rest.RestRouteResult
import com.duncpro.jroute.rest.RestRouter
import com.duncpro.jroute.route.Route
import com.duncpro.jroute.util.ParameterizedRoute
import com.duncpro.jroute.router.Router
import com.duncpro.restk.ResponseBodyContainer.FullResponseBodyContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.io.InputStream
import java.lang.ArithmeticException
import java.nio.ByteBuffer
import java.nio.charset.Charset

private val logger: Logger = LoggerFactory.getLogger("com.duncpro.restk")

/**
 * Intended for use within [RequestHandler] functions, or functions which are used exclusively
 * within [RequestHandler]s. This exception is caught within [handleRequest] and used to create
 * an erroneous HTTP response. Deserialization methods, such as [RequestBodyContainer.asByteArray],
 * [RequestBodyContainer.asFlow], [RestStringFieldValueReference.asDouble] and similar will
 * throw this exception. When caught by [handleRequest], the stacktrace will be printed using the SLF4j "com.duncpro.restk"
 * logger on [Level.INFO].
 */
class RestException(val statusCode: Int = 400, cause: Throwable? = null, message: String? = null): Exception(message, cause)

/**
 * [RequestBodyContainer] serves as an extension-point for body deserialization methods.
 * When using custom data formats, consider creating an extension method on this class which
 * deserializes the response body. For an example implementation see [RequestBodyContainer.asString].
 */
sealed interface RequestBodyContainer {
    fun asFlow(): Flow<Byte>
    suspend fun asByteBuffer(): ByteBuffer
}

class MemoryRequestBodyContainer(private val data: ByteBuffer): RequestBodyContainer {
    override fun asFlow(): Flow<Byte> = flow {
        while (data.hasRemaining()) {
            emit(data.get())
        }
    }

    override suspend fun asByteBuffer(): ByteBuffer { return this.data; }
}

object EmptyRequestBodyContainer: RequestBodyContainer {
    override fun asFlow(): Flow<Byte> {
        throw RestException(statusCode = 400, message = "Expected request to contain body but it did not.")
    }

    override suspend fun asByteBuffer(): ByteBuffer {
        throw RestException(statusCode = 400, message = "Expected request to contain body but it did not.")
    }
}

class AsyncRequestBodyContainer(
    private val channel: Channel<Byte>,
    private val contentLength: Long?
): RequestBodyContainer {
    override fun asFlow(): Flow<Byte> = channel.consumeAsFlow()

    override suspend fun asByteBuffer(): ByteBuffer {
        if (contentLength != null) {
            val buffer = ByteBuffer.allocate(kotlin.run {
                try {
                    Math.toIntExact(contentLength)
                } catch (e: ArithmeticException) {
                    throw RestException(statusCode = 413, message = "Request length overflows integer and can therefore" +
                            " not be represented as a single ByteBuffer.")
                }
            })
            this.asFlow().collect(buffer::put)
            return buffer

        }
        return ByteBuffer.wrap(asFlow().toCollection(ArrayList()).toByteArray())
    }
}

class BlockingRequestBodyContainer(private val inputStream: InputStream): RequestBodyContainer  {
    override fun asFlow(): Flow<Byte> = flow {
        inputStream.use {
            var b: Int
            do {
                b = inputStream.read()
                if (b != -1) emit(b.toByte())
            } while (b != -1)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun asByteBuffer(): ByteBuffer
        = inputStream.use {
            try {
                ByteBuffer.wrap(inputStream.readBytes())
            } catch (e: OutOfMemoryError) {
                throw RestException(statusCode = 413, message = "Request length overflows integer and can therefore" +
                        " not be represented as a single ByteBuffer.")
            }
    }
}

suspend fun RequestBodyContainer.asByteArray(): ByteArray {
    val source = this.asByteBuffer()
    val destination = ByteArray(source.remaining())
    source.get(destination)
    return destination
}

/**
 * Deserializes the response body into a [String].
 * @throws RestException if the byte channel can not be deserialized into a [String] using the given charset.
 */
suspend fun RequestBodyContainer.asString(charset: Charset = Charset.defaultCharset()): String = String(asByteArray(), charset)

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

class RestEndpoint constructor(
    val method: HttpMethod,
    val route: String,
    val consumeContentType: Set<String>,
    val produceContentType: Set<String>,
    val handler: RequestHandler
)


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
 * [ContentEndpointGroup]s are created automatically, by [createRouter].
 */
class ContentEndpointGroup internal constructor(
    internal val position: EndpointPosition,
    internal val likeEndpoints: Set<RestEndpoint>
)

/**
 * A set of browser-enforced permissions describing what HTTP Methods and HTTP headers an origin is permitted
 * to use when communicating with a web server.
 *
 * @property allowedMethods the set of all methods which an origin has permission to access. If empty, then the
 *  origin is considered forbidden, and no Access-Control-Allow-Origin, or Access-Control-Allow-Methods header will be
 *  attached to the response. If the set contains at least one method, then both of the aforementioned headers will be
 *  automatically included in the response.
 * @property allowedHeaders the set of all headers which an origin has permission to send. If empty, then the
 *  Access-Control-Allow-Headers header is not attached to the response. If the set contains at least one element,
 *  then the Access-Control-Allow-Headers header will be automatically included in the response.
 */
data class BrowserEnforcedAccessControl(val allowedMethods: Set<HttpMethod>, val allowedHeaders: Set<String>) {
    companion object {
        val Forbidden = BrowserEnforcedAccessControl(emptySet(), emptySet())
    }
}

typealias RestResourceIntrospector = () -> RestResource<ContentEndpointGroup>

/**
 * A policy describing the capabilities that various origins are granted with respect to the endpoints provided by some
 * [RestRouter].
 *
 * The [String] parameter is the origin (example: http://example.com) which is requesting access to some serverside endpoint.
 * The [Route] parameter describes the route which the origin is requesting access to.
 * The [RestResourceIntrospector] is a function which returns the [RestResource] matching the given [Route].
 */
typealias CorsPolicy = (String?, Route, RestResourceIntrospector) -> BrowserEnforcedAccessControl

object CorsPolicies {
    /**
     * Creates a permissive CORS policy which grants permission to all origins to use all resources and methods.
     * By default, this policy will grant only authorization header permission. More headers can be added
     * by passing a set of header names for the [allowedHeaders] parameter.
     */
    fun public(allowedHeaders: Set<String> = setOf("authorization")): CorsPolicy = { _, _, introspect ->
        val targetResource = introspect()
        val allowedMethods = HttpMethod.values().asSequence()
            .filter { method -> targetResource.getMethodEndpoint(method).isPresent }
            .toSet()
        BrowserEnforcedAccessControl(allowedMethods, allowedHeaders)
    }

    /**
     * Creates a private CORS policy which grants permission to only whitelisted origins to use all resources and methods.
     * More restrictive policies can be created by implementing [CorsPolicy] directly.
     */
    fun private(allowedHeaders: Set<String>, originWhitelist: Set<String>): CorsPolicy {
        val sanitizedOriginWhitelist = originWhitelist.asSequence()
            .map(String::lowercase).
            toSet()
        return policy@{ origin, _, introspect ->
            if (origin == null) return@policy BrowserEnforcedAccessControl.Forbidden
            if (!sanitizedOriginWhitelist.contains(origin.lowercase())) {
                return@policy BrowserEnforcedAccessControl.Forbidden
            }
            val targetResource = introspect()
            val allowedMethods = HttpMethod.values().asSequence()
                .filter { method -> targetResource.getMethodEndpoint(method).isPresent }
                .toSet()
            BrowserEnforcedAccessControl(allowedMethods, allowedHeaders)
        }
    }
}

private fun createPreflightEndpoint(route: Route, corsPolicy: CorsPolicy, router: RestRouter<ContentEndpointGroup>) = RestEndpoint(HttpMethod.OPTIONS, route.toString(), emptySet(),
    emptySet()) { preflightRequest ->

    val origin = preflightRequest.header["origin"]?.firstOrNull()
    val resourceIntrospector: RestResourceIntrospector = { router.getEndpoint(route).orElseThrow(::IllegalStateException) }
    val permissions = corsPolicy(origin, route, resourceIntrospector)

    responseOf {
        statusCode = 204

        if (permissions.allowedMethods.isNotEmpty()) {
            header("Access-Control-Allow-Methods", permissions.allowedMethods
                .map(HttpMethod::name)
                .joinToString(", "))
            origin?.let {
                header("Access-Control-Allow-Origin", origin)
            }
        }

        if (permissions.allowedHeaders.isNotEmpty()) {
            header("Access-Control-Allow-Headers", permissions.allowedHeaders.joinToString(", "))
        }
    }
}

private fun wrapEndpointWithCorsSupport(endpoint: RestEndpoint, corsPolicy: CorsPolicy?, router: RestRouter<ContentEndpointGroup>): RestEndpoint {
    if (corsPolicy == null) return endpoint

    return RestEndpoint(endpoint.method, endpoint.route, endpoint.consumeContentType, endpoint.produceContentType) { request ->
        val origin = request.header["origin"]?.firstOrNull()
        val route = ParameterizedRoute.parse(endpoint.route)
        val permissions = corsPolicy(origin, route) { router.getEndpoint(route).orElseThrow(::IllegalStateException) }
        val permissionGranted = permissions.allowedMethods.isNotEmpty()
        val response = endpoint.handler(request)

        if (permissionGranted && origin != null) {
            response.copy(header = response.header + Pair("Access-Control-Allow-Origin", listOf(origin)))
        } else {
            response
        }
    }
}

/**
 * Creates a [Router] containing the given [RestEndpoint]s. The value returned by this function
 * should be passed to [handleRequest].
 */
fun createRouter(endpoints: Iterable<RestEndpoint>, corsPolicy: CorsPolicy?): RestRouter<ContentEndpointGroup> {
    val router = RestRouter<ContentEndpointGroup>()

    // Register implicit CORS preflight OPTIONS request endpoints.
    val implicitCorsPreflightEndpoints = endpoints.asSequence()
        .map(RestEndpoint::route)
        .map(ParameterizedRoute::parse)
        .distinct()
        .mapNotNull { route -> corsPolicy?.let { createPreflightEndpoint(route, corsPolicy, router) } }
        .toList()

    // Register all explicitly defined endpoints.
    (endpoints union implicitCorsPreflightEndpoints)
        .map { endpoint -> wrapEndpointWithCorsSupport(endpoint, corsPolicy, router) }
        .groupBy { EndpointPosition(it.method, ParameterizedRoute.parse(it.route)) }
        .map { (position, likeEndpoints) -> ContentEndpointGroup(position, likeEndpoints.toSet()) }
        .forEach { endpointGroup -> router.add(endpointGroup.position.method, endpointGroup.position.route, endpointGroup) }

    return router
}

/**
 * Uses the given [Router] to handle an inbound HTTP/REST request.
 * If no route matches the request, a [RestResponse] with status code 404 Not Found will be returned.
 * If a matching route is found (in terms of HTTP Verb and Path), but content negotiation fails,
 * a [RestResponse] HTTP 400 Bad Request will be returned. If the delegated [RequestHandler] ([RestEndpoint]) throws a
 * [RestException], an [RestResponse] with status code equal to [RestException.statusCode] will be returned. If any
 * other exception occurs, it will not be caught and will bubble up to the caller.
 */
suspend fun handleRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: RequestBodyContainer,
    router: RestRouter<ContentEndpointGroup>
): RestResponse {
    val endpointGroup = when (val result = router.route(method, path)) {
        is RestRouteResult.ResourceNotFound<ContentEndpointGroup> -> { return RestResponse(404, emptyMap(), null) }
        is RestRouteResult.UnsupportedMethod<ContentEndpointGroup> -> { return RestResponse(405, emptyMap(), null) }
        is RestRouteResult.RestRouteMatch<ContentEndpointGroup> -> result.methodEndpoint
        else -> throw AssertionError()
    }

    val sanitizedHeader = header.mapKeys { entry -> entry.key.lowercase() }
    val requestBodyType: String? = sanitizedHeader["content-type"]?.firstOrNull()?.lowercase()
    val acceptableResponseBodyTypes: Set<String> = (sanitizedHeader["accept"] ?: emptyList()).asSequence()
        .flatMap { headerValue -> headerValue.split(",") }
        .map { headerValue -> headerValue.trim().lowercase() }
        .toSet()

    val capableConsumerEndpoints = endpointGroup.likeEndpoints
        .filter { endpoint ->
            (endpoint.consumeContentType.isEmpty() && requestBodyType == null)
                    || endpoint.consumeContentType.map(String::lowercase).contains(requestBodyType)
        }

    if (capableConsumerEndpoints.isEmpty()) {
       logger.info("Unable to process request because the request payload contains an unsupported media type: " +
               "${requestBodyType ?: "No Content"}.")
        return RestResponse(415, emptyMap(), null)
    }


    val matchedEndpoint = capableConsumerEndpoints
        .firstOrNull { endpoint -> (endpoint.produceContentType.isEmpty() && acceptableResponseBodyTypes.isEmpty())
                || (endpoint.produceContentType.map(String::lowercase) intersect acceptableResponseBodyTypes).isNotEmpty() }

    if (matchedEndpoint == null) {
        logger.info("Unable to process request because the accept header does not contain any supported media types.")
        return RestResponse(406, emptyMap(), null)
    }

    val request = RestRequest(endpointGroup.position.route.extractVariablesMap(Path(path)), query,
        sanitizedHeader, body)

    return try {
        var response = matchedEndpoint.handler(request)
        // Add implicits
        // Automatically set response content-type header if possible
        if (matchedEndpoint.produceContentType.size == 1 && response.body != null) {
            response = response.copy(header = response.header + Pair("content-type",
                listOf(matchedEndpoint.produceContentType.first())))
        }
        return response
    } catch (e: RestException) {
        logger.info("An error occurred while processing request.", e)
        RestResponse(e.statusCode, emptyMap(), null)
    }
}
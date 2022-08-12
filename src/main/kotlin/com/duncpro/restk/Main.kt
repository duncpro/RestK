package com.duncpro.restk

import com.duncpro.jroute.HttpMethod
import com.duncpro.jroute.Path
import com.duncpro.jroute.route.Route
import com.duncpro.jroute.route.StaticRouteElement
import com.duncpro.jroute.route.WildcardRouteElement
import com.duncpro.jroute.router.Router
import com.duncpro.jroute.router.RouterResult
import com.duncpro.jroute.router.TreeRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.nio.charset.Charset

private val logger: Logger = LoggerFactory.getLogger("com.duncpro.restk")

/**
 * Intended for use within [RequestHandler] functions, or functions which are used exclusively
 * within [RequestHandler]s. This exception is caught within [handleRequest] and used to create
 * an erroneous HTTP response. Deserialization methods, such as [ResponseBodyContainer.asString],
 * [RestStringFieldValueReference.asDouble] and similar will throw this exception.
 * When caught by [handleRequest], the stacktrace will be printed using the SLF4j "com.duncpro.restk" logger
 * on [Level.INFO].
 */
class RestException(val statusCode: Int = 400, cause: Throwable? = null, message: String? = null): Exception(message, cause)

/**
 * [ResponseBodyContainer] servers as an extension-point for body deserialization methods.
 * When using custom data formats, consider creating an extension method on this class which
 * deserializes the response body. For an example implementation see [ResponseBodyContainer.asString].
 */
class ResponseBodyContainer internal constructor(val bodyChannel: Channel<ByteArray>?)

fun ResponseBodyContainer.asChannel(): Channel<ByteArray> {
    if (bodyChannel == null) throw RestException(statusCode = 400, message = "Expected request to contain a body" +
            " but none was provided byt he client")
    return bodyChannel
}

/**
 * Deserializes the response body into a [String].
 * @throws RestException if the byte channel can not be deserialized into a [String] using the given charset.
 *  The exception contains an HTTP Status Code of 400 Bad Request.
 */
suspend fun ResponseBodyContainer.asString(charset: Charset = Charset.defaultCharset()): String {
    val builder = StringBuilder()
    try {
        this.asChannel()
            .consumeAsFlow()
            .onEach { part -> builder.append(String(part, charset)) }
            .collect()
    } catch (e: CancellationException) {
        e.printStackTrace()
    } catch (e: IllegalArgumentException) {
        throw RestException(400, e)
    }

    return builder.toString()
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
    val body: Flow<ByteArray>?
)

data class RestRequest internal constructor(
    val path: Map<String, String>,
    val query: Map<String, List<String>>,
    val header: Map<String, List<String>>,
    val body: ResponseBodyContainer
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

class ResponseBuilderContext internal constructor(
    var statusCode: Int = 200,
    var header: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var body: Flow<ByteArray>? = null
)

fun ResponseBuilderContext.body(string: String) {
    this.body = flow { emit(string.toByteArray()) }
}

fun ResponseBuilderContext.header(key: String, value: Any) {
    val values = header.getOrPut(key) { mutableListOf() }
    values.add(value.toString())
}

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

internal data class ParameterizedRoute(val anonymous: Route, val parameters: List<String>) {
    init {
        val wildcardCount = anonymous.elements.count { element -> element is WildcardRouteElement }
        val parameterCount = parameters.size
        if (wildcardCount != parameterCount) throw IllegalArgumentException()
    }
    companion object
}

private enum class SegmentType { PARAMETER, STATIC }

private fun ParameterizedRoute.Companion.parse(routeString: String): ParameterizedRoute {
    var route: Route = Route.ROOT
    val params = mutableListOf<String>()

    routeString.trim().split("/")
        .map { segment -> segment.trim() }
        .filter { segment -> segment != "" }
        .forEach { segment ->
            val hasParameterStart = segment.startsWith('{')
            val hasParameterEnd = segment.endsWith('}')
            if (hasParameterStart xor hasParameterEnd) throw IllegalArgumentException("Expected parameterized" +
                    " route segment to begin AND end with curly braces. For example \"{id}\"")
            @Suppress("MoveVariableDeclarationIntoWhen")
            val segmentType = if (hasParameterStart && hasParameterEnd) SegmentType.PARAMETER else SegmentType.STATIC
            when (segmentType) {
                SegmentType.PARAMETER -> {
                    val parameterName = segment.substring(1, segment.length - 1)
                    if (parameterName.contains('{') || parameterName.contains('}'))
                        throw IllegalArgumentException("Only a single parameter per path segment is allowed")
                    route = route.withTrailingElement(WildcardRouteElement())
                    params.add(parameterName)
                }
                SegmentType.STATIC -> {
                    if (segment.contains('{') || segment.contains('}'))
                        throw IllegalArgumentException("Parameters can not exist within static route elements")
                    route = route.withTrailingElement(StaticRouteElement(segment))
                }
            }
        }

    return ParameterizedRoute(route, params)
}

private fun ParameterizedRoute.extractVariables(path: Path): Map<String, String> {
    val arguments = mutableMapOf<String, String>()

    var wildcardsSoFar = 0
    for ((i, element) in this.anonymous.elements.withIndex()) {
        if (element is WildcardRouteElement) {
            arguments[this.parameters[wildcardsSoFar]] = path.elements[i]
            wildcardsSoFar += 1
        }
    }

    return arguments
}

private fun ParameterizedRoute.extractVariables(pathString: String) = extractVariables(Path(pathString))

internal data class EndpointPosition internal constructor(val method: HttpMethod, val route: ParameterizedRoute)

class EndpointGroup internal constructor(
    internal val position: EndpointPosition,
    internal val likeEndpoints: Set<RestEndpoint>
)

fun routerOf(vararg endpoints: RestEndpoint): Router<EndpointGroup> {
    val router = TreeRouter<EndpointGroup>()
    endpoints
        .groupBy { EndpointPosition(it.method, ParameterizedRoute.parse(it.route)) }
        .map { (position, likeEndpoints) -> EndpointGroup(position, likeEndpoints.toSet()) }
        .forEach { endpointGroup -> router.addRoute(endpointGroup.position.method, endpointGroup.position.route.anonymous,
            endpointGroup) }
    return router
}

suspend fun handleRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: Channel<ByteArray>?,
    router: Router<EndpointGroup>
): RestResponse {
    val (_, endpointGroup) = router.route(method, path).orElse(null) ?: return RestResponse(404, emptyMap(), null)
    val sanitizedHeader = header.mapKeys { entry -> entry.key.lowercase() }
    val requestBodyType: String? = sanitizedHeader["content-type"]?.firstOrNull()
    val acceptableResponseBodyTypes: Set<String> = (sanitizedHeader["accept"] ?: emptyList()).asSequence()
        .flatMap { headerValue -> headerValue.split(",") }
        .map { headerValue -> headerValue.trim().lowercase() }
        .toSet()


    val matchingEndpoint = endpointGroup.likeEndpoints
        .filter { endpoint ->
            (endpoint.consumeContentType.isEmpty() && requestBodyType == null)
                    || endpoint.consumeContentType.contains(requestBodyType)
        }
        .firstOrNull { endpoint -> (endpoint.produceContentType.isEmpty() && acceptableResponseBodyTypes.isEmpty())
                || (endpoint.produceContentType intersect acceptableResponseBodyTypes).isNotEmpty() }
        ?: return RestResponse(400, emptyMap(), null) // TODO: More specialized error response

    val request = RestRequest(
        path = endpointGroup.position.route.extractVariables(path),
        query = query,
        header = sanitizedHeader,
        body = ResponseBodyContainer(body)
    )

    return try {
        matchingEndpoint.handler(request)
    } catch (e: RestException) {
        logger.info("An error occurred while processing request.", e)
        RestResponse(e.statusCode, emptyMap(), null)
    }
}

suspend fun handleRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: ByteArray?,
    router: Router<EndpointGroup>
): RestResponse {
    val bodyChannel: Channel<ByteArray>? = body?.let { bytes -> Channel<ByteArray>(1)
        .apply {
            send(bytes)
            close()
        }
    }
    return handleRequest(method, path, query, header, bodyChannel, router)
}

internal operator fun <T> RouterResult<T>.component1(): Route = this.route
internal operator fun <T> RouterResult<T>.component2(): T = this.endpoint


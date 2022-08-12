package com.duncpro.restk

import com.duncpro.jroute.HttpMethod
import com.duncpro.jroute.Path
import com.duncpro.jroute.route.Route
import com.duncpro.jroute.route.StaticRouteElement
import com.duncpro.jroute.route.WildcardRouteElement
import com.duncpro.jroute.router.Router
import com.duncpro.jroute.router.TreeRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

class RestException(val statusCode: Int, cause: Throwable? = null): Exception(cause)

class ResponseBodyContainer(val bodyChannel: Channel<ByteArray>)

suspend fun ResponseBodyContainer.asString(): String {
    val builder = StringBuilder()
    try {
        bodyChannel.consumeAsFlow()
            .onEach { part -> builder.append(String(part)) }
            .collect()
    } catch (e: CancellationException) {
        e.printStackTrace()
    } catch (e: IllegalArgumentException) {
        throw RestException(400, e)
    }

    return builder.toString()
}

data class RestStringFieldValueContainer(val value: String)

fun RestStringFieldValueContainer.asInt(): Int {
    try {
        return Integer.parseInt(value)
    } catch (e: NumberFormatException) {
        throw RestException(400, e)
    }
}

fun RestStringFieldValueContainer.asBoolean(): Boolean {
    try {
        return value.toBooleanStrict()
    } catch (e: IllegalArgumentException) {
        throw RestException(400, e)
    }
}

fun RestStringFieldValueContainer.asDouble(): Double {
    try {
        return value.toDouble()
    } catch (e: NumberFormatException) {
        throw RestException(400, e)
    }
}

fun RestStringFieldValueContainer.asLong(): Long {
    try {
        return value.toLong()
    } catch (e: NumberFormatException) {
        throw RestException(400, e)
    }
}

fun RestStringFieldValueContainer.asString(): String = value

data class RestResponse(
    val statusCode: Int,
    val header: Map<String, List<String>>,
    val body: Flow<ByteArray>?
)

data class RestRequest(
    val path: Map<String, RestStringFieldValueContainer>,
    val query: Map<String, List<RestStringFieldValueContainer>>,
    val header: Map<String, List<RestStringFieldValueContainer>>,
    val body: ResponseBodyContainer?
)

class ResponseBuilderContext(
    var statusCode: Int = 200,
    var headers: MutableMap<String, MutableList<String>> = mutableMapOf(),
    var body: Flow<ByteArray>? = null
)

fun ResponseBuilderContext.body(string: String) {
    this.body = flow { emit(string.toByteArray()) }
}

fun ResponseBuilderContext.header(key: String, value: Any) {
    val values = headers.getOrPut(key) { mutableListOf() }
    values.add(value.toString())
}

fun responseOf(builder: ResponseBuilderContext.() -> Unit): RestResponse {
    val context = ResponseBuilderContext()
    builder(context)
    return RestResponse(context.statusCode, context.headers, context.body)
}

typealias RequestHandler = suspend (RestRequest) -> RestResponse

class RestEndpoint(
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

data class ParameterizedRoute(val anonymous: Route, val parameters: List<String>) {
    init {
        val wildcardCount = anonymous.elements.size
        val parameterCount = parameters.size
        if (wildcardCount != parameterCount) throw IllegalArgumentException()
    }
    companion object
}

private enum class SegmentType { PARAMETER, STATIC }

fun ParameterizedRoute.Companion.parse(routeString: String): ParameterizedRoute {
    var route: Route = Route.ROOT
    val params = mutableListOf<String>()

    routeString.trim().split("/")
        .map { segment -> segment.trim() }
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

fun ParameterizedRoute.extractVariables(path: Path): Map<String, String> {
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

fun ParameterizedRoute.extractVariables(pathString: String) = extractVariables(Path(pathString))

data class EndpointPosition(val method: HttpMethod, val route: ParameterizedRoute)
data class EndpointGroup(val likeEndpoints: Set<RestEndpoint>, val position: EndpointPosition)

fun EndpointGroup.handleRequest(
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: Channel<ByteArray>,
    path: String
): RestResponse {
    val sanitizedHeader = header.mapKeys { entry -> entry.key.lowercase() }
    val requestBodyType: String? = sanitizedHeader["content-type"]?.firstOrNull()
    val acceptableResponseBodyTypes: Set<String> = sanitizedHeader["accept"]?.toSet() ?: emptySet()

    val matchingEndpoint = this.likeEndpoints.asSequence()
        .filter { endpoint -> (endpoint.consumeContentType.isEmpty() && requestBodyType == null)
                || endpoint.consumeContentType.contains(requestBodyType) }
        .filter { endpoint -> (endpoint.produceContentType intersect acceptableResponseBodyTypes).isNotEmpty() }
        .firstOrNull() ?: return RestResponse(400, emptyMap(), null) // TODO: More specialized error response

    val request = RestRequest(
        path = position.route.extractVariables(path).mapValues { (_, raw) -> RestStringFieldValueContainer(raw) },
        query = query.mapValues { (_, rawValues) -> rawValues.map { value -> RestStringFieldValueContainer(value) } },
        header = sanitizedHeader.mapValues { (_, rawValues) -> rawValues }
    )
}

fun createRouter(vararg endpoints: RestEndpoint): Router<EndpointGroup> {
    val router = TreeRouter<EndpointGroup>()
    endpoints
        .groupBy { EndpointPosition(it.method, ParameterizedRoute.parse(it.route)) }
        .mapValues { (position, likeEndpoints) -> EndpointGroup(likeEndpoints.toSet(), position) }
        .forEach { (position, endpointGroup) -> router.addRoute(position.method, position.route.anonymous, endpointGroup) }
    return router
}




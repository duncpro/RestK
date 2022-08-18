package com.duncpro.restk

import com.duncpro.jroute.Path
import com.duncpro.jroute.rest.HttpMethod
import com.duncpro.jroute.rest.RestResource
import com.duncpro.jroute.rest.RestRouteResult
import com.duncpro.jroute.rest.RestRouter
import com.duncpro.jroute.route.Route
import com.duncpro.jroute.router.Router
import com.duncpro.jroute.util.ParameterizedRoute
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.SortedMap
import java.util.TreeMap

private val logger: Logger = LoggerFactory.getLogger("com.duncpro.restk")

class RestException(
    val statusCode: Int = 400,
    cause: Throwable? = null,
    message: String? = null
): Exception(message, cause) {
    val attachedHeaders: SortedMap<String, MutableList<String>> = TreeMap(String.CASE_INSENSITIVE_ORDER)
}


typealias RequestHandler = suspend (RestRequest) -> RestResponse

class RestEndpoint constructor(
    val method: HttpMethod,
    val route: String,
    val consumeContentType: Set<ContentType>,
    val produceContentType: ContentType?,
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
    internal val likeEndpoints: Set<RestEndpoint>,
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
    fun private(originWhitelist: Set<String>, allowedHeaders: Set<String> = setOf("authorization")): CorsPolicy {
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

private fun createPreflightEndpoint(route: Route, corsPolicy: CorsPolicy, router: RestRouter<ContentEndpointGroup>) =
    RestEndpoint(HttpMethod.OPTIONS, route.toString(), emptySet(), null) { preflightRequest ->

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
        val permissionGranted = permissions.allowedMethods.contains(endpoint.method)

        fun attachHeaders(map: MutableMap<String, MutableList<String>>) {
            if (origin != null && permissionGranted) {
                map.getOrPut("Access-Control-Allow-Origin", ::ArrayList).add(origin)
            }
        }

        try {
            val response = endpoint.handler(request)
            attachHeaders(response.header)
            return@RestEndpoint response
        } catch (e: RestException) {
            attachHeaders(e.attachedHeaders)
            throw e
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

suspend fun handleRequest(
    method: HttpMethod,
    path: String,
    query: Map<String, List<String>>,
    header: Map<String, List<String>>,
    body: RequestBody,
    router: RestRouter<ContentEndpointGroup>
): RestResponse {
    val endpointGroup = when (val result = router.route(method, path)) {
        is RestRouteResult.ResourceNotFound<ContentEndpointGroup> -> {
            logger.info("Unable to process request because the requested resource does not exist: ${path}.")
            return RestResponse(404, HashMap(), null)
        }
        is RestRouteResult.UnsupportedMethod<ContentEndpointGroup> -> {
            logger.info("Unable to process request because the requested resource ($path) does not support method: ${method}.")
            return RestResponse(405, HashMap(), null)
        }
        is RestRouteResult.RestRouteMatch<ContentEndpointGroup> -> result.methodEndpoint
        else -> throw AssertionError()
    }

    val request = RestRequest.of(endpointGroup.position.route.extractVariablesMap(Path(path)), query, header, body)
    logger.info("Inbound Request: $method $path: $request")

    val capableConsumerEndpoints = endpointGroup.likeEndpoints
        .filter { endpoint -> ContentTypes.isMatch(request.contentType(), endpoint.consumeContentType) }

    if (capableConsumerEndpoints.isEmpty()) {
       logger.info("Unable to process request because the request payload contains an unsupported media type: " +
               "${request.contentType() ?: "No Content"}.")
        return RestResponse(415, HashMap(), null)
    }

    val accepts = request.accepts().map(QualifiableContentType::contentType).toSet()
    val matchedEndpoint = capableConsumerEndpoints
        .firstOrNull { endpoint -> ContentTypes.isMatch(endpoint.produceContentType, accepts) }

    if (matchedEndpoint == null) {
        logger.info("Unable to process request because the accept header does not contain any supported media types.")
        return RestResponse(406, HashMap(), null)
    }

    return try {
        val response = matchedEndpoint.handler(request)
        logger.info("Outbound Response: $response")
        return response
    } catch (e: RestException) {
        logger.info("An error occurred while processing request.", e)
        RestResponse(e.statusCode, e.attachedHeaders, null)
    }
}
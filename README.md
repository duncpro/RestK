# RestK
[![Build Status](https://app.travis-ci.com/duncpro/RestK.svg?token=Hs9i7xHmw7XfVHT1kBUx&branch=master)](https://app.travis-ci.com/duncpro/RestK)
[![](https://jitpack.io/v/duncpro/restk.svg)](https://jitpack.io/#duncpro/restk)
[![codecov](https://codecov.io/gh/duncpro/RestK/branch/master/graph/badge.svg?token=HEH1Q38EOD)](https://codecov.io/gh/duncpro/RestK)


An extensible, typesafe, reflection-free, non-blocking, serverside framework for writing compliant REST APIs.

The entire framework fits into a single reasonably sized file.
You can get started with RestK in five minutes and master it in thirty.
Contributions are always welcome.

**Note**: This is not an HTTP server library. This framework must be
paired with some HTTP server of your choosing. 

RestK *does*
come with a thin wrapper around the Sun Microsystems HTTP Server which is shipped
with most distributions of the JVM. The Sun HTTP Server platform may be used
when testing an application locally, but not in production.
In production, consider pairing RestK with a battle-tested HTTP server implementation
like Netty or Ktor. 

## Getting Started
### Define an Endpoint
```kotlin
val PublishDocumentEndpoint = endpointOf(HttpMethod.POST, "/docs/{customerId}/docs/{docId}",
    /* consumes = */ setOf("text/plain; charset=utf-8"), /* produces = */ setOf("text/html; charset-utf-8")) { request ->
    val customerId = request.path("customerId").asString()
    val docId = request.path("docId").asInt() /* throws RestException if not an integer */
    val token = request.query("authToken").asLong() /* throws RestException if query arg not provided or not long */
    val timestamp = request.query("timestamp").asLong() /* throws RestException if header value not provided or not long */
     /* throws RestException if body not provided or not deserializable to String of charset) */
    val docContents = request.body.asString()
    // TODO: Business Logic
    return@endpointOf responseOf {
        statusCode = 201 // by default 200
        body("<p>${docContents}</p>", Charsets.UTF_8)
    }
}
```
### Handle a Request
```kotlin
val platformRequest = TODO("Choose a platform: AWS Lambda/API Gateway, Undertow, etc.")

// This function is provided by RestK
handleRequest(
    method = HttpMethod.valueOf(platformRequest.method.uppercase()),
    path = platformRequest.path, // String
    query = platformRequest.query, // or parse query params yourself if not provided by platform
    header = platformRequest.header, // Map<String, List<String>>
    body = MemoryRequestBodyContainer(platformRequest.body),
    router = routerOf(PublishDocumentEndpoint /*, ... */) // routerOf provided by RestK
)
```

### Testing & Prototyping Locally
RestK comes with a thin wrapper around the Sun Microsystems HTTP server which ships with most distributions of the JVM.
This is intended to aid in local-testing and prototyping. 
```kotlin
val router: Router<EndpointGroup> = TODO()
httpServerOf(router, InetSocketAddress(8080)).start()
```

### Docs
API Documentation for the master branch can be found [here](https://duncpro.github.io/RestK/-rest-k/com.duncpro.restk/index.html).
# RestK
An extensible, typesafe, reflection-free, non-blocking, serverside REST framework for Kotlin.

The entire codebase fits into a single reasonably sized file.
You can get started with RestK in five minutes and master it in thirty.
Contributions are always welcome.

## Getting Started
### Define an Endpoint
```kotlin
val PublishDocumentEndpoint = endpointOf(HttpMethod.POST, "/docs/{customerId}/docs/{docId}",
    /* consumes = */ setOf("text/plain"), /* produces = */ setOf("text/html")) { request ->
    val customerId = request.path("customerId").asString()
    val docId = request.path("docId").asInt() /* throws RestException if not an integer */
    val token = request.query("authToken").asLong() /* throws RestException if query arg not provided or not long */
    val timestamp = request.query("timestamp").asLong() /* throws RestException if header value not provided or not long */
     /* throws RestException if body not provided or not deserializable to String of charset) */
    val docContents = request.body.asString()
    
    // TODO: Business Logic
    return "<p>${docContents}</p>"
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
    body = platformRequest.body, /* as ByteArray, or as Channel<ByteArray>, or null if bodiless */
    router = routerOf(PublishDocumentEndpoint /*, ... */) // routerOf provided by RestK
)
```
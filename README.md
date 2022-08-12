# RestK
A small, typesafe, async, serverside REST framework for Kotlin.

### Content Negotiation
Serialization of responses is performed implicitly by the framework.
All producable content types must be registered.
```kotlin
ProducableContentType(
    contentType = "application/json",
    // Response body objects which match this predicate
    // are eligible for serialization to the given HTTP content
    // type using the given serializer function.
    matchKotlinType = { type.isDataClass() },
    serialize = { Json.encode(it) },
)
```
Deserialization of request bodies is performed implicitly by the framework.
All acceptable content types must be registered.
```kotlin
AcceptableContentType(
    contentType = "application/json",
    // Request body objects which match this predicate
    // are eligible for deserialization to the given HTTP 
    // content type.
    matchKotlinType = { type.isDataClass() },
    deserializer = { Json.decode(it) }
)
```

To avoid wasted computation, Content Negotiation is performed before the handler function
is even invoked.

### Endpoints
Each `Endpoint` is created using the top-level `defineEndpoint` method.
```kotlin
val CreatePetRestEndpoint = endpointOf(POST, "/customers/{customerId}/pets/{petId}", 
    consume = "application/json", produce = "application/json") {
    
    val customerId = request.path["customerId"].asLong()
    val petId = request.path["petId"].asLong()
    val accessKey = request.query["accessKey"].asString()
    val timestamp = request.header("timeout").asDate()
    val account = authenticate(request)
    val body = json.decode(request.body)
    // TODO: Business Logic 

    responseOf {
        statusCode = 200
        body = json.encode(MySerializableObj())
        header("")
    }
}
```

### Integration with Platform
The `handleRequest` method will deserialize the given request, invoke the corresponding endpoint method,
and serialize the response.
```kotlin
val response = handleRequest(
    request = convertPlatformRequestToRestRequest(platformRequest),
    producableContentTypes = listOf<ProducableContentType>(),
    acceptableContentTypes = listOf<AcceptableContentType>(),
    endpoints = setOf<Endpoint>(),
)
```

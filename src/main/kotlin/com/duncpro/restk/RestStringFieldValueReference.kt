package com.duncpro.restk

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
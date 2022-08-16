package com.duncpro.restk

import java.nio.charset.Charset
import java.nio.charset.UnsupportedCharsetException
import java.text.DecimalFormat
import java.util.Objects

object ContentTypes {
    val ANY = ContentType("*", "*", Charsets.UTF_8)

    object Application {
        val JSON = ContentType("application", "json", Charsets.UTF_8)
    }
    object Text {
        val PLAIN = ContentType("text", "plain", Charsets.UTF_8)
        val HTML = ContentType("text", "html", Charsets.UTF_8)
    }

    fun isMatch(produced: ContentType?, consumable: Set<ContentType>): Boolean {
        if (produced == null) return consumable.contains(ANY) || consumable.isEmpty()
        return consumable.any { isMatch(produced, it) }
    }

    fun isMatch(a: ContentType, b: ContentType): Boolean {
        val mimeTypeMatch = a.mimeType == b.mimeType || setOf(a.mimeType, b.mimeType).contains("*")
        val mimeSubTypeMatch = (a.mimeSubType == b.mimeSubType) || setOf(a.mimeSubType, b.mimeSubType).contains("*")
        val charsetMatch = a.charset == b.charset
        return mimeTypeMatch && mimeSubTypeMatch && charsetMatch
    }
}

class MalformedContentTypeStringException: Exception()

class ContentType {
    val mimeType: String
    val mimeSubType: String
    val charsetName: String

    /**
     * Creates a new [ContentType] instance with an arbitrary mime-type, mime-subtype, and charset.
     * It is possible to create [ContentType]s which will result in an error when accessing [ContentType.charset].
     * Therefore, the caller should take care to ensure the given charset is supported on the current platform.
     *
     * To achieve case-insensitivity, the provided string arguments will be converted to lowercase text.
     */
    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor(mimeType: String, mimeSubType: String, charset: String) {
        this.mimeType = mimeType.lowercase()
        this.mimeSubType = mimeSubType.lowercase()
        this.charsetName = charset.lowercase()
    }

    /**
     * By default, [Charsets.UTF_8] is used, since it dominates the modern web.
     * See the <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Accept-Charset">Mozilla Developer Network
     * Article on Charset Encodings for more information</a>
     */
    constructor(mimeType: String, mimeSubType: String, charset: Charset = Charsets.UTF_8)
        : this(mimeType, mimeSubType, charset.name())



    /**
     * Returns the Java [Charset] for this [ContentType], if [charsetName] describes a charset which is not
     * supported on this system, [UnsupportedCharsetException] is thrown.
     */
    val charset get(): Charset = Charset.forName(charsetName.uppercase())

    override fun equals(other: Any?): Boolean {
        if (other !is ContentType) return false
        return other.mimeType == this.mimeType
                && other.mimeSubType == this.mimeSubType
                && other.charsetName == this.charsetName
    }

    override fun hashCode(): Int {
        return Objects.hash(mimeType, mimeSubType, charsetName)
    }

    /**
     * Compiles this [ContentType] into a valid HTTP Content-Type element, such that it can be embedded
     * directly into a request or response header.
     */
    override fun toString(): String {
        return "${this.mimeType}/${this.mimeSubType};charset=${charsetName}"
    }

    companion object {
        internal fun parse(element: GenericElement): ContentType {
            val (fullMimeType, arguments) = element
            val mimeParts = fullMimeType.split("/")
            if (mimeParts.size != 2) throw MalformedContentTypeStringException()

            return ContentType(
                mimeType = mimeParts[0],
                mimeSubType = mimeParts[1],
                charset = arguments["charset"] ?: Charsets.UTF_8.name()
            )
        }

        /**
         * Parses the given HTTP Content-Type element (for example: application/json;charset=utf-8).
         * Content-Type elements are case-insensitive, and must contain at least a mime-type and mime-subtype.
         * Optionally, an element can include a variety of different parameters, one of them being charset.
         * If no charset is explicitly defined, then [Charsets.UTF_8] is assumed. UTF-8 is used as a default
         * by the vast majority of web browsers and HTTP clients.
         *
         * If either the mime-type or mime-subtype is missing, this function throws [MalformedContentTypeStringException].
         * If a parameter-argument pair is malformed (for example, has an equals sign but no value),
         * [MalformedContentTypeStringException] is also thrown.
         *
         * See <a href="https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Content-Type">Mozilla Developer
         * Network Explanation</a> for more information.
         */
        @Throws(MalformedContentTypeStringException::class)
        @Suppress("RemoveRedundantQualifierName")
        fun parse(string: String) = parseGeneric(string)?.let { ContentType.parse(it) }
    }
}

class QualifiableContentType {
    val contentType: ContentType
    private val qualityString: String

    @Suppress("ConvertSecondaryConstructorToPrimary")
    constructor(contentType: ContentType, quality: Double?) {
        this.contentType = contentType
        this.qualityString = quality?.let(decimalFormat::format) ?: "1"
    }

    val quality: Double get() = qualityString.toDouble()

    companion object {
        // For format justification see:
        // https://developer.mozilla.org/en-US/docs/Glossary/Quality_values
        private val decimalFormat = DecimalFormat("#.###")

        private fun parse(element: GenericElement): QualifiableContentType {
            val contentType = ContentType.parse(element)
            val quality = element.arguments["q"]?.toDoubleOrNull()
            return QualifiableContentType(contentType, quality)
        }

        @Suppress("RemoveRedundantQualifierName")
        fun parse(string: String): QualifiableContentType = parseGeneric(string).let { QualifiableContentType.parse(it) }
    }

    override fun hashCode(): Int {
        return Objects.hash(contentType, qualityString)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is QualifiableContentType) return false
        return other.contentType == this.contentType && other.quality == this.quality
    }

    override fun toString(): String = "$contentType;q=$qualityString"
}

internal data class GenericElement(val first: String, val arguments: Map<String, String>)

private fun parseGeneric(str: String): GenericElement {
    val components = str.lowercase().replace(" ", "").split(";").toMutableList()
    val first = components.removeFirstOrNull() ?: throw MalformedContentTypeStringException()
    val arguments = mutableMapOf<String, String>()
    for (component in components) {
        if (component.isEmpty()) continue
        val split = component.split("=")
        if (split.size != 2) throw MalformedContentTypeStringException()
        arguments[split[0]] = split[1]
    }
    return GenericElement(first, arguments)
}
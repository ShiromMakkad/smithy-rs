/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators.http

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.shapes.CollectionShape
import software.amazon.smithy.model.shapes.DocumentShape
import software.amazon.smithy.model.shapes.ListShape
import software.amazon.smithy.model.shapes.MapShape
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.SimpleShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.model.traits.EnumTrait
import software.amazon.smithy.model.traits.MediaTypeTrait
import software.amazon.smithy.model.traits.TimestampFormatTrait
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.asOptional
import software.amazon.smithy.rust.codegen.core.rustlang.autoDeref
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.customize.NamedSectionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.customize.Section
import software.amazon.smithy.rust.codegen.core.smithy.generators.OperationBuildError
import software.amazon.smithy.rust.codegen.core.smithy.generators.operationBuildError
import software.amazon.smithy.rust.codegen.core.smithy.makeOptional
import software.amazon.smithy.rust.codegen.core.smithy.mapRustType
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingDescriptor
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpLocation
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.parse.EventStreamUnmarshallerGenerator
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.UNREACHABLE
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.isPrimitive
import software.amazon.smithy.rust.codegen.core.util.isStreaming
import software.amazon.smithy.rust.codegen.core.util.outputShape
import software.amazon.smithy.rust.codegen.core.util.redactIfNecessary
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase

/**
 * The type of HTTP message from which we are (de)serializing the HTTP-bound data.
 * We are either:
 *     - deserializing data from an HTTP request (we are a server),
 *     - deserializing data from an HTTP response (we are a client),
 *     - serializing data to an HTTP request (we are a client),
 *     - serializing data to an HTTP response (we are a server),
 */
enum class HttpMessageType {
    REQUEST, RESPONSE
}

/**
 * Class describing an HTTP binding (de)serialization section that can be used in a customization.
 */
sealed class HttpBindingSection(name: String) : Section(name) {
    data class BeforeIteratingOverMapShapeBoundWithHttpPrefixHeaders(val variableName: String, val shape: MapShape) :
        HttpBindingSection("BeforeIteratingOverMapShapeBoundWithHttpPrefixHeaders")
    data class AfterDeserializingIntoAHashMapOfHttpPrefixHeaders(val memberShape: MemberShape) :
        HttpBindingSection("AfterDeserializingIntoAHashMapOfHttpPrefixHeaders")
}

typealias HttpBindingCustomization = NamedSectionGenerator<HttpBindingSection>

/**
 * This class generates Rust functions that (de)serialize data from/to an HTTP message.
 * They are useful for *both*:
 *     - servers (that deserialize HTTP requests and serialize HTTP responses); and
 *     - clients (that deserialize HTTP responses and serialize HTTP requests)
 * because the (de)serialization logic is entirely similar. In the cases where they _slightly_ differ,
 * the [HttpMessageType] enum is used to distinguish.
 *
 * For deserialization logic that is wholly different among clients and servers, use the classes:
 *     - [ServerRequestBindingGenerator] from the `codegen-server` project for servers; and
 *     - [ResponseBindingGenerator] from this project for clients
 * instead.
 *
 * For serialization logic that is wholly different among clients and servers, use the classes:
 *     - [ServerResponseBindingGenerator] from the `codegen-server` project for servers; and
 *     - [RequestBindingGenerator] from this project for clients
 * instead.
 */
class HttpBindingGenerator(
    private val protocol: Protocol,
    private val codegenContext: CodegenContext,
    private val symbolProvider: SymbolProvider,
    private val operationShape: OperationShape,
    /** Function that maps a StructureShape into its builder symbol */
    private val builderSymbol: (StructureShape) -> Symbol,
    private val customizations: List<HttpBindingCustomization> = listOf(),
) {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val codegenTarget = codegenContext.target
    private val model = codegenContext.model
    private val service = codegenContext.serviceShape
    private val index = HttpBindingIndex.of(model)
    private val headerUtil = CargoDependency.smithyHttp(runtimeConfig).toType().member("header")
    private val defaultTimestampFormat = TimestampFormatTrait.Format.EPOCH_SECONDS
    private val dateTime = RuntimeType.DateTime(runtimeConfig).toSymbol().rustType()
    private val httpSerdeModule = RustModule.private("http_serde")

    /**
     * Generate a function to deserialize [binding] from HTTP headers.
     *
     * The name of the resulting function is returned as a String.
     *
     * Generates a function like:
     * ```rust
     * fn parse_foo(headers: &http::HeaderMap) -> Result<Option<String>, ParseError> {
     *   ...
     * }
     * ```
     */
    fun generateDeserializeHeaderFn(binding: HttpBindingDescriptor): RuntimeType {
        check(binding.location == HttpLocation.HEADER)
        val outputT = symbolProvider.toSymbol(binding.member).makeOptional()
        val fnName = "deser_header_${fnName(operationShape, binding)}"
        return RuntimeType.forInlineFun(fnName, httpSerdeModule) {
            rustBlock(
                "pub(crate) fn $fnName(header_map: &#T::HeaderMap) -> std::result::Result<#T, #T::ParseError>",
                RuntimeType.http,
                outputT,
                headerUtil,
            ) {
                rust("let headers = header_map.get_all(${binding.locationName.dq()}).iter();")
                deserializeFromHeader(model.expectShape(binding.member.target), binding.member)
            }
        }
    }

    fun generateDeserializePrefixHeaderFn(binding: HttpBindingDescriptor): RuntimeType {
        check(binding.location == HttpBinding.Location.PREFIX_HEADERS)
        val outputSymbol = symbolProvider.toSymbol(binding.member)
        val target = model.expectShape(binding.member.target)
        check(target is MapShape)
        val fnName = "deser_prefix_header_${fnName(operationShape, binding)}"
        val inner = RuntimeType.forInlineFun("${fnName}_inner", httpSerdeModule) {
            rustBlock(
                "pub fn ${fnName}_inner(headers: #T::header::ValueIter<http::HeaderValue>) -> std::result::Result<Option<#T>, #T::ParseError>",
                RuntimeType.http,
                symbolProvider.toSymbol(model.expectShape(target.value.target)),
                headerUtil,
            ) {
                deserializeFromHeader(model.expectShape(target.value.target), binding.member)
            }
        }
        val returnTypeSymbol = outputSymbol.mapRustType { it.asOptional() }
        return RuntimeType.forInlineFun(fnName, httpSerdeModule) {
            rustBlock(
                "pub(crate) fn $fnName(header_map: &#T::HeaderMap) -> std::result::Result<#T, #T::ParseError>",
                RuntimeType.http,
                returnTypeSymbol,
                headerUtil,
            ) {
                rust(
                    """
                    let headers = #T::headers_for_prefix(header_map, ${binding.locationName.dq()});
                    let out: std::result::Result<_, _> = headers.map(|(key, header_name)| {
                        let values = header_map.get_all(header_name);
                        #T(values.iter()).map(|v| (key.to_string(), v.expect(
                            "we have checked there is at least one value for this header name; please file a bug report under https://github.com/awslabs/smithy-rs/issues"
                        )))
                    }).collect();
                    """,
                    headerUtil, inner,
                )

                for (customization in customizations) {
                    customization.section(
                        HttpBindingSection.AfterDeserializingIntoAHashMapOfHttpPrefixHeaders(binding.member),
                    )(this)
                }
                rust("out.map(Some)")
            }
        }
    }

    /**
     * Generate a function to deserialize `[binding]` from the request / response payload.
     */
    fun generateDeserializePayloadFn(
        binding: HttpBindingDescriptor,
        errorT: RuntimeType,
        // Deserialize a single structure, union or document member marked as a payload
        payloadParser: RustWriter.(String) -> Unit,
        httpMessageType: HttpMessageType = HttpMessageType.RESPONSE,
    ): RuntimeType {
        check(binding.location == HttpBinding.Location.PAYLOAD)
        val fnName = "deser_payload_${fnName(operationShape, binding)}"
        return RuntimeType.forInlineFun(fnName, httpSerdeModule) {
            if (binding.member.isStreaming(model)) {
                val outputT = symbolProvider.toSymbol(binding.member)
                rustBlock(
                    "pub fn $fnName(body: &mut #T) -> std::result::Result<#T, #T>",
                    RuntimeType.sdkBody(runtimeConfig),
                    outputT,
                    errorT,
                ) {
                    // Streaming unions are Event Streams and should be handled separately
                    val target = model.expectShape(binding.member.target)
                    if (target is UnionShape) {
                        bindEventStreamOutput(operationShape, target)
                    } else {
                        deserializeStreamingBody(binding)
                    }
                }
            } else {
                // The output needs to be Optional when deserializing the payload body or the caller signature
                // will not match.
                val outputT = symbolProvider.toSymbol(binding.member).makeOptional()
                rustBlock("pub fn $fnName(body: &[u8]) -> std::result::Result<#T, #T>", outputT, errorT) {
                    deserializePayloadBody(
                        binding,
                        errorT,
                        structuredHandler = payloadParser,
                        httpMessageType,
                    )
                }
            }
        }
    }

    private fun RustWriter.bindEventStreamOutput(operationShape: OperationShape, targetShape: UnionShape) {
        val unmarshallerConstructorFn = EventStreamUnmarshallerGenerator(
            protocol,
            codegenContext,
            operationShape,
            targetShape,
            builderSymbol,
        ).render()
        rustTemplate(
            """
            let unmarshaller = #{unmarshallerConstructorFn}();
            let body = std::mem::replace(body, #{SdkBody}::taken());
            Ok(#{Receiver}::new(unmarshaller, body))
            """,
            "SdkBody" to RuntimeType.sdkBody(runtimeConfig),
            "unmarshallerConstructorFn" to unmarshallerConstructorFn,
            "Receiver" to RuntimeType.eventStreamReceiver(runtimeConfig),
        )
    }

    private fun RustWriter.deserializeStreamingBody(binding: HttpBindingDescriptor) {
        val member = binding.member
        val targetShape = model.expectShape(member.target)
        check(targetShape is BlobShape)
        rustTemplate(
            """
            // replace the body with an empty body
            let body = std::mem::replace(body, #{SdkBody}::taken());
            Ok(#{ByteStream}::new(body))
            """,
            "ByteStream" to symbolProvider.toSymbol(member), "SdkBody" to RuntimeType.sdkBody(runtimeConfig),
        )
    }

    private fun RustWriter.deserializePayloadBody(
        binding: HttpBindingDescriptor,
        errorSymbol: RuntimeType,
        structuredHandler: RustWriter.(String) -> Unit,
        httpMessageType: HttpMessageType = HttpMessageType.RESPONSE,
    ) {
        val member = binding.member
        val targetShape = model.expectShape(member.target)
        // There is an unfortunate bit of dual behavior caused by an empty body causing the output to be `None` instead
        // of an empty instance of the response type.
        withBlock("(!body.is_empty()).then(||{", "}).transpose()") {
            when (targetShape) {
                is StructureShape, is UnionShape, is DocumentShape -> this.structuredHandler("body")
                is StringShape -> {
                    when (httpMessageType) {
                        HttpMessageType.RESPONSE -> {
                            rustTemplate(
                                "let body_str = std::str::from_utf8(body).map_err(#{error_symbol}::unhandled)?;",
                                "error_symbol" to errorSymbol,
                            )
                        }
                        HttpMessageType.REQUEST -> {
                            rust("let body_str = std::str::from_utf8(body)?;")
                        }
                    }
                    if (targetShape.hasTrait<EnumTrait>()) {
                        if (codegenTarget == CodegenTarget.SERVER) {
                            rust(
                                "Ok(#T::try_from(body_str)?)",
                                symbolProvider.toSymbol(targetShape),
                            )
                        } else {
                            rust(
                                "Ok(#T::from(body_str))",
                                symbolProvider.toSymbol(targetShape),
                            )
                        }
                    } else {
                        rust("Ok(body_str.to_string())")
                    }
                }
                is BlobShape -> rust(
                    "Ok(#T::new(body))",
                    symbolProvider.toSymbol(targetShape),
                )
                // `httpPayload` can be applied to set/map/list shapes.
                // However, none of the AWS protocols support it.
                // Smithy CLI will refuse to build the model if you apply the trait to these shapes, so this branch
                // remains unreachable.
                else -> UNREACHABLE("unexpected shape: $targetShape")
            }
        }
    }

    /**
     * Parse a value from a header.
     * This function produces an expression which produces the precise type required by the target shape.
     */
    private fun RustWriter.deserializeFromHeader(targetShape: Shape, memberShape: MemberShape) {
        val rustType = symbolProvider.toSymbol(targetShape).rustType().stripOuter<RustType.Option>()
        // Normally, we go through a flow that looks for `,`s but that's wrong if the output
        // is just a single string (which might include `,`s.).
        // MediaType doesn't include `,` since it's base64, send that through the normal path
        if (targetShape is StringShape && !targetShape.hasTrait<MediaTypeTrait>()) {
            rust("#T::one_or_none(headers)", headerUtil)
            return
        }
        val (coreType, coreShape) = if (targetShape is CollectionShape) {
            val coreShape = model.expectShape(targetShape.member.target)
            symbolProvider.toSymbol(coreShape).rustType() to coreShape
        } else {
            rustType to targetShape
        }
        val parsedValue = safeName()
        if (coreType == dateTime) {
            val timestampFormat =
                index.determineTimestampFormat(
                    memberShape,
                    HttpBinding.Location.HEADER,
                    defaultTimestampFormat,
                )
            val timestampFormatType = RuntimeType.TimestampFormat(runtimeConfig, timestampFormat)
            rust(
                "let $parsedValue: Vec<${coreType.render()}> = #T::many_dates(headers, #T)?;",
                headerUtil,
                timestampFormatType,
            )
        } else if (coreShape.isPrimitive()) {
            rust(
                "let $parsedValue = #T::read_many_primitive::<${coreType.render()}>(headers)?;",
                headerUtil,
            )
        } else {
            rust(
                "let $parsedValue: Vec<${coreType.render()}> = #T::read_many_from_str(headers)?;",
                headerUtil,
            )
            if (coreShape.hasTrait<MediaTypeTrait>()) {
                rustTemplate(
                    """
                    let $parsedValue: std::result::Result<Vec<_>, _> = $parsedValue
                        .iter().map(|s|
                            #{base_64_decode}(s).map_err(|_|#{header}::ParseError::new("failed to decode base64"))
                            .and_then(|bytes|String::from_utf8(bytes).map_err(|_|#{header}::ParseError::new("base64 encoded data was not valid utf-8")))
                        ).collect();
                    """,
                    "base_64_decode" to RuntimeType.Base64Decode(runtimeConfig),
                    "header" to headerUtil,
                )
                rust("let $parsedValue = $parsedValue?;")
            }
        }
        when (rustType) {
            is RustType.Vec ->
                rust(
                    """
                    Ok(if !$parsedValue.is_empty() {
                        Some($parsedValue)
                    } else {
                        None
                    })
                    """,
                )
            is RustType.HashSet ->
                rust(
                    """
                    Ok(if !$parsedValue.is_empty() {
                        Some($parsedValue.into_iter().collect())
                    } else {
                        None
                    })
                    """,
                )
            else -> {
                if (targetShape is ListShape) {
                    // This is a constrained list shape and we must therefore be generating a server SDK.
                    check(codegenTarget == CodegenTarget.SERVER)
                    check(rustType is RustType.Opaque)
                    rust(
                        """
                        Ok(if !$parsedValue.is_empty() {
                            Some(#T($parsedValue))
                        } else {
                            None
                        })
                        """,
                        symbolProvider.toSymbol(targetShape),
                    )
                } else {
                    check(targetShape is SimpleShape)
                    rustTemplate(
                        """
                        if $parsedValue.len() > 1 {
                            Err(#{header_util}::ParseError::new(format!("expected one item but found {}", $parsedValue.len())))
                        } else {
                            let mut $parsedValue = $parsedValue;
                            Ok($parsedValue.pop())
                        }
                        """,
                        "header_util" to headerUtil,
                    )
                }
            }
        }
    }

    /**
     * Generate a unique name for the deserializer function for a given [operationShape] and HTTP binding.
     */
    // Rename here technically not required, operations and members cannot be renamed.
    private fun fnName(operationShape: OperationShape, binding: HttpBindingDescriptor) =
        "${operationShape.id.getName(service).toSnakeCase()}_${binding.member.container.name.toSnakeCase()}_${binding.memberName.toSnakeCase()}"

    /**
     * Returns a function to set headers on an HTTP message for the given [shape].
     * Returns null if no headers need to be set.
     *
     * [shape] can either be:
     *     - an [OperationShape], in which case the header-bound data is in its input or output shape; or
     *     - an error shape (i.e. a [StructureShape] with the `error` trait), in which case the header-bound data is in the shape itself.
     */
    fun generateAddHeadersFn(
        shape: Shape,
        httpMessageType: HttpMessageType = HttpMessageType.REQUEST,
    ): RuntimeType? {
        val (headerBindings, prefixHeaderBinding) = when (httpMessageType) {
            // Only a single structure member can be bound by `httpPrefixHeaders`, hence the `getOrNull(0)`.
            HttpMessageType.REQUEST -> index.getRequestBindings(shape, HttpLocation.HEADER) to
                index.getRequestBindings(shape, HttpLocation.PREFIX_HEADERS).getOrNull(0)
            HttpMessageType.RESPONSE -> index.getResponseBindings(shape, HttpLocation.HEADER) to
                index.getResponseBindings(shape, HttpLocation.PREFIX_HEADERS).getOrNull(0)
        }

        if (headerBindings.isEmpty() && prefixHeaderBinding == null) {
            return null
        }

        val fnName = "add_headers_${shape.id.getName(service).toSnakeCase()}"
        return RuntimeType.forInlineFun(fnName, httpSerdeModule) {
            // If the shape is an operation shape, the input symbol of the generated function is the input or output
            // shape, which is the shape holding the header-bound data.
            val shapeSymbol = symbolProvider.toSymbol(
                if (shape is OperationShape) {
                    when (httpMessageType) {
                        HttpMessageType.REQUEST -> shape.inputShape(model)
                        HttpMessageType.RESPONSE -> shape.outputShape(model)
                    }
                } else {
                    shape
                },
            )
            val codegenScope = arrayOf(
                "BuildError" to runtimeConfig.operationBuildError(),
                HttpMessageType.REQUEST.name to RuntimeType.HttpRequestBuilder,
                HttpMessageType.RESPONSE.name to RuntimeType.HttpResponseBuilder,
                "Shape" to shapeSymbol,
            )
            rustBlockTemplate(
                """
                pub fn $fnName(
                    input: &#{Shape},
                    mut builder: #{${httpMessageType.name}}
                ) -> std::result::Result<#{${httpMessageType.name}}, #{BuildError}>
                """,
                *codegenScope,
            ) {
                headerBindings.forEach { httpBinding -> renderHeaders(httpBinding) }
                if (prefixHeaderBinding != null) {
                    renderPrefixHeader(prefixHeaderBinding)
                }
                rust("Ok(builder)")
            }
        }
    }

    private fun RustWriter.renderHeaders(httpBinding: HttpBinding) {
        check(httpBinding.location == HttpLocation.HEADER)
        val memberShape = httpBinding.member
        val targetShape = model.expectShape(memberShape.target)
        val memberSymbol = symbolProvider.toSymbol(memberShape)
        val memberName = symbolProvider.toMemberName(memberShape)
        ifSet(targetShape, memberSymbol, "&input.$memberName") { field ->
            listForEach(targetShape, field) { innerField, targetId ->
                val innerMemberType = model.expectShape(targetId)
                if (innerMemberType.isPrimitive()) {
                    val encoder = CargoDependency.smithyTypes(runtimeConfig).toType().member("primitive::Encoder")
                    rust("let mut encoder = #T::from(${autoDeref(innerField)});", encoder)
                }
                val formatted = headerFmtFun(
                    this,
                    innerMemberType,
                    memberShape,
                    innerField,
                    isListHeader = targetShape is CollectionShape,
                )
                val safeName = safeName("formatted")
                write("let $safeName = $formatted;")
                rustBlock("if !$safeName.is_empty()") {
                    rustTemplate(
                        """
                        let header_value = $safeName;
                        let header_value = http::header::HeaderValue::try_from(&*header_value).map_err(|err| {
                            #{invalid_field_error:W}
                        })?;
                        builder = builder.header("${httpBinding.locationName}", header_value);
                        """,
                        "invalid_field_error" to OperationBuildError(runtimeConfig).invalidField(memberName) {
                            rust(
                                """
                                format!(
                                    "`{}` cannot be used as a header value: {}",
                                    &${memberShape.redactIfNecessary(model, "header_value")},
                                    err
                                )
                                """,
                            )
                        },
                    )
                }
            }
        }
    }

    private fun RustWriter.renderPrefixHeader(httpBinding: HttpBinding) {
        check(httpBinding.location == HttpLocation.PREFIX_HEADERS)
        val memberShape = httpBinding.member
        val targetShape = model.expectShape(memberShape.target, MapShape::class.java)
        val memberSymbol = symbolProvider.toSymbol(memberShape)
        val memberName = symbolProvider.toMemberName(memberShape)
        val valueTargetShape = model.expectShape(targetShape.value.target)

        ifSet(targetShape, memberSymbol, "&input.$memberName") { field ->
            for (customization in customizations) {
                customization.section(
                    HttpBindingSection.BeforeIteratingOverMapShapeBoundWithHttpPrefixHeaders(field, targetShape),
                )(this)
            }
            rustTemplate(
                """
                for (k, v) in $field {
                    use std::str::FromStr;
                    let header_name = http::header::HeaderName::from_str(&format!("{}{}", "${httpBinding.locationName}", &k)).map_err(|err| {
                        #{invalid_header_name:W}
                    })?;
                    let header_value = ${headerFmtFun(this, valueTargetShape, memberShape, "v", isListHeader = false)};
                    let header_value = http::header::HeaderValue::try_from(&*header_value).map_err(|err| {
                        #{invalid_header_value:W}
                    })?;
                    builder = builder.header(header_name, header_value);
                }

                """,
                "invalid_header_name" to OperationBuildError(runtimeConfig).invalidField(memberName) {
                    rust("""format!("`{k}` cannot be used as a header name: {err}")""")
                },
                "invalid_header_value" to OperationBuildError(runtimeConfig).invalidField(memberName) {
                    rust(
                        """
                        format!(
                            "`{}` cannot be used as a header value: {}",
                            ${memberShape.redactIfNecessary(model, "v")},
                            err
                        )
                        """,
                    )
                },
            )
        }
    }

    /**
     * Format [member] when used as an HTTP header.
     */
    private fun headerFmtFun(writer: RustWriter, target: Shape, member: MemberShape, targetName: String, isListHeader: Boolean): String {
        fun quoteValue(value: String): String {
            // Timestamp shapes are not quoted in header lists
            return if (isListHeader && !target.isTimestampShape) {
                val quoteFn = writer.format(headerUtil.member("quote_header_value"))
                "$quoteFn($value)"
            } else {
                value
            }
        }
        return when {
            target.isStringShape -> {
                if (target.hasTrait<MediaTypeTrait>()) {
                    val func = writer.format(RuntimeType.Base64Encode(runtimeConfig))
                    "$func(&$targetName)"
                } else {
                    quoteValue("$targetName.as_str()")
                }
            }
            target.isTimestampShape -> {
                val timestampFormat =
                    index.determineTimestampFormat(member, HttpBinding.Location.HEADER, defaultTimestampFormat)
                val timestampFormatType = RuntimeType.TimestampFormat(runtimeConfig, timestampFormat)
                quoteValue("$targetName.fmt(${writer.format(timestampFormatType)})?")
            }
            target.isListShape || target.isMemberShape -> {
                throw IllegalArgumentException("lists should be handled at a higher level")
            }
            target.isPrimitive() -> {
                "encoder.encode()"
            }
            else -> throw CodegenException("unexpected shape: $target")
        }
    }
}
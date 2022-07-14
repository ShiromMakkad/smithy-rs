/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.neighbor.RelationshipDirection
import software.amazon.smithy.model.neighbor.Walker
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.traits.HttpHeaderTrait
import software.amazon.smithy.model.traits.HttpLabelTrait
import software.amazon.smithy.model.traits.HttpPrefixHeadersTrait
import software.amazon.smithy.model.traits.HttpQueryParamsTrait
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpResponseCodeTrait
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.traits.SensitiveTrait
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.rust.codegen.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.rustlang.asType
import software.amazon.smithy.rust.codegen.rustlang.rust
import software.amazon.smithy.rust.codegen.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.rustlang.withBlock
import software.amazon.smithy.rust.codegen.rustlang.withBlockTemplate
import software.amazon.smithy.rust.codegen.server.smithy.ServerCargoDependency
import software.amazon.smithy.rust.codegen.smithy.CoreCodegenContext
import software.amazon.smithy.rust.codegen.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.util.getTrait
import software.amazon.smithy.rust.codegen.util.inputShape
import software.amazon.smithy.rust.codegen.util.outputShape

class ServerHttpSensitivityGenerator(
    private val model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val operation: OperationShape,
    coreCodegenContext: CoreCodegenContext
) {
    private val runtimeConfig = coreCodegenContext.runtimeConfig
    private val codegenScope = arrayOf(
        "SmithyHttpServer" to ServerCargoDependency.SmithyHttpServer(runtimeConfig).asType(),
    )

    private fun renderHeaderClosure(writer: RustWriter, headers: List<HttpHeaderTrait>, prefixHeaders: List<HttpPrefixHeadersTrait>) {
        writer.rustBlock("|name|") {
            rust("let name = name.as_str();")

            if (prefixHeaders.isNotEmpty()) {
                withBlock("let (value, key_suffix) = ", ";") {
                    prefixHeaders.map { it.value }.distinct().forEach {
                        rustTemplate("if name.starts_with(\"$it\") { (true, Some(${it.length})) }")
                        rust("else")
                    }
                    rust("{ (false, None) }")
                }
            } else {
                rust("let value = false;")
                rust("let key_suffix = None;")
            }

            if (headers.isNotEmpty()) {
                withBlock("let value = value || matches!(name,", ");") {
                    val matches = headers.map { it.value }.distinct().map { "\"$it\"" }.joinToString("|")
                    rust(matches)
                }
            }

            rustBlockTemplate("#{SmithyHttpServer}::logging::HeaderMarker", *codegenScope) {
                rust("value, key_suffix")
            }
        }
    }

    private fun renderQueryClosure(writer: RustWriter, queries: List<HttpQueryTrait>) {
        writer.withBlockTemplate("|name| #{SmithyHttpServer}::logging::QueryMarker { key: false, value: matches!(name,", ") }", *codegenScope) {
            val matches = queries.map { it.value }.distinct().map { "\"$it\"" }.joinToString("|")
            rust(matches)
        }
    }

    private fun renderQueryParamsClosure(writer: RustWriter) {
        writer.rustBlock("|_|") {
            rustTemplate(
                "#{SmithyHttpServer}::logging::QueryMarker { key: true, value: true }", *codegenScope
            )
        }
    }

    fun renderPathClosure(writer: RustWriter, indexes: List<Int>) {
        writer.rustBlock("|index|") {
            withBlock("matches!(index,", ")") {
                val matches = indexes.map { "$it" }.joinToString("|")
                rust(matches)
            }
        }
    }

    // Find member shapes which are sensitive and enjoy a trait `T`.
    private inline fun <reified T : Trait> findSensitiveBound(rootShape: Shape): List<MemberShape> {
        return Walker(model)
            .walkShapes(rootShape, {
                // Do not traverse upwards or beyond a sensitive trait
                it.getDirection() == RelationshipDirection.DIRECTED && it.getShape().getTrait<SensitiveTrait>() == null
            })
            .filter {
                it.getTrait<SensitiveTrait>() != null
            }
            .flatMap {
                Walker(model)
                    .walkShapes(it, {
                        // Do not traverse upwards
                        it.getDirection() == RelationshipDirection.DIRECTED
                    })
                    .filter {
                        it.getTrait<T>() != null
                    }
                    .map {
                        it as? MemberShape
                    }
                    .filterNotNull()
            }
    }

    // Find traits (applied to member shapes) which are sensitive.
    private inline fun <reified T : Trait> findSensitiveBoundTrait(rootShape: Shape): List<T> {
        return findSensitiveBound<T>(rootShape).map { it.getTrait<T>() }.filterNotNull()
    }

    fun render(writer: RustWriter) {
        writer.withBlockTemplate("#{SmithyHttpServer}::logging::Sensitivity::new()", ";", *codegenScope) {
            // Sensitivity only applies when HTTP trait is applied to the operation
            val httpTrait = operation.getTrait<HttpTrait>() ?: return@withBlockTemplate

            val inputShape = operation.inputShape(model)

            // URI bindings
            val uriLabels: Map<String, Int> = httpTrait
                .uri
                .getSegments()
                .withIndex()
                .filter { (_, segment) -> segment.isLabel() }
                .map { (index, segment) -> Pair(segment.getContent(), index) }
                .toMap()
            val labeledUriIndexes: List<Int> = findSensitiveBound<HttpLabelTrait>(inputShape)
                .map { uriLabels.get(it.getMemberName()) }
                .filterNotNull()
            if (labeledUriIndexes.isNotEmpty()) {
                withBlock(".path(", ")") {
                    renderPathClosure(writer, labeledUriIndexes)
                }
            }

            // Query string bindings
            val requestQuery = findSensitiveBoundTrait<HttpQueryTrait>(inputShape)
            if (requestQuery.isNotEmpty()) {
                withBlock(".query(", ")") {
                    renderQueryClosure(writer, requestQuery)
                }
            }
            val requestQueryParams = findSensitiveBoundTrait<HttpQueryParamsTrait>(inputShape)
                .isNotEmpty()
            if (requestQueryParams) {
                withBlock(".query(", ")") {
                    renderQueryParamsClosure(writer)
                }
            }

            // Request header bindings
            val requestHttpHeaders = findSensitiveBoundTrait<HttpHeaderTrait>(inputShape)
            val requestPrefixHttpHeaders = findSensitiveBoundTrait<HttpPrefixHeadersTrait>(inputShape)
            if (requestHttpHeaders.isNotEmpty() || requestPrefixHttpHeaders.isNotEmpty()) {
                withBlock(".request_header(", ")") {
                    renderHeaderClosure(writer, requestHttpHeaders, requestPrefixHttpHeaders)
                }
            }

            val outputShape = operation.outputShape(model)

            // Response header bindings
            val responseHttpHeaders = findSensitiveBoundTrait<HttpHeaderTrait>(outputShape)
            val responsePrefixHttpHeaders = findSensitiveBoundTrait<HttpPrefixHeadersTrait>(outputShape)
            if (responseHttpHeaders.isNotEmpty() || responsePrefixHttpHeaders.isNotEmpty()) {
                withBlock(".response_header(", ")") {
                    renderHeaderClosure(writer, responseHttpHeaders, responsePrefixHttpHeaders)
                }
            }

            // Status code bindings
            val hasResponseStatusCode = findSensitiveBoundTrait<HttpResponseCodeTrait>(outputShape).isNotEmpty()
            if (hasResponseStatusCode) {
                rust(".status_code()")
            }
        }
    }
}
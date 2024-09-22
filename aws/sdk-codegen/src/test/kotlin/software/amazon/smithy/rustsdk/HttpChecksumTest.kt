/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import org.junit.jupiter.api.Test
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.plus
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.integrationTest

internal class HttpChecksumTest {
    companion object {
        private const val PREFIX = "\$version: \"2\""
        private val model =
            """
            $PREFIX
            namespace test

            use aws.api#service
            use aws.auth#sigv4
            use aws.protocols#httpChecksum
            use aws.protocols#restJson1
            use smithy.rules#endpointRuleSet

            @service(sdkId: "dontcare")
            @restJson1
            @sigv4(name: "dontcare")
            @auth([sigv4])
            @endpointRuleSet({
                "version": "1.0",
                "rules": [{ "type": "endpoint", "conditions": [], "endpoint": { "url": "https://example.com" } }],
                "parameters": {
                    "Region": { "required": false, "type": "String", "builtIn": "AWS::Region" },
                }
            })
            service TestService {
                version: "2023-01-01",
                operations: [SomeOperation, SomeStreamingOperation]
            }

            @http(uri: "/SomeOperation", method: "POST")
            @optionalAuth
            @httpChecksum(
                requestChecksumRequired: true,
                requestAlgorithmMember: "checksumAlgorithm",
                requestValidationModeMember: "validationMode",
                responseAlgorithms: ["CRC32", "CRC32C", "SHA1", "SHA256"]
            )
            operation SomeOperation {
                input: SomeInput,
                output: SomeOutput
            }

            @input
            structure SomeInput {
                @httpHeader("x-amz-request-algorithm")
                checksumAlgorithm: ChecksumAlgorithm

                @httpHeader("x-amz-response-validation-mode")
                validationMode: ValidationMode

                @httpPayload
                @required
                body: Blob
            }

            @output
            structure SomeOutput {
                @httpPayload
                body: Blob
            }

            @http(uri: "/SomeStreamingOperation", method: "POST")
            @optionalAuth
            @httpChecksum(
                requestChecksumRequired: true,
                requestAlgorithmMember: "checksumAlgorithm",
                requestValidationModeMember: "validationMode",
                responseAlgorithms: ["CRC32", "CRC32C", "SHA1", "SHA256"]
            )
            operation SomeStreamingOperation {
                input: SomeStreamingInput,
                output: SomeStreamingOutput
            }

            @streaming
            blob StreamingBlob

            @input
            structure SomeStreamingInput {
                @httpHeader("x-amz-request-algorithm")
                checksumAlgorithm: ChecksumAlgorithm

                @httpHeader("x-amz-response-validation-mode")
                validationMode: ValidationMode

                @httpPayload
                @required
                body: StreamingBlob
            }

            @output
            structure SomeStreamingOutput {}

            enum ChecksumAlgorithm {
                CRC32
                CRC32C
                //Value not supported by current smithy version
                //CRC64NVME
                SHA1
                SHA256
            }

            enum ValidationMode {
                ENABLED
            }
            """.asSmithyModel()
    }

    @Test
    fun requestChecksumWorks() {
        awsSdkIntegrationTest(model) { context, rustCrate ->

            val rc = context.runtimeConfig
            // Create Writables for all test types
            val checksumRequestTestWritables =
                checksumRequestTests.map { createRequestChecksumCalculationTest(it, context) }.join("\n")
            val checksumResponseSuccTestWritables =
                checksumResponseSuccTests.map { createResponseChecksumValidationSuccessTest(it, context) }.join("\n")
            val checksumResponseFailTestWritables =
                checksumResponseFailTests.map { createResponseChecksumValidationFailureTest(it, context) }.join("\n")
            val checksumStreamingRequestTestWritables =
                streamingRequestTests.map { createStreamingRequestChecksumCalculationTest(it, context) }.join("\n")

            // Shared imports for all test types
            val testBase =
                writable {
                    rustTemplate(
                        """
                        ##![cfg(feature = "test-util")]
                        ##![allow(unused_imports)]

                        use #{Blob};
                        use #{Region};
                        use #{pretty_assertions}::assert_eq;
                        use #{SdkBody};
                        use std::io::Write;
                        use http_body::Body;
                        """,
                        *preludeScope,
                        "Blob" to RuntimeType.smithyTypes(rc).resolve("Blob"),
                        "Region" to AwsRuntimeType.awsTypes(rc).resolve("region::Region"),
                        "pretty_assertions" to CargoDependency.PrettyAssertions.toType(),
                        "SdkBody" to RuntimeType.smithyTypes(rc).resolve("body::SdkBody"),
                    )
                }

            // Create one integ test per test type
            rustCrate.integrationTest("request_checksums") {
                testBase.plus(checksumRequestTestWritables)()
            }

            rustCrate.integrationTest("response_checksums_success") {
                testBase.plus(checksumResponseSuccTestWritables)()
            }

            rustCrate.integrationTest("response_checksums_fail") {
                testBase.plus(checksumResponseFailTestWritables)()
            }

            rustCrate.integrationTest("streaming_request_checksums") {
                testBase.plus(checksumStreamingRequestTestWritables)()
            }
        }
    }

    /**
     * Generate tests where the request checksum is calculated correctly
     */
    private fun createRequestChecksumCalculationTest(
        testDef: RequestChecksumCalculationTest,
        context: ClientCodegenContext,
    ): Writable {
        val rc = context.runtimeConfig
        val moduleName = context.moduleUseName()
        val algoLower = testDef.checksumAlgorithm.lowercase()
        // If the algo is Crc32 don't explicitly set it to test that the default is correctly set
        val setChecksumAlgo =
            if (testDef.checksumAlgorithm != "Crc32") {
                ".checksum_algorithm($moduleName::types::ChecksumAlgorithm::${testDef.checksumAlgorithm})"
            } else {
                ""
            }
        return writable {
            rustTemplate(
                """
                //${testDef.docs}
                ##[#{tokio}::test]
                async fn ${algoLower}_request_checksums_work() {
                    let (http_client, rx) = #{capture_request}(None);
                    let config = $moduleName::Config::builder()
                        .region(Region::from_static("doesntmatter"))
                        .with_test_defaults()
                        .http_client(http_client)
                        .build();

                    let client = $moduleName::Client::from_conf(config);
                    let _ = client.some_operation()
                    .body(Blob::new(b"${testDef.requestPayload}"))
                    $setChecksumAlgo
                    .send()
                    .await;
                    let request = rx.expect_request();
                    let ${algoLower}_header = request.headers()
                        .get("x-amz-checksum-$algoLower")
                        .expect("$algoLower header should exist");

                    assert_eq!(${algoLower}_header, "${testDef.checksumHeader}");

                    let algo_header = request.headers()
                        .get("x-amz-request-algorithm")
                        .expect("algo header should exist");

                    assert_eq!(algo_header, "${testDef.algoHeader}");
                }
                """,
                *preludeScope,
                "tokio" to CargoDependency.Tokio.toType(),
                "capture_request" to RuntimeType.captureRequest(rc),
            )
        }
    }

    /**
     * Generate tests where the request is streaming and checksum is calculated correctly
     */
    private fun createStreamingRequestChecksumCalculationTest(
        testDef: StreamingRequestChecksumCalculationTest,
        context: ClientCodegenContext,
    ): Writable {
        val rc = context.runtimeConfig
        val moduleName = context.moduleUseName()
        val algoLower = testDef.checksumAlgorithm.lowercase()
        // If the algo is Crc32 don't explicitly set it to test that the default is correctly set
        val setChecksumAlgo =
            if (testDef.checksumAlgorithm != "Crc32") {
                ".checksum_algorithm($moduleName::types::ChecksumAlgorithm::${testDef.checksumAlgorithm})"
            } else {
                ""
            }
        return writable {
            rustTemplate(
                """
                //${testDef.docs}
                ##[#{tokio}::test]
                async fn ${algoLower}_request_checksums_work() {
                    let (http_client, rx) = #{capture_request}(None);
                    let config = $moduleName::Config::builder()
                        .region(Region::from_static("doesntmatter"))
                        .with_test_defaults()
                        .http_client(http_client)
                        .build();

                    let client = $moduleName::Client::from_conf(config);

                    let mut file = tempfile::NamedTempFile::new().unwrap();
                    file.as_file_mut()
                    .write_all("${testDef.requestPayload}".as_bytes())
                    .unwrap();

                    let streaming_body = aws_smithy_types::byte_stream::ByteStream::read_from()
                        .path(&file)
                        .build()
                        .await
                        .unwrap();

                    let _operation = client
                        .some_streaming_operation()
                        .body(streaming_body)
                        $setChecksumAlgo
                        .send()
                        .await;


                    let request = rx.expect_request();

                    let headers = request.headers();

                    assert_eq!(
                        headers.get("x-amz-trailer").unwrap(),
                        "x-amz-checksum-$algoLower",
                    );
                    assert_eq!(headers.get("content-encoding").unwrap(), "aws-chunked");

                    let mut body = request.body().try_clone().expect("body is retryable");

                    let mut body_data = bytes::BytesMut::new();
                    while let Some(data) = body.data().await {
                        body_data.extend_from_slice(&data.unwrap())
                    }

                    let body_string = std::str::from_utf8(&body_data).unwrap();
                    assert!(body_string.contains("x-amz-checksum-$algoLower:${testDef.trailerChecksum}"));
                }
                """,
                *preludeScope,
                "tokio" to CargoDependency.Tokio.toType(),
                "capture_request" to RuntimeType.captureRequest(rc),
            )
        }
    }

    /**
     * Generate tests where the response checksum validates successfully
     */
    private fun createResponseChecksumValidationSuccessTest(
        testDef: ResponseChecksumValidationSuccessTest,
        context: ClientCodegenContext,
    ): Writable {
        val rc = context.runtimeConfig
        val moduleName = context.moduleUseName()
        val algoLower = testDef.checksumAlgorithm.lowercase()
        return writable {
            rustTemplate(
                """
                //${testDef.docs}
                ##[::tokio::test]
                async fn ${algoLower}_response_checksums_work() {
                    let (http_client, _rx) = #{capture_request}(Some(
                        http::Response::builder()
                            .header("x-amz-checksum-$algoLower", "${testDef.checksumHeaderValue}")
                            .body(SdkBody::from("${testDef.responsePayload}"))
                            .unwrap(),
                    ));
                    let config = $moduleName::Config::builder()
                        .region(Region::from_static("doesntmatter"))
                        .with_test_defaults()
                        .http_client(http_client)
                        .build();

                    let client = $moduleName::Client::from_conf(config);
                    let res = client
                        .some_operation()
                        .body(Blob::new(b"Doesn't matter."))
                        .checksum_algorithm($moduleName::types::ChecksumAlgorithm::${testDef.checksumAlgorithm})
                        .validation_mode($moduleName::types::ValidationMode::Enabled)
                        .send()
                        .await;
                    assert!(res.is_ok())
                }
                """,
                *preludeScope,
                "tokio" to CargoDependency.Tokio.toType(),
                "capture_request" to RuntimeType.captureRequest(rc),
            )
        }
    }

    /**
     * Generate tests where the response checksum fails to validate
     */
    private fun createResponseChecksumValidationFailureTest(
        testDef: ResponseChecksumValidationFailureTest,
        context: ClientCodegenContext,
    ): Writable {
        val rc = context.runtimeConfig
        val moduleName = context.moduleUseName()
        val algoLower = testDef.checksumAlgorithm.lowercase()
        return writable {
            rustTemplate(
                """
                //${testDef.docs}
                ##[::tokio::test]
                async fn ${algoLower}_response_checksums_work() {
                    let (http_client, _rx) = #{capture_request}(Some(
                        http::Response::builder()
                            .header("x-amz-checksum-$algoLower", "${testDef.checksumHeaderValue}")
                            .body(SdkBody::from("${testDef.responsePayload}"))
                            .unwrap(),
                    ));
                    let config = $moduleName::Config::builder()
                        .region(Region::from_static("doesntmatter"))
                        .with_test_defaults()
                        .http_client(http_client)
                        .build();

                    let client = $moduleName::Client::from_conf(config);
                    let res = client
                        .some_operation()
                        .body(Blob::new(b"Doesn't matter."))
                        .checksum_algorithm($moduleName::types::ChecksumAlgorithm::${testDef.checksumAlgorithm})
                        .validation_mode($moduleName::types::ValidationMode::Enabled)
                        .send()
                        .await;

                    assert!(res.is_err());

                    let boxed_err = res
                        .unwrap_err()
                        .into_source()
                        .unwrap()
                        .downcast::<aws_smithy_checksums::body::validate::Error>();
                    let typed_err = boxed_err.as_ref().unwrap().as_ref();

                    match typed_err {
                        aws_smithy_checksums::body::validate::Error::ChecksumMismatch { actual, .. } => {
                            let calculated_checksum = aws_smithy_types::base64::encode(actual);
                            assert_eq!(calculated_checksum, "${testDef.calculatedChecksum}");
                        }
                        _ => panic!("Unknown error type in checksum validation"),
                    };
                }
                """,
                *preludeScope,
                "tokio" to CargoDependency.Tokio.toType(),
                "capture_request" to RuntimeType.captureRequest(rc),
            )
        }
    }
}

// Classes and data for test definitions

data class RequestChecksumCalculationTest(
    val docs: String,
    val requestPayload: String,
    val checksumAlgorithm: String,
    val algoHeader: String,
    val checksumHeader: String,
)

val checksumRequestTests =
    listOf(
        RequestChecksumCalculationTest(
            "CRC32 checksum calculation works.",
            "Hello world",
            "Crc32",
            "CRC32",
            "i9aeUg==",
        ),
        RequestChecksumCalculationTest(
            "CRC32C checksum calculation works.",
            "Hello world",
            "Crc32C",
            "CRC32C",
            "crUfeA==",
        ),
        /* We do not yet support Crc64Nvme checksums
         RequestChecksumCalculationTest(
         "CRC64NVME checksum calculation works.",
         "Hello world",
         "Crc64Nvme",
         "CRC64NVME",
         "uc8X9yrZrD4=",
         ),
         */
        RequestChecksumCalculationTest(
            "SHA1 checksum calculation works.",
            "Hello world",
            "Sha1",
            "SHA1",
            "e1AsOh9IyGCa4hLN+2Od7jlnP14=",
        ),
        RequestChecksumCalculationTest(
            "SHA256 checksum calculation works.",
            "Hello world",
            "Sha256",
            "SHA256",
            "ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=",
        ),
    )

data class StreamingRequestChecksumCalculationTest(
    val docs: String,
    val requestPayload: String,
    val checksumAlgorithm: String,
    val trailerChecksum: String,
)

val streamingRequestTests =
    listOf(
        StreamingRequestChecksumCalculationTest(
            "CRC32 streaming checksum calculation works.",
            "Hello world",
            "Crc32",
            "i9aeUg==",
        ),
        StreamingRequestChecksumCalculationTest(
            "CRC32C streaming checksum calculation works.",
            "Hello world",
            "Crc32C",
            "crUfeA==",
        ),
//    StreamingRequestChecksumCalculationTest(
//        "CRC64NVME streaming checksum calculation works.",
//        "Hello world",
//        "Crc64Nvme",
//        "uc8X9yrZrD4=",
//    ),
        StreamingRequestChecksumCalculationTest(
            "SHA1 streaming checksum calculation works.",
            "Hello world",
            "Sha1",
            "e1AsOh9IyGCa4hLN+2Od7jlnP14=",
        ),
        StreamingRequestChecksumCalculationTest(
            "SHA256 streaming checksum calculation works.",
            "Hello world",
            "Sha256",
            "ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=",
        ),
    )

data class ResponseChecksumValidationSuccessTest(
    val docs: String,
    val responsePayload: String,
    val checksumAlgorithm: String,
    val checksumHeaderValue: String,
)

val checksumResponseSuccTests =
    listOf(
        ResponseChecksumValidationSuccessTest(
            "Successful payload validation with CRC32 checksum.",
            "Hello world",
            "Crc32",
            "i9aeUg==",
        ),
        ResponseChecksumValidationSuccessTest(
            "Successful payload validation with Crc32C checksum.",
            "Hello world",
            "Crc32C",
            "crUfeA==",
        ),
        /*
        ResponseChecksumValidationSuccessTest(
            "Successful payload validation with Crc64Nvme checksum.",
            "Hello world",
            "Crc64Nvme",
            "uc8X9yrZrD4=",
        ),*/
        ResponseChecksumValidationSuccessTest(
            "Successful payload validation with Sha1 checksum.",
            "Hello world",
            "Sha1",
            "e1AsOh9IyGCa4hLN+2Od7jlnP14=",
        ),
        ResponseChecksumValidationSuccessTest(
            "Successful payload validation with Sha256 checksum.",
            "Hello world",
            "Sha256",
            "ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=",
        ),
    )

data class ResponseChecksumValidationFailureTest(
    val docs: String,
    val responsePayload: String,
    val checksumAlgorithm: String,
    val checksumHeaderValue: String,
    val calculatedChecksum: String,
)

val checksumResponseFailTests =
    listOf(
        ResponseChecksumValidationFailureTest(
            "Failed payload validation with CRC32 checksum.",
            "Hello world",
            "Crc32",
            "bm90LWEtY2hlY2tzdW0=",
            "i9aeUg==",
        ),
        ResponseChecksumValidationFailureTest(
            "Failed payload validation with CRC32C checksum.",
            "Hello world",
            "Crc32C",
            "bm90LWEtY2hlY2tzdW0=",
            "crUfeA==",
        ),
        /*
        ResponseChecksumValidationFailureTest(
            "Failed payload validation with CRC64NVME checksum.",
            "Hello world",
            "Crc64Nvme",
            "bm90LWEtY2hlY2tzdW0=",
            "uc8X9yrZrD4=",
        ),*/
        ResponseChecksumValidationFailureTest(
            "Failed payload validation with SHA1 checksum.",
            "Hello world",
            "Sha1",
            "bm90LWEtY2hlY2tzdW0=",
            "e1AsOh9IyGCa4hLN+2Od7jlnP14=",
        ),
        ResponseChecksumValidationFailureTest(
            "Failed payload validation with SHA256 checksum.",
            "Hello world",
            "Sha256",
            "bm90LWEtY2hlY2tzdW0=",
            "ZOyIygCyaOW6GjVnihtTFtIS9PNmskdyMlNKiuyjfzw=",
        ),
    )

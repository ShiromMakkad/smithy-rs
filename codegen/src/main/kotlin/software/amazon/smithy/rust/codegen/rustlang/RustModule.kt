/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.rustlang

data class RustModule(val name: String, val rustMetadata: RustMetadata, val documentation: String?) {
    fun render(writer: RustWriter) {
        documentation?.let { docs -> writer.docs(docs) }
        rustMetadata.render(writer)
        writer.write("mod $name;")
    }

    companion object {
        fun default(name: String, public: Boolean, documentation: String? = null): RustModule {
            return RustModule(name, RustMetadata(public = public), documentation)
        }

        fun public(name: String, documentation: String? = null): RustModule =
            default(name, public = true, documentation = documentation)

        fun private(name: String, documentation: String? = null): RustModule =
            default(name, public = false, documentation = documentation)

        val Config = public("config", documentation = "Configuration for the service.")
        val Error = public("error", documentation = "Errors that can occur when calling the service.")
    }
}
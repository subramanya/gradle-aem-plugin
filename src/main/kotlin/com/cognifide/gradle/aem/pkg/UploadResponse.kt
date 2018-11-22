package com.cognifide.gradle.aem.pkg

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.InputStream

@JsonIgnoreProperties(ignoreUnknown = true)
class UploadResponse private constructor() {

    var isSuccess: Boolean = false

    lateinit var msg: String

    lateinit var path: String

    companion object {

        fun fromJson(json: InputStream): UploadResponse {
            return ObjectMapper().readValue(json, UploadResponse::class.java)
        }
    }
}

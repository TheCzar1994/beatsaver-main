package io.beatmaps.util

import io.beatmaps.cloudflare.CaptchaVerifier
import io.beatmaps.common.json
import io.beatmaps.controllers.UploadException
import io.ktor.client.HttpClient
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveMultipart
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

data class MultipartRequest<U>(val dataMap: Map<String, JsonElement> = emptyMap(), private val recaptchaSuccess: Boolean = false, val fileOutput: U? = null) {
    inline fun <reified T> get() = json.decodeFromJsonElement<T>(JsonObject(dataMap))
    fun validRecaptcha(authType: AuthType) = authType == AuthType.Oauth || recaptchaSuccess
}

private suspend fun <U> handleMultipartInternal(data: MultiPartData, ctx: PipelineContext<*, ApplicationCall>, client: HttpClient, cb: suspend (PartData.FileItem) -> U): MultipartRequest<U> {
    val part = data.readPart()

    return if (part is PartData.FormItem) {
        // Process recaptcha immediately as it is time-critical
        if (part.name.toString() == "recaptcha") {
            handleMultipartInternal(data, ctx, client, cb)
                .copy(
                    recaptchaSuccess = ctx.captchaProvider { provider ->
                        val verifyResponse = withContext(Dispatchers.IO) {
                            CaptchaVerifier.verify(client, provider, part.value, ctx.call.request.origin.remoteHost)
                        }

                        verifyResponse.success || throw UploadException("Could not verify user [${verifyResponse.errorCodes.joinToString(", ")}]")
                    }
                )
        } else {
            val newData = try {
                if (part.value.startsWith("{") && part.value.endsWith("}")) {
                    json.parseToJsonElement(part.value)
                } else {
                    null
                }
            } catch (e: SerializationException) {
                null
            } ?: JsonPrimitive(part.value)

            handleMultipartInternal(data, ctx, client, cb).let {
                it.copy(dataMap = it.dataMap.plus(part.name.toString() to newData))
            }
        }
    } else if (part is PartData.FileItem) {
        handleMultipartInternal(data, ctx, client, cb).copy(fileOutput = cb(part))
    } else if (part != null) {
        handleMultipartInternal(data, ctx, client, cb)
    } else {
        MultipartRequest()
    }
}

suspend fun <U> PipelineContext<*, ApplicationCall>.handleMultipart(client: HttpClient, cb: suspend (PartData.FileItem) -> U) =
    handleMultipartInternal(call.receiveMultipart(), this, client, cb)

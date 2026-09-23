package com.michalkulik.photogallery.util

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Result of a plain HTTP call: the status code plus the body (or an error message). */
data class HttpResult(val code: Int, val body: String) {
    val isSuccess: Boolean get() = code in 200..299
}

/**
 * Minimal HTTP client built on [HttpURLConnection] so the app needs no networking dependency.
 * Every call is blocking and must run off the main thread.
 */
object Http {

    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 30_000

    fun postForm(url: String, params: Map<String, String>, bearer: String? = null): HttpResult {
        val encoded = params.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }
        return execute(url, "POST", encoded.toByteArray(Charsets.UTF_8), "application/x-www-form-urlencoded", bearer)
    }

    fun postJson(url: String, json: String, bearer: String? = null): HttpResult =
        execute(url, "POST", json.toByteArray(Charsets.UTF_8), "application/json", bearer)

    fun getJson(url: String, bearer: String? = null): HttpResult = execute(url, "GET", null, null, bearer)

    fun delete(url: String, bearer: String? = null): HttpResult = execute(url, "DELETE", null, null, bearer)

    private fun execute(
        url: String,
        method: String,
        body: ByteArray?,
        contentType: String?,
        bearer: String?,
    ): HttpResult {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    doOutput = true
                    setFixedLengthStreamingMode(body.size)
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                Logs.w("HTTP $method $url -> $code: ${text.take(500)}")
            }
            HttpResult(code, text)
        } catch (error: IOException) {
            Logs.w("HTTP $method $url failed", error)
            HttpResult(-1, error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Streams [url] into [destination]. Returns true only when the file was fully written,
     * so a truncated download never ends up in the photo cache.
     */
    fun download(url: String, bearer: String?, destination: File): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                Logs.w("Download $url -> $code")
                return false
            }
            destination.parentFile?.mkdirs()
            val temporary = File(destination.parentFile, destination.name + ".part")
            connection.inputStream.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
            true
        } catch (error: IOException) {
            Logs.w("Download $url failed", error)
            false
        } finally {
            connection?.disconnect()
        }
    }
}

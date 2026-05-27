package com.jt.naicenotes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class RecipeScanClient(
    private val url: String,
    private val secret: String,
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun scan(imageBytes: ByteArray): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            if (url.isBlank()) {
                throw IllegalStateException(
                    "Recipe scan not configured. Add RECIPE_SCAN_URL and RECIPE_SCAN_SECRET to local.properties.",
                )
            }

            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "image",
                    "recipe.jpg",
                    imageBytes.toRequestBody("image/jpeg".toMediaTypeOrNull(), 0, imageBytes.size),
                )
                .build()

            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("X-Auth", secret)
                .build()

            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}: ${raw.take(200)}")
                }
                if (raw.isBlank()) throw IOException("Empty response from scan service")
                val parsed = json.decodeFromString<ScanResponse>(raw)
                parsed.ingredients.filter { it.isNotBlank() }
            }
        }
    }
}

@Serializable
private data class ScanResponse(val ingredients: List<String> = emptyList())

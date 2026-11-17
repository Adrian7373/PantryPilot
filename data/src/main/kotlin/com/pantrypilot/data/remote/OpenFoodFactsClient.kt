package com.pantrypilot.data.remote

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** [size] is the package size as the label states it, such as "400 g", or empty. */
data class RemoteProduct(val name: String, val categories: String, val size: String)

/** Looks up packaged products by barcode in the Open Food Facts public database. */
class OpenFoodFactsClient @Inject constructor() {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the product is not in the database. Throws [IOException] when the lookup fails. */
    suspend fun product(barcode: String): RemoteProduct? = withContext(Dispatchers.IO) {
        require(barcode.all(Char::isDigit)) { "Barcode must be digits only" }
        val url = URL("https://world.openfoodfacts.org/api/v2/product/$barcode.json?fields=$FIELDS")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_FOUND -> return@withContext null
                HttpURLConnection.HTTP_OK -> Unit
                else -> throw IOException("Open Food Facts returned HTTP $code")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val product = try {
                json.decodeFromString<Response>(body).product
            } catch (e: SerializationException) {
                throw IOException("Unreadable Open Food Facts response", e)
            } ?: return@withContext null

            val name = listOf(product.brands.substringBefore(','), product.productName)
                .map(String::trim)
                .filter(String::isNotEmpty)
                .joinToString(" ")
            if (name.isEmpty()) return@withContext null
            // Tags look like "en:whole-milks"; keep the English ones as plain words.
            val categories = product.categoriesTags
                .filter { it.startsWith("en:") }
                .joinToString(" ") { it.removePrefix("en:").replace('-', ' ') }
            RemoteProduct(name, categories, product.quantity.trim())
        } finally {
            connection.disconnect()
        }
    }

    @Serializable
    private data class Response(val product: Product? = null)

    @Serializable
    private data class Product(
        @SerialName("product_name") val productName: String = "",
        val brands: String = "",
        @SerialName("categories_tags") val categoriesTags: List<String> = emptyList(),
        val quantity: String = "",
    )

    private companion object {
        const val FIELDS = "product_name,brands,categories_tags,quantity"
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "PantryPilot/0.1 (Android)"
    }
}

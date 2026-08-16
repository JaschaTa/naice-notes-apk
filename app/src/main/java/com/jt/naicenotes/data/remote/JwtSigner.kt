package com.jt.naicenotes.data.remote

import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal HS256 JWT signer for the one webhook that needs it.
 *
 * Hand-rolled rather than pulled from a JWT library: this is ~15 lines of [Mac] plus
 * base64url, and the app's dependency set is deliberately small (OkHttp + serialization,
 * no DI, no ViewModels). Uses `java.util.Base64` rather than `android.util.Base64` so the
 * signer stays exercisable from a plain JVM unit test — the Android one is a stub off-device
 * and would throw "not mocked".
 *
 * Company webhook rules rank JWT above a static bearer secret, and the reason is [exp]:
 * a token lifted off the wire stops working. Note that this does *not* make the secret
 * itself safe — anything shipped inside an APK can be extracted. Short expiry bounds
 * replay; it doesn't hide the passphrase.
 */
internal object JwtSigner {

    /** Long enough to survive a slow mobile round-trip, short enough to bound replay. */
    const val DEFAULT_TTL_SECONDS = 300L

    fun sign(
        secret: String,
        issuer: String,
        nowMillis: Long,
        ttlSeconds: Long = DEFAULT_TTL_SECONDS,
    ): String {
        val issuedAt = nowMillis / MILLIS_PER_SECOND
        val header = """{"alg":"HS256","typ":"JWT"}"""
        // Only fixed keys, an app constant and integers are interpolated here, so no JSON
        // escaping is needed. Never interpolate note text or any other caller-supplied
        // string into this payload without escaping it first.
        val payload = """{"iss":"$issuer","iat":$issuedAt,"exp":${issuedAt + ttlSeconds}}"""
        val signingInput = "${header.base64Url()}.${payload.base64Url()}"
        return "$signingInput.${hmacSha256(secret, signingInput).base64Url()}"
    }

    private fun hmacSha256(secret: String, data: String): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).apply {
            init(SecretKeySpec(secret.toByteArray(), HMAC_ALGORITHM))
        }.doFinal(data.toByteArray())

    /** JWT mandates base64url without padding — plain base64 is rejected by verifiers. */
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    private fun String.base64Url(): String = encoder.encodeToString(toByteArray())

    private fun ByteArray.base64Url(): String = encoder.encodeToString(this)

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val MILLIS_PER_SECOND = 1000L
}

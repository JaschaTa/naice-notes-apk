package com.jt.naicenotes.data.sync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/** A signed-in session as GoTrue hands it out. [expiresAt] is epoch seconds. */
data class AuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val userId: String,
    val email: String,
)

/**
 * Where the session lives. App-private SharedPreferences, excluded from cloud backup and device
 * transfer (see the two rules files in res/xml) so a restored phone never carries a live refresh token.
 * Not EncryptedSharedPreferences: that library is deprecated, and on a single-user sideloaded
 * phone the app sandbox is the boundary that actually holds.
 */
class AuthStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): AuthSession? {
        val access = prefs.getString(K_ACCESS, null) ?: return null
        val refresh = prefs.getString(K_REFRESH, null) ?: return null
        return AuthSession(
            accessToken = access,
            refreshToken = refresh,
            expiresAt = prefs.getLong(K_EXPIRES, 0),
            userId = prefs.getString(K_USER, "").orEmpty(),
            email = prefs.getString(K_EMAIL, "").orEmpty(),
        )
    }

    /** `commit`, not `apply`: a rotated refresh token must be on disk before it's relied on. */
    fun save(s: AuthSession) {
        prefs.edit()
            .putString(K_ACCESS, s.accessToken)
            .putString(K_REFRESH, s.refreshToken)
            .putLong(K_EXPIRES, s.expiresAt)
            .putString(K_USER, s.userId)
            .putString(K_EMAIL, s.email)
            .commit()
    }

    fun clear() {
        prefs.edit().clear().commit()
    }

    companion object {
        /** Named in the backup exclusion rules — keep the two in step. */
        const val PREFS = "naice_sync_auth"
        private const val K_ACCESS = "access_token"
        private const val K_REFRESH = "refresh_token"
        private const val K_EXPIRES = "expires_at"
        private const val K_USER = "user_id"
        private const val K_EMAIL = "email"
    }
}

/**
 * Supabase over plain OkHttp: GoTrue for the emailed sign-in code, PostgREST for rows. No SDK —
 * the same hand-rolled shape as [com.jt.naicenotes.data.remote.InboxPushClient], and the only
 * thing Android needs from Supabase is a few REST calls.
 *
 * The URL and anon key are not secrets (row-level security is the boundary), but they still
 * come from local.properties so the public repo doesn't name the project.
 */
class SupabaseClient(
    baseUrl: String,
    private val anonKey: String,
    private val authStore: AuthStore,
) : SyncRemote {

    val isConfigured: Boolean = baseUrl.isNotBlank() && anonKey.isNotBlank()

    private val base: HttpUrl? = baseUrl.trim().trimEnd('/').takeIf { isConfigured }?.toHttpUrl()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Serialises refreshes: GoTrue rotates the refresh token, so two at once would race. */
    private val refreshLock = Mutex()

    val session: AuthSession? get() = authStore.load()

    // ── Auth ──

    /** Emails a 6-digit code. The account is created here, on first request — if invited. */
    suspend fun requestCode(email: String) {
        authCall("otp", """{"email":${quote(email)},"create_user":true}""")
    }

    suspend fun verifyCode(email: String, code: String): AuthSession {
        val raw = authCall("verify", """{"type":"email","email":${quote(email)},"token":${quote(code)}}""")
        return toSession(json.decodeFromString(TokenResponse.serializer(), raw)).also(authStore::save)
    }

    /** Best-effort server-side revoke; the local session goes regardless. */
    suspend fun signOut() {
        val s = authStore.load()
        authStore.clear()
        val b = base
        if (s == null || b == null) return
        runCatching {
            withContext(Dispatchers.IO) {
                client.newCall(
                    Request.Builder()
                        .url(b.newBuilder().addPathSegments("auth/v1/logout").addQueryParameter("scope", "local").build())
                        .header("apikey", anonKey)
                        .header("Authorization", "Bearer ${s.accessToken}")
                        .post(ByteArray(0).toRequestBody(null))
                        .build(),
                ).execute().close()
            }
        }
    }

    private suspend fun accessToken(forceRefresh: Boolean = false): String = refreshLock.withLock {
        val s = authStore.load() ?: throw SignedOutException()
        val now = System.currentTimeMillis() / 1000
        if (!forceRefresh && s.expiresAt - REFRESH_MARGIN_S > now) return@withLock s.accessToken
        val raw = try {
            authCall("token?grant_type=refresh_token", """{"refresh_token":${quote(s.refreshToken)}}""")
        } catch (e: RemoteException) {
            // A refused refresh token (revoked, reused, expired) can't be retried into working.
            if (e.permanent) {
                authStore.clear()
                throw SignedOutException()
            }
            throw e
        }
        val fresh = toSession(json.decodeFromString(TokenResponse.serializer(), raw))
        authStore.save(fresh)
        fresh.accessToken
    }

    private suspend fun authCall(pathAndQuery: String, body: String): String {
        val b = base ?: throw IllegalStateException("Sync isn't configured in this build")
        val path = pathAndQuery.substringBefore('?')
        val url = b.newBuilder().addPathSegments("auth/v1/$path").apply {
            pathAndQuery.substringAfter('?', "").takeIf { it.isNotEmpty() }?.split('&')?.forEach {
                addQueryParameter(it.substringBefore('='), it.substringAfter('='))
            }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("apikey", anonKey)
            .post(body.toRequestBody(JSON))
            .build()
        return execute(request)
    }

    private fun toSession(t: TokenResponse) = AuthSession(
        accessToken = t.accessToken,
        refreshToken = t.refreshToken,
        expiresAt = t.expiresAt ?: (System.currentTimeMillis() / 1000 + t.expiresIn),
        userId = t.user.id,
        email = t.user.email.orEmpty(),
    )

    // ── SyncRemote ──

    override suspend fun listSpaces(): List<SpaceRow> =
        get("spaces", listOf("select" to "id,name,kind,created_by"), ListSerializer(SpaceRow.serializer()))

    override suspend fun horizon(): Long =
        get("sync_horizon", listOf("select" to "min_rev"), ListSerializer(Horizon.serializer()))
            .firstOrNull()?.minRev ?: 0L

    override suspend fun sectionsSince(since: Long) = pageByRev("sections", "*", since, emptyList(), SectionRow.serializer()) { it.rev }

    override suspend fun itemsSince(since: Long) = pageByRev("items", "*", since, emptyList(), ItemRow.serializer()) { it.rev }

    override suspend fun sectionsInSpaces(spaceIds: List<String>): List<SectionRow> {
        if (spaceIds.isEmpty()) return emptyList()
        return pageByRev("sections", "*", 0, listOf("space_id" to inList(spaceIds)), SectionRow.serializer()) { it.rev }
    }

    override suspend fun itemsInSpaces(spaceIds: List<String>): List<ItemRow> {
        if (spaceIds.isEmpty()) return emptyList()
        // The embedded section only exists to filter by its space; ignoreUnknownKeys drops it.
        return pageByRev(
            "items", "*,sections!inner(space_id)", 0,
            listOf("sections.space_id" to inList(spaceIds)), ItemRow.serializer(),
        ) { it.rev }
    }

    override suspend fun upsertSections(rows: List<SectionRow>) =
        upsert("sections", json.encodeToString(ListSerializer(SectionRow.serializer()), rows), rows.isEmpty())

    override suspend fun upsertItems(rows: List<ItemRow>) =
        upsert("items", json.encodeToString(ListSerializer(ItemRow.serializer()), rows), rows.isEmpty())

    override suspend fun tombstone(table: SyncRemote.Table, ids: List<String>) {
        if (ids.isEmpty()) return
        authorized { token ->
            Request.Builder()
                .url(rest(table.path, listOf("id" to inList(ids))))
                .rest(token)
                .header("Prefer", "return=minimal")
                .patch("""{"deleted_at":${quote(Instant.now().toString())}}""".toRequestBody(JSON))
                .build()
        }
    }

    // ── Sharing (server functions in supabase/migrations/…0002_section_sharing.sql) ──

    /** Shares one of your sections. The first share moves it into a new shared space. */
    suspend fun shareSection(sectionUuid: String, email: String) {
        rpc("share_section", """{"p_section":${quote(sectionUuid)},"p_email":${quote(email)}}""")
    }

    /** Back into your personal space; the shared space and everyone else's access go. */
    suspend fun unshareSection(sectionUuid: String) {
        rpc("unshare_section", """{"p_section":${quote(sectionUuid)}}""")
    }

    suspend fun leaveSpace(spaceId: String) {
        rpc("leave_space", """{"p_space":${quote(spaceId)}}""")
    }

    /** Everyone in your shared spaces, plus open invites. */
    suspend fun spacePeople(): List<SpacePerson> =
        json.decodeFromString(ListSerializer(SpacePerson.serializer()), rpc("space_people", "{}"))

    private suspend fun rpc(fn: String, body: String): String = authorized { token ->
        Request.Builder()
            .url(rest("rpc/$fn", emptyList()))
            .rest(token)
            .post(body.toRequestBody(JSON))
            .build()
    }

    private suspend fun upsert(table: String, body: String, empty: Boolean) {
        if (empty) return
        authorized { token ->
            Request.Builder()
                .url(rest(table, listOf("on_conflict" to "id")))
                .rest(token)
                .header("Prefer", "resolution=merge-duplicates,return=minimal")
                .post(body.toRequestBody(JSON))
                .build()
        }
    }

    private suspend fun <T> get(
        table: String,
        query: List<Pair<String, String>>,
        serializer: kotlinx.serialization.KSerializer<List<T>>,
    ): List<T> {
        val raw = authorized { token -> Request.Builder().url(rest(table, query)).rest(token).get().build() }
        return json.decodeFromString(serializer, raw)
    }

    private suspend fun <T> pageByRev(
        table: String,
        columns: String,
        since: Long,
        filters: List<Pair<String, String>>,
        serializer: kotlinx.serialization.KSerializer<T>,
        revOf: (T) -> Long?,
    ): List<T> {
        val out = mutableListOf<T>()
        var cursor = since
        while (true) {
            val page = get(
                table,
                listOf("select" to columns, "rev" to "gt.$cursor", "order" to "rev.asc", "limit" to PAGE.toString()) + filters,
                ListSerializer(serializer),
            )
            out += page
            if (page.size < PAGE) return out
            cursor = revOf(page.last()) ?: return out
        }
    }

    private fun rest(table: String, query: List<Pair<String, String>>): HttpUrl {
        val b = base ?: throw IllegalStateException("Sync isn't configured in this build")
        return b.newBuilder().addPathSegments("rest/v1/$table").apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
    }

    private fun Request.Builder.rest(token: String) = this
        .header("apikey", anonKey)
        .header("Authorization", "Bearer $token")

    /** One retry with a forced refresh on 401: the access token can expire between check and use. */
    private suspend fun authorized(build: (String) -> Request): String {
        try {
            return execute(build(accessToken()))
        } catch (e: UnauthorizedException) {
            return try {
                execute(build(accessToken(forceRefresh = true)))
            } catch (e2: UnauthorizedException) {
                throw RemoteException(e2.message ?: "unauthorized", permanent = false)
            }
        }
    }

    private class UnauthorizedException(message: String) : IOException(message)

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw RemoteException("network: ${e.message}", permanent = false)
        }
        response.use {
            val raw = it.body.string()
            if (it.isSuccessful) return@withContext raw
            val (code, message) = parseError(raw)
            val label = "HTTP ${it.code}${code?.let { c -> " $c" }.orEmpty()}: ${message ?: raw.take(200)}"
            when {
                it.code == 401 || code?.startsWith("PGRST3") == true -> throw UnauthorizedException(label)
                // The server is down, overloaded or rate-limiting: try again later.
                it.code >= 500 || it.code == 408 || it.code == 429 -> throw RemoteException(label, permanent = false)
                // Anything else is the server looking at this request and saying no.
                else -> throw RemoteException(label, permanent = true)
            }
        }
    }

    /** PostgREST says {code, message}; GoTrue says {error_code, msg} or {error, error_description}. */
    private fun parseError(raw: String): Pair<String?, String?> = runCatching {
        val obj = json.parseToJsonElement(raw) as JsonObject
        fun field(vararg names: String) = names.firstNotNullOfOrNull { n -> obj[n]?.jsonPrimitive?.contentOrNull }
        field("code", "error_code", "error") to field("message", "msg", "error_description")
    }.getOrDefault(null to null)

    private fun quote(s: String): String = json.encodeToString(kotlinx.serialization.serializer<String>(), s)

    private fun inList(ids: List<String>) = "in.(${ids.joinToString(",")})"

    @Serializable
    private data class Horizon(@SerialName("min_rev") val minRev: Long)

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long = 3600,
        @SerialName("expires_at") val expiresAt: Long? = null,
        val user: TokenUser,
    )

    @Serializable
    private data class TokenUser(val id: String, val email: String? = null)

    private companion object {
        val JSON = "application/json".toMediaType()

        /** PostgREST's default max rows per response. */
        const val PAGE = 1000

        /** Refresh this long before expiry, so a token can't lapse mid-sync. */
        const val REFRESH_MARGIN_S = 60
    }
}

@Serializable
data class SpacePerson(
    @SerialName("space_id") val spaceId: String,
    val email: String,
    /** `owner` or `member`. */
    val role: String,
    /** False: invited, hasn't signed in yet. */
    val joined: Boolean,
)

/** A human message for a failed share/unshare/leave, matching the PWA's wording. */
fun sharingError(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        e is RemoteException && !e.permanent -> "You're offline. Sharing needs a connection."
        e is SignedOutException -> "Sign in again to share."
        m.contains("not an email address") -> "That doesn't look like an email address."
        m.contains("you already have access") -> "That's your own email address."
        else -> m.substringAfter(": ").ifBlank { "Something went wrong." }
    }
}

/** A human message for the sign-in screen. */
fun signInError(e: Throwable): String {
    val m = e.message.orEmpty()
    return when {
        e is RemoteException && !e.permanent -> "Couldn't reach the server. Check your connection."
        m.contains("invite-only", ignoreCase = true) -> "This email hasn't been invited."
        m.contains("otp_expired", ignoreCase = true) || m.contains("expired", ignoreCase = true) ->
            "That code is wrong or has expired."
        m.contains("rate", ignoreCase = true) -> "Too many attempts. Wait a minute and try again."
        else -> m.ifBlank { "Something went wrong." }
    }
}

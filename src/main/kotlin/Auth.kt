package win.hammerwars

import com.microsoft.aad.msal4j.*
import com.nimbusds.jwt.SignedJWT
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.Logger
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

class Auth(val root: String, val db: DB, val log: Logger, val http: HttpClient,
           val clientId: String, val clientSecret: String,
           val discordClientId: String, val discordClientSecret: String
) {
    val client = ConfidentialClientApplication
        .builder(clientId, ClientCredentialFactory.createFromSecret(clientSecret))
        .authority("https://login.microsoftonline.com/4130bd39-7c53-419c-b1e5-8758d6d63f21/")
        .build()

    val redirectUrl = "$root/auth"
    val discordRedirectUrl = "$root/authdiscord"

    fun validateEmail(email: String): Boolean =
        "^[A-Za-z0-9+_.-]+@purdue.edu\$".toRegex().matches(email)

    fun redir(state: String, nonce: String) =
        client.getAuthorizationRequestUrl(AuthorizationRequestUrlParameters
            .builder(redirectUrl, setOf("User.Read"))
            .state(state)
            .nonce(db.hash(nonce).base64())
            .responseMode(ResponseMode.FORM_POST)
            .prompt(Prompt.SELECT_ACCOUNT)
            .build()).toString()

    fun redirDiscord(state: String) =
        "https://discord.com/oauth2/authorize?client_id=$discordClientId&redirect_uri=${URLEncoder.encode(discordRedirectUrl, "UTF-8")}&response_type=code&scope=email+identify&state=${URLEncoder.encode(state, "UTF-8")}"

    suspend fun auth(code: String, ses: DB.SessionDB, nonce: String, state: String) =
        try {
            if (ses.isDiscord)
                throw LoginErr(LoginWith.Microsoft, WebErrorType.Unauthorized, "Wrong login method")

            if (ses.state!=state)
                throw LoginErr(LoginWith.Microsoft, WebErrorType.Unauthorized, "Bad state")
            val authParams = AuthorizationCodeParameters
                .builder(code, URI(redirectUrl)).scopes(setOf("User.Read")).build()

            val res = client.acquireToken(authParams).get()
            val claims = SignedJWT.parse(res.idToken()).jwtClaimsSet
            val nonceHash = claims.getStringClaim("nonce").base64()

            if (!MessageDigest.isEqual(nonceHash, db.hash(nonce)))
                throw LoginErr(LoginWith.Microsoft, WebErrorType.Unauthorized, "Bad nonce")

            if (!validateEmail(res.account().username()))
                throw LoginErr(LoginWith.Microsoft, WebErrorType.BadEmail, "Invalid email -- please login to Microsoft with your Purdue email address.")

            ses.withEmail(res.account().username(),
                runCatching {claims.getStringClaim("name")}.getOrNull()?.toName(), null, null)
        } catch(e: Exception) {
            ses.remove()
            throw LoginErr(LoginWith.Microsoft, WebErrorType.Other, "Error logging in with Microsoft")
        }

    suspend fun discordAuth(ses: DB.SessionDB, code: String, state: String) = try {
        if (!ses.isDiscord)
            throw LoginErr(LoginWith.Discord, WebErrorType.Unauthorized, "Wrong login method")
        if (ses.state!=state)
            throw LoginErr(LoginWith.Discord, WebErrorType.Unauthorized, "Bad state")

        val res = http.submitForm("https://discord.com/api/v10/oauth2/token", formParameters=parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", discordRedirectUrl)
        }) {
            basicAuth(discordClientId,discordClientSecret)
        }.body<JsonObject>()

        val tok: String = res["access_token"]?.jsonPrimitive?.content ?: run {
            log.error("no access token", res)
            throw LoginErr(LoginWith.Discord, WebErrorType.Unauthorized, "No access token returned")
        }

        @Serializable
        data class DiscordUser(val id: String, val username: String, val email: String?)

        val user = http.get("https://discord.com/api/v10/users/@me") {
            bearerAuth(tok)
        }.body<DiscordUser>()

        ses.withEmail(user.username, null, user.id, user.email)
    } catch(e: Exception) {
        ses.remove()
        throw LoginErr(LoginWith.Discord, WebErrorType.Other, "Error logging in with Discord")
    }
}
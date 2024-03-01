package win.hammerwars

import com.microsoft.aad.msal4j.*
import com.nimbusds.jwt.SignedJWT
import java.net.URI
import java.security.MessageDigest

class Auth(val root: String, val clientId: String, val clientSecret: String, val db: DB) {
    val client = ConfidentialClientApplication
        .builder(clientId, ClientCredentialFactory.createFromSecret(clientSecret))
        .authority("https://login.microsoftonline.com/4130bd39-7c53-419c-b1e5-8758d6d63f21/")
        .build()

    val redirectUrl = "$root/auth"

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

    suspend fun auth(code: String, ses: DB.SessionDB, nonce: String, state: String) =
        try {
            if (ses.state!=state) throw WebErrorType.Unauthorized.err("Bad state")
            val authParams = AuthorizationCodeParameters
                .builder(code, URI(redirectUrl)).scopes(setOf("User.Read")).build()

            val res = client.acquireToken(authParams).get()
            val claims = SignedJWT.parse(res.idToken()).jwtClaimsSet
            val nonceHash = claims.getStringClaim("nonce").base64()

            if (!MessageDigest.isEqual(nonceHash, db.hash(nonce)))
                throw WebErrorType.Unauthorized.err("Bad nonce")

            if (!validateEmail(res.account().username()))
                throw WebErrorType.BadEmail.err("Invalid email -- please login to Microsoft with your Purdue email address.")

            ses.withEmail(res.account().username(),
                runCatching {claims.getStringClaim("name")}.getOrNull())
        } catch(e: Exception) {
            ses.remove()
            throw e
        }
}
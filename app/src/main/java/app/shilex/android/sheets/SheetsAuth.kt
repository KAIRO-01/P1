package app.shilex.android.sheets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import app.shilex.android.data.Settings
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Private read-only Sheets access. Google Play services owns the OAuth token and renewals. */
object SheetsAuth {
    private const val SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
    private const val MAX_BYTES = 3_000_000
    private val scopes get() = listOf(Scope(SCOPE))
    private val request get() = AuthorizationRequest.builder().setRequestedScopes(scopes).build()

    fun authorized(context: Context): Boolean = Settings(context).sheetOAuthConnected

    fun connect(context: Context, resolve: (IntentSenderRequest) -> Unit, connected: () -> Unit, failed: (String) -> Unit) {
        Identity.getAuthorizationClient(context).authorize(request)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    val pending = result.pendingIntent
                    if (pending == null) failed("Google authorization could not open.")
                    else resolve(IntentSenderRequest.Builder(pending.intentSender).build())
                } else if (valid(result)) {
                    Settings(context).sheetOAuthConnected = true
                    connected()
                } else failed("Google did not grant read-only Sheets access.")
            }
            .addOnFailureListener {
                failed("Google authorization failed. Check Google Play services and Android OAuth client configuration.")
            }
    }

    fun complete(context: Context, intent: Intent?): Boolean {
        if (intent == null) return false
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(intent)
        if (!valid(result)) return false
        Settings(context).sheetOAuthConnected = true
        return true
    }

    fun disconnect(context: Context, complete: (Boolean) -> Unit) {
        Identity.getAuthorizationClient(context)
            .revokeAccess(RevokeAccessRequest.builder().setScopes(scopes).build())
            .addOnSuccessListener { Settings(context).sheetOAuthConnected = false; complete(true) }
            .addOnFailureListener { complete(false) }
    }

    private fun valid(result: AuthorizationResult) =
        SCOPE in result.grantedScopes && !result.accessToken.isNullOrBlank()

    /** Called ONLY from an IO thread. Background jobs cannot launch Google consent UI. */
    private fun token(context: Context): String {
        if (!authorized(context)) error("Connect Google Account in KAIRO Settings > Google Sheets.")
        val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request), 25, TimeUnit.SECONDS)
        if (result.hasResolution() || !valid(result)) error("Google needs your authorization again. Reconnect in Settings.")
        return result.accessToken ?: error("Google returned no access token.")
    }

    fun read(context: Context, http: OkHttpClient, ref: OrderSheet.SheetRef): OrderSheet.Snapshot {
        val bearer = token(context)
        val base = "https://sheets.googleapis.com/v4/spreadsheets/" + ref.id
        val meta = get(http, base + "?fields=sheets(properties(sheetId,title))", bearer)
        val tabs = meta.optJSONArray("sheets") ?: error("No accessible spreadsheet tabs.")
        if (tabs.length() == 0) error("No spreadsheet tabs.")
        var chosen: JSONObject? = null
        for (i in 0 until tabs.length()) {
            val properties = tabs.getJSONObject(i).getJSONObject("properties")
            if (properties.optString("sheetId") == ref.gid) { chosen = properties; break }
        }
        if (chosen == null && ref.gid != "0") error("The tab ID in your link was not found.")
        val title = (chosen ?: tabs.getJSONObject(0).getJSONObject("properties")).getString("title")
        val range = "'" + title.replace("'", "''") + "'!A1:AZ15001"
        val values = get(http, base + "/values/" + Uri.encode(range), bearer).optJSONArray("values")
            ?: error("The selected tab has no rows.")
        val csv = buildString {
            for (i in 0 until values.length()) {
                val row = values.getJSONArray(i)
                for (j in 0 until row.length()) {
                    if (j > 0) append(',')
                    append('"')
                    append(row.optString(j).replace("\"", "\"\""))
                    append('"')
                }
                append('\n')
            }
        }
        val orders = OrderSheet.decode(csv.toByteArray(Charsets.UTF_8))
        if (orders.isEmpty()) error("No orders recognized. Check column headings in the first row.")
        return OrderSheet.Snapshot(orders, "private Google Sheet (read-only, tab: " + title + ")", true)
    }

    private fun get(http: OkHttpClient, url: String, bearer: String): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer " + bearer).build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(when (response.code) {
                    401 -> "Google authorization expired. Reconnect in Settings."
                    403 -> "Google refused access. Verify the account, sharing rights, and Google Sheets API in Cloud."
                    404 -> "Spreadsheet or tab not found for your Google Account."
                    else -> "Google Sheets returned HTTP " + response.code + "."
                })
            }
            val stream = response.body?.byteStream() ?: error("Google returned no data.")
            val output = ByteArrayOutputStream()
            stream.use {
                val buffer = ByteArray(8192)
                while (true) {
                    val n = it.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > MAX_BYTES) error("Sheet exceeds the 3 MB preview limit.")
                    output.write(buffer, 0, n)
                }
            }
            JSONObject(output.toString("UTF-8"))
        }
    }
}

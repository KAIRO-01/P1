package app.shilex.android.sheets

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.result.IntentSenderRequest
import app.shilex.android.data.Settings
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Tasks
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
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
                } else failed("Google did not grant the read-only Sheets scope. Check Google Account and OAuth settings.")
            }
            .addOnFailureListener { error ->
                failed(explain(error))
            }
    }

    /**
     * Preserve Google's actual authorization failure for display, rather than silently
     * collapsing every failure (including a developer configuration error) into false.
     */
    fun complete(context: Context, intent: Intent?): Result<Unit> = runCatching {
        val data = intent ?: error("Google returned no authorization data.")
        val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data)
        if (!valid(result)) error("Google did not grant read-only Sheets access.")
        Settings(context).sheetOAuthConnected = true
    }

    /** Package and current APK certificate for the matching Google Cloud Android OAuth client. */
    @Suppress("DEPRECATION")
    fun clientDetails(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_SIGNING_CERTIFICATES,
        )
        val signature = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: error("Signing certificate unavailable")
        val sha1 = MessageDigest.getInstance("SHA-1").digest(signature.toByteArray())
            .joinToString(":") { "%02X".format(it) }
        "Package: ${context.packageName}. Signing SHA-1: $sha1. " +
            "Create a matching Android OAuth client in Google Cloud, enable the Google Sheets API, " +
            "and authorize the Google Account that can open the spreadsheet."
    }.getOrElse { "Package: ${context.packageName}. Could not read signing SHA-1 (${it.javaClass.simpleName})." }

    /** Never log or expose tokens; show the error status and the required configuration. */
    fun explain(error: Throwable?): String {
        val apiError = error as? ApiException ?: error?.cause as? ApiException
        return when (apiError?.statusCode) {
            10 -> "Google authorization error 10 (DEVELOPER_ERROR). The APK package name and signing SHA-1 " +
                "must match an Android OAuth client in Google Cloud. Tap OAuth setup details below."
            12501, 16 -> "Google account selection did not complete (status ${apiError.statusCode}). " +
                "Select an account that has access to the Sheet and try again."
            null -> "Google authorization could not finish: " +
                (error?.localizedMessage?.take(170) ?: "No result was returned.") +
                " Check OAuth setup details."
            else -> "Google authorization failed (status ${apiError.statusCode}). " +
                "Check Google Play services, the Google Cloud OAuth client and Sheets API."
        }
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

    /**
     * Read every monthly tab in the spreadsheet, not only the gid in the link.
     * Values.batchGet groups up to eight tabs per request to limit latency.
     * All requests stay read-only under the user's authorized Google Account.
     */
    fun read(context: Context, http: OkHttpClient, ref: OrderSheet.SheetRef): OrderSheet.Snapshot {
        val bearer = token(context)
        val base = "https://sheets.googleapis.com/v4/spreadsheets/" + ref.id
        val meta = get(http, base + "?fields=sheets(properties(sheetId,title,hidden))", bearer)
        val sheets = meta.optJSONArray("sheets") ?: error("No accessible spreadsheet tabs.")
        val visible = buildList {
            for (i in 0 until sheets.length()) {
                val props = sheets.getJSONObject(i).getJSONObject("properties")
                if (!props.optBoolean("hidden", false)) add(props.getString("title"))
            }
        }
        if (visible.isEmpty()) error("The spreadsheet has no visible tabs.")
        val titles = OrderSheet.selectOrderTabs(visible)
        if (titles.size > 36) error("Too many tabs for this preview (" + titles.size + "; maximum 36).")
        val orders = ArrayList<OrderSheet.Order>()
        for (group in titles.chunked(8)) {
            val ranges = group.map { title ->
                "'" + title.replace("'", "''") + "'!A1:AZ15001"
            }
            val query = ranges.joinToString("") { "&ranges=" + Uri.encode(it) }
            val result = get(http, base + "/values:batchGet?valueRenderOption=FORMATTED_VALUE" + query, bearer)
            val rangeResults = result.optJSONArray("valueRanges") ?: error("Google returned no tab data.")
            if (rangeResults.length() != group.size) error("Google did not return all requested tabs.")
            for (i in group.indices) {
                val rows = rangeResults.getJSONObject(i).optJSONArray("values") ?: continue
                val csv = buildString {
                    for (j in 0 until rows.length()) {
                        val row = rows.getJSONArray(j)
                        for (k in 0 until row.length()) {
                            if (k > 0) append(',')
                            append('"')
                            append(row.optString(k).replace("\"", "\"\""))
                            append('"')
                        }
                        append('\n')
                    }
                }
                orders += OrderSheet.decode(csv.toByteArray(Charsets.UTF_8), sourceTab = group[i])
            }
        }
        if (orders.isEmpty()) error("No customer orders recognized across " + titles.size + " tabs. Check Name headings.")
        return OrderSheet.Snapshot(
            orders,
            "private Google Sheet (read-only; " + titles.size + " tabs: " + titles.joinToString(", ") + ")",
            true,
            tabs = titles,
        )
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

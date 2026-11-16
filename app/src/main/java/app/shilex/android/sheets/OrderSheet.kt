package app.shilex.android.sheets

import android.content.Context
import app.shilex.android.data.Settings
import java.io.InputStream
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Read-only order data. A Sheet URL by itself is NOT an OAuth grant:
 * direct CSV access works only when Google allows anonymous reads.
 * An explicitly imported CSV remains private on the device.
 */
object OrderSheet {
    private const val MAX_BYTES = 3_000_000
    private const val CSV_FILE = "kairo_orders_import.csv"

    data class SheetRef(val id: String, val gid: String)
    data class Order(
        val customer: String,
        val mandir: String,
        val price: String,
        val advance: String,
        val balance: String,
        val delivery: String,
        val address: String,
        val status: String,
    )
    data class Snapshot(val orders: List<Order>, val source: String, val live: Boolean)

    fun parseLink(link: String): SheetRef? {
        val uri = runCatching { URI(link.trim()) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host != "docs.google.com") return null
        val parts = uri.path.orEmpty().split("/")
        val i = parts.indexOf("d")
        val id = if (i >= 0) parts.getOrNull(i + 1).orEmpty() else ""
        if (!id.matches(Regex("[A-Za-z0-9_-]{20,}"))) return null
        val gid = Regex("(?:^|[?&#])gid=([0-9]+)").find(link)?.groupValues?.get(1) ?: "0"
        return SheetRef(id, gid)
    }

    fun hasImport(context: Context): Boolean = context.getFileStreamPath(CSV_FILE).exists()

    fun importCsv(context: Context, stream: InputStream): Int {
        val bytes = stream.use { it.readNBytes(MAX_BYTES + 1) }
        require(bytes.size <= MAX_BYTES) { "CSV file is too large (maximum 3 MB)." }
        val parsed = decode(bytes)
        require(parsed.isNotEmpty()) { "No customer orders were found in the CSV." }
        context.openFileOutput(CSV_FILE, Context.MODE_PRIVATE).use { it.write(bytes) }
        return parsed.size
    }

    fun load(context: Context, http: OkHttpClient): Snapshot {
        val link = Settings(context).orderSheetUrl.trim()
        var reason = ""
        if (link.isNotEmpty()) {
            val ref = parseLink(link) ?: throw IllegalArgumentException("Invalid Google Sheets URL.")
            try {
                val url = "https://docs.google.com/spreadsheets/d/${ref.id}/export?format=csv&gid=${ref.gid}"
                val request = Request.Builder().url(url).get().build()
                val bytes = http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("Google refused access (HTTP ${response.code}).")
                    val input = response.body?.byteStream() ?: error("Google returned an empty response.")
                    input.use { it.readNBytes(MAX_BYTES + 1) }
                }
                if (bytes.size > MAX_BYTES) error("Sheet exceeds the 3 MB preview limit.")
                val text = String(bytes, Charsets.UTF_8).trimStart()
                if (text.startsWith("<!DOCTYPE", ignoreCase = true) || text.startsWith("<html", ignoreCase = true)) {
                    error("Google requires sign-in to read this Sheet.")
                }
                return Snapshot(decode(bytes), "live Google Sheet", true)
            } catch (error: Exception) {
                reason = error.message.orEmpty()
                if (!hasImport(context)) {
                    throw IllegalStateException(
                        "This link cannot be read without Google authorization (${reason.take(100)}). " +
                            "Your private Sheet has NOT been made public. Import its CSV export in KAIRO Settings for read-only questions.",
                    )
                }
            }
        }
        if (hasImport(context)) {
            val bytes = context.openFileInput(CSV_FILE).use { it.readNBytes(MAX_BYTES + 1) }
            return Snapshot(decode(bytes), if (reason.isNotEmpty()) "imported CSV (live link inaccessible)" else "imported CSV", false)
        }
        throw IllegalStateException("Open KAIRO Settings > Google Sheets and save a link or import a CSV.")
    }

    fun decode(bytes: ByteArray): List<Order> {
        val rows = parseCsv(String(bytes, Charsets.UTF_8).removePrefix("\uFEFF"))
            .filter { row -> row.any(String::isNotBlank) }
        if (rows.isEmpty()) return emptyList()
        val headers = rows.first().map(::key)
        fun at(row: List<String>, vararg names: String): String {
            val options = names.map(::key)
            val exact = headers.indexOfFirst { it in options }
            val idx = if (exact >= 0) exact else headers.indexOfFirst { h -> options.any { h.contains(it) } }
            return row.getOrNull(idx)?.trim().orEmpty().take(500)
        }
        return rows.drop(1).take(15_000).mapNotNull { row ->
            val customer = at(row, "Customer Name", "Client Name", "Customer", "Client", "Party Name", "Name")
            val mandir = at(row, "Mandir Type", "Mandir Design", "Mandir", "Temple Type", "Product", "Item", "Order Details")
            val delivery = at(row, "Delivery Date", "Dispatch Date", "Due Date", "Expected Delivery", "Delivery")
            val status = at(row, "Order Status", "Work Status", "Delivery Status", "Status")
            val price = at(row, "Total Amount", "Total Price", "Order Value", "Order Amount", "Selling Price", "Price", "Amount")
            val advance = at(row, "Advance Received", "Advance Payment", "Advance Paid", "Advance")
            val balance = at(row, "Balance Amount", "Balance Due", "Pending Amount", "Balance", "Due Payment")
            val address = at(row, "Delivery Address", "Delivery Location", "Site Address", "Customer Address", "Address", "Location")
            if (listOf(customer, mandir, delivery, price, status).all(String::isEmpty)) null
            else Order(customer, mandir, price, advance, balance, delivery, address, status)
        }
    }

    private fun key(s: String): String = s.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    /** Standard RFC-style quoted CSV, including commas, doubled quotes and line breaks within cells. */
    fun parseCsv(csv: String): List<List<String>> {
        if (csv.isEmpty()) return emptyList()
        val rows = ArrayList<List<String>>()
        var cells = ArrayList<String>()
        val value = StringBuilder()
        var quoted = false
        var i = 0
        fun cell() { cells.add(value.toString()); value.setLength(0) }
        fun row() { cell(); rows.add(cells); cells = ArrayList() }
        while (i < csv.length) {
            val c = csv[i]
            when {
                c == '"' && quoted && i + 1 < csv.length && csv[i + 1] == '"' -> { value.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> cell()
                (c == '\n' || c == '\r') && !quoted -> {
                    row()
                    if (c == '\r' && i + 1 < csv.length && csv[i + 1] == '\n') i++
                }
                else -> value.append(c)
            }
            i++
        }
        if (value.isNotEmpty() || cells.isNotEmpty()) row()
        return rows
    }

    private val dates = listOf("d/M/uuuu", "d-M-uuuu", "d/M/uu", "d-M-uu", "uuuu-M-d", "d MMM uuuu", "d MMMM uuuu")
        .map { DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(it).toFormatter(Locale.ENGLISH) }

    fun deliveryDate(raw: String): LocalDate? {
        val clean = raw.trim().substringBefore(" 00:00").substringBefore("T00:00").trim()
        for (format in dates) {
            try { return LocalDate.parse(clean, format) } catch (_: Exception) {}
        }
        return null
    }

    fun money(value: String): Double? {
        val cleaned = value.replace(",", "").replace("₹", "").replace("Rs.", "", ignoreCase = true).trim()
        return cleaned.toDoubleOrNull()
    }
}

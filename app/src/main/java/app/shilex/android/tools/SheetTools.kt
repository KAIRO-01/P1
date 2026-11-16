package app.shilex.android.tools

import app.shilex.android.sheets.OrderSheet
import app.shilex.android.sheets.SheetWatch
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.text.NumberFormat
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Only reads orders from the user-linked Sheet or explicitly imported CSV. Never modifies Google data. */
object SheetTools {
    fun all(): List<PhoneTool> = listOf(ReadOrders)

    private object ReadOrders : PhoneTool {
        override val name = "sheet_orders"
        override fun declaration() = Schema.function(
            name,
            "Read the owner's connected Google Sheet of customer mandir orders, live when permitted. " +
                "Use for order details, who is delivering in the next days, pending balance, overdue deliveries. " +
                "Never invent missing cells or report offline CSV as current live data.",
            Schema.string("action", "find, due, overdue, balance, list, or changes", required = true,
                choices = listOf("find", "due", "overdue", "balance", "list", "changes")),
            Schema.string("customer", "Customer name for find; part of name is fine."),
            Schema.integer("days", "For due: upcoming number of days (default 4, max 90)."),
        )

        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            if (args.text("action") == "changes") return Results.done(SheetWatch.lastUpdate(host.context))
            val snapshot = try {
                withContext(Dispatchers.IO) { OrderSheet.load(host.context, host.http) }
            } catch (e: Exception) {
                return Results.failed("sheet_unavailable", e.message ?: "Could not read your Google Sheet.")
            }
            val orders = snapshot.orders
            val today = LocalDate.now()
            val name = args.text("customer")
            val action = args.text("action")
            val days = args.optInt("days", 4).coerceIn(1, 90)
            val result = when (action) {
                "find" -> {
                    if (name.isBlank()) return Results.failed("missing_customer", "Ask which customer's order.")
                    val matches = orders.filter { it.customer.contains(name, ignoreCase = true) }
                    if (matches.isEmpty()) "No orders matched $name."
                    else "Found ${matches.size} matching order(s):\n" + matches.take(8).joinToString("\n") { detail(it) }
                }
                "due" -> {
                    val upcoming = orders.mapNotNull { order -> OrderSheet.deliveryDate(order.delivery)?.let { it to order } }
                        .filter { (date, order) -> !date.isBefore(today) && !date.isAfter(today.plusDays(days.toLong())) && !finished(order.status) }
                        .sortedBy { it.first }
                    if (upcoming.isEmpty()) "No undelivered orders with a readable delivery date within the next $days days. Check the date column and status."
                    else "${upcoming.size} order(s) due within $days days:\n" +
                        upcoming.take(12).joinToString("\n") { (date, order) -> "${order.customer.ifBlank { "Unnamed customer" }}: ${order.mandir.ifBlank { "Mandir" }}, $date. ${balance(order)}" }
                }
                "overdue" -> {
                    val late = orders.mapNotNull { order -> OrderSheet.deliveryDate(order.delivery)?.let { it to order } }
                        .filter { (date, order) -> date.isBefore(today) && !finished(order.status) }
                        .sortedBy { it.first }
                    if (late.isEmpty()) "No overdue orders found among readable delivery dates and statuses."
                    else "${late.size} overdue order(s):\n" + late.take(12).joinToString("\n") { (date, order) ->
                        "${order.customer.ifBlank { "Unnamed customer" }}: ${order.mandir}, due $date (${ChronoUnit.DAYS.between(date, today)} days ago)."
                    }
                }
                "balance" -> {
                    val pending = orders.mapNotNull { o -> outstanding(o)?.takeIf { it > 0.0 }?.let { o to it } }
                    val total = pending.sumOf { it.second }
                    "Recorded pending balance across ${pending.size} orders: ${rupees(total)}. " +
                        pending.sortedByDescending { it.second }.take(8).joinToString("; ") { (order, amount) ->
                            "${order.customer.ifBlank { "Unnamed customer" }} ${rupees(amount)}"
                        } + ". Orders with missing or nonnumeric amounts are excluded."
                }
                "list" -> "${orders.size} order(s) in the source. Recent entries:\n" +
                    orders.takeLast(8).joinToString("\n") { detail(it) }
                else -> return Results.failed("invalid_action", "Use find, due, overdue, balance, or list.")
            }
            return Results.done("Source: ${snapshot.source} (${orders.size} order records). $result")
        }
    }

    private fun detail(o: OrderSheet.Order): String =
        "Customer: ${o.customer.ifBlank { "not entered" }}; Mandir: ${o.mandir.ifBlank { "not entered" }}; " +
            "Price: ${o.price.ifBlank { "not entered" }}; Advance: ${o.advance.ifBlank { "not entered" }}; " +
            "${balance(o)}; Delivery: ${o.delivery.ifBlank { "not entered" }}; " +
            "Address: ${o.address.ifBlank { "not entered" }}; Status: ${o.status.ifBlank { "not entered" }}."

    private fun balance(o: OrderSheet.Order): String = when {
        o.balance.isNotBlank() -> "Balance: ${o.balance}"
        outstanding(o) != null -> "Calculated balance: ${rupees(outstanding(o)!!)}"
        else -> "Balance not entered"
    }

    private fun outstanding(o: OrderSheet.Order): Double? =
        OrderSheet.money(o.balance) ?: run {
            val price = OrderSheet.money(o.price)
            val advance = OrderSheet.money(o.advance)
            if (price != null && advance != null) price - advance else null
        }

    private fun finished(status: String): Boolean =
        status.lowercase(Locale.ROOT).let { s ->
            listOf("delivered", "installed", "completed", "complete", "cancelled", "canceled").any { it in s }
        }

    private fun rupees(amount: Double): String =
        "₹" + NumberFormat.getNumberInstance(Locale("en", "IN")).apply { maximumFractionDigits = 2 }.format(amount)
}

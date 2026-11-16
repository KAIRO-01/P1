package app.shilex.android.tools

import android.content.Intent
import android.net.Uri
import org.json.JSONObject

/**
 * Uses Google Maps' own "Your location" origin. No guessed coordinates, no browser search.
 * Maps itself requests GPS/location permission and calculates the actual route.
 */
object NavigationTools {
    fun all(): List<PhoneTool> = listOf(Navigate)

    private object Navigate : PhoneTool {
        override val name = "navigate_to"
        override fun declaration() = Schema.function(
            name,
            "Open turn-by-turn Google Maps navigation from the phone's CURRENT LOCATION to a destination. " +
                "Use for take me to, directions from here, mujhe yahan se le chalo, raasta dikhao. " +
                "Never use open_web_search or open_app for a navigation request.",
            Schema.string("destination", "Place name or address, e.g. Thane, Viviana Mall, Mumbai.", required = true),
            Schema.string("mode", "driving, walking, bicycling or transit. Default driving."),
        )
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val destination = args.text("destination").take(250)
            if (destination.isBlank()) return Results.failed("missing_destination", "Ask where to navigate.")
            val mode = when (args.text("mode").lowercase()) {
                "walking", "walk" -> "w"
                "bicycling", "cycling", "bike_cycle" -> "b"
                "transit", "public_transport" -> "r"
                else -> "d"
            }
            // google.navigation starts from current location; Google Maps owns GPS permission.
            val uri = Uri.parse("google.navigation:q=" + Uri.encode(destination) + "&mode=" + mode)
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                setPackage("com.google.android.apps.maps")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(host.context.packageManager) == null) {
                return Results.failed("maps_missing", "Google Maps is unavailable. Install or enable Google Maps; no browser fallback.")
            }
            return host.openApp(intent, "Google Maps",
                "Opening Google Maps navigation to $destination from your current location. If GPS is unavailable, Maps will ask you to enable location.")
        }
    }
}

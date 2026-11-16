package app.shilex.android.sheets

import org.junit.Assert.*
import org.junit.Test

class OrderSheetTest {
    @Test fun readsSelectedTabFromPastedLink() {
        val url = "https://docs.google.com/spreadsheets/d/abcdef0123456789ABCDEF0123456789/edit#gid=1234"
        assertEquals("1234", OrderSheet.parseLink(url)?.gid)
    }

    @Test fun rejectsNonGoogleOrMalformedLink() {
        assertNull(OrderSheet.parseLink("https://example.com/spreadsheets/d/abcdef0123456789ABCDEF0123456789"))
        assertNull(OrderSheet.parseLink("https://docs.google.com/spreadsheets/d/x/edit"))
    }

    @Test fun parsesCustomerOrdersAndQuotedComma() {
        val csv = "Customer Name,Mandir Type,Total Price,Advance,Delivery Date,Address\n" +
            "\"Harsh Sir\",\"Teak, Corian\",222000,20000,18/11/2026,\"Thane, Mumbai\"\n"
        val orders = OrderSheet.decode(csv.toByteArray())
        assertEquals(1, orders.size)
        assertEquals("Harsh Sir", orders[0].customer)
        assertEquals("Teak, Corian", orders[0].mandir)
        assertEquals("Thane, Mumbai", orders[0].address)
    }

    @Test fun parsesIndianMoneyAndDate() {
        assertEquals(222000.0, OrderSheet.money("₹2,22,000")!!, 0.001)
        assertEquals("2026-11-18", OrderSheet.deliveryDate("18/11/2026").toString())
    }
}

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
    @Test fun selectsAllMonthlyTabsNotOnlyCurrentGid() {
        val tabs = listOf("Dashboard", "jan", "feb", "March", "APRIL", "MAY", "Jun", "july", "August", "Sep", "Oct", "Nov")
        assertEquals(tabs.drop(1), OrderSheet.selectOrderTabs(tabs))
        assertEquals(listOf("Orders"), OrderSheet.selectOrderTabs(listOf("Orders")))
    }

    @Test fun parsesActualMonthlyNameNoteContactAndBookingDate() {
        val csv = "Shop orders,2026\\nZ,NOTE,Name,contact,Column 5,Making by,Book DT\\n" +
            ",CORIAN,NAYAN RANE,9769736490,,,09/11/2026\\n" +
            ",teakwood/corian,VISHAL LAUNDIYA,9833645529,,,11/11/2026\\n"
        val orders = OrderSheet.decode(csv.toByteArray(), "jan")
        assertEquals(2, orders.size)
        assertEquals("jan", orders[0].tab)
        assertEquals("NAYAN RANE", orders[0].customer)
        assertEquals("CORIAN", orders[0].mandir)
        assertEquals("9769736490", orders[0].contact)
        assertEquals("09/11/2026", orders[0].bookingDate)
        assertEquals("", orders[0].delivery) // Book DT is NOT a delivery due date.
    }

}

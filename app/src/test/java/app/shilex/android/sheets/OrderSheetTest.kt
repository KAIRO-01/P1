package app.shilex.android.sheets

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class OrderSheetTest {
    @Test fun parsesGoogleLinkAndGid() {
        val s = OrderSheet.parseLink("https://docs.google.com/spreadsheets/d/1ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi/edit#gid=12345")
        assertEquals("12345", s?.gid)
        assertNull(OrderSheet.parseLink("https://example.com/spreadsheets/d/1ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi"))
        assertNull(OrderSheet.parseLink("http://docs.google.com/spreadsheets/d/1ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghi"))
    }

    @Test fun quotedCsvIsParsedAndColumnsMatched() {
        val csv = "Customer Name,Mandir Type,Order Price,Advance,Delivery Date,Delivery Address\n" +
            "\"Shah, Amit\",\"Teakwood, Carved\",45000,15000,17/11/2026,\"Thane\nWest\"\n"
        val orders = OrderSheet.decode(csv.toByteArray())
        assertEquals(1, orders.size)
        assertEquals("Shah, Amit", orders[0].customer)
        assertEquals("Teakwood, Carved", orders[0].mandir)
        assertEquals("Thane\nWest", orders[0].address)
        assertEquals(30000.0, OrderSheet.money(orders[0].price)!! - OrderSheet.money(orders[0].advance)!!, 0.0)
    }

    @Test fun indianDatesAndQuotedEscapes() {
        assertEquals(LocalDate.of(2026, 11, 17), OrderSheet.deliveryDate("17/11/2026"))
        assertEquals(LocalDate.of(2026, 11, 17), OrderSheet.deliveryDate("17 Nov 2026"))
        assertEquals(listOf(listOf("A\"B","C")), OrderSheet.parseCsv("\"A\"\"B\",C"))
    }
}

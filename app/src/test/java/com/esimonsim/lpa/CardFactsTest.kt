package com.esimonsim.lpa

import com.esimonsim.apdu.ApduBackend
import com.esimonsim.apdu.OpenResult
import com.esimonsim.apdu.SimRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardFactsTest {
    /** A phone's records of a 5ber card: its built-in profile first, then eSIMs, some since deleted. */
    private val seen = listOf(
        SimRecord("89000000000000000114", "5ber", 1, 3, false),
        SimRecord("89000000000000000122", "Carrier V", 1, 4, false), // deleted since
        SimRecord("89000000000000000130", "Carrier P", 0, 5, false), // named from Android's carrier list
        SimRecord("89000000000000000148", "Carrier R", 1, 6, false),
        SimRecord("89000000000000000155", "Carrier F", 1, 13, true),
    )
    private val onCard = listOf("8900000000000000014", "8900000000000000015") // as the card's list gives them

    @Test fun theCardsOwnProfileIsTheFirstRecordThatIsntOneOfItsEsims() {
        assertEquals(CardFacts.Name("5ber", CardFacts.Name.From.SEEN, "89000000000000000114"),
            CardFacts.name(seen, onCard, Lpa.ISD_R_OPEN))
    }

    @Test fun oneInUseWithNoneOfTheEsimsOnSettlesIt() {
        val now = seen.map { if (it.name == "Carrier F") SimRecord(it.iccid, it.name, 1, it.record, false) else it } +
            SimRecord("89000000000000000114", "5ber", 1, 20, true)
        assertEquals(CardFacts.Name("5ber", CardFacts.Name.From.IN_USE, "89000000000000000114"), CardFacts.name(now, onCard, null))
    }

    @Test fun namesPeopleChoseOrAndroidLookedUpDontCount() {
        val renamed = listOf(SimRecord("89000000000000000114", "My card", 2, 3, false), // NAME_SOURCE_USER_INPUT
            SimRecord("89000000000000000163", "Carrier", 0, 4, false)) // NAME_SOURCE_CARRIER_ID
        assertNull(CardFacts.name(renamed, onCard, Lpa.ISD_R_OPEN))
    }

    @Test fun withoutRecordsAMakersOwnIdNamesTheBrand() {
        assertEquals(CardFacts.Name("5ber", CardFacts.Name.From.MANAGER_ID),
            CardFacts.name(emptyList(), onCard, "A0000005591010FFFFFFFF8900050500"))
        assertEquals("ESTKme", CardFacts.name(emptyList(), onCard, "a06573746b6d65ffff4953442d522031")?.value)
        assertNull(CardFacts.name(emptyList(), onCard, Lpa.ISD_R_LOWER))
    }

    @Test fun anIccidMatchesWithOrWithoutItsPaddingAndCheckDigit() {
        val eSim = listOf(SimRecord("8900000000000000014F", "Carrier R", 1, 1, false))
        assertNull(CardFacts.name(eSim, listOf("8900000000000000014"), null))
        assertNull(CardFacts.name(listOf(SimRecord("89000000000000000148", "Carrier R", 1, 1, false)),
            listOf("8900000000000000014"), null))
    }

    @Test fun whoMadeTheCard() {
        // A 5ber: the EID and the factory number agree.
        assertEquals("Eastcompeace" to "Zhuhai, China", CardFacts.maker("89086030000000000000000000000017", "ED-ZI-UP-0824"))
        // By the factory number alone, by company and site: TS is Thales, but not in Wuxi.
        assertEquals("Kigen" to "Dublin, Ireland", CardFacts.maker("89044045000000000000000000000001", "KN-DN-UP-0924"))
        assertEquals("Tongxin Microelectronics", CardFacts.maker(null, "TS-WI-UP-0128")?.first)
        assertEquals("Giesecke+Devrient", CardFacts.maker(null, "GD-NG-0617")?.first) // a card that left out "UP"
        // Another scheme's number names another company: never read as a card factory.
        assertNull(CardFacts.maker(null, "ED-RH-SM-0528"))
        assertNull(CardFacts.maker(null, "XX-YY-UP-0101"))
        assertNull(CardFacts.maker(null, null))
    }

    @Test fun aKnownBrandBeatsAnOlderRecordUnderAnotherName() {
        // The card first came to this phone with an eSIM on, deleted before this app saw it: its record is older.
        val records = listOf(SimRecord("89000000000000000122", "Carrier V", 1, 2, false),
            SimRecord("89000000000000000114", "5ber", 1, 3, false))
        assertEquals(CardFacts.Name("5ber", CardFacts.Name.From.SEEN, "89000000000000000114"),
            CardFacts.name(records, onCard, "A0000005591010FFFFFFFF8900050500"))
        // With only the other record, the brand itself.
        assertEquals(CardFacts.Name("5ber", CardFacts.Name.From.MANAGER_ID),
            CardFacts.name(records.take(1), onCard, "A0000005591010FFFFFFFF8900050500"))
    }

    @Test fun androidsPlaceholderIsNoName() {
        assertNull(CardFacts.name(listOf(SimRecord("89000000000000000114", "CARD 1", null, 3, false)), onCard, null))
    }

    @Test fun categoriesInWords() {
        assertEquals("Basic", CardFacts.category(1))
        assertEquals("Medium", CardFacts.category(2))
        assertEquals("Contactless", CardFacts.category(3))
        assertNull(CardFacts.category(0)) // "other" says nothing
        assertNull(CardFacts.category(null))
    }

    @Test fun aRealFiveBerCardsInfo() {
        // GetEUICCInfo2 as a 5ber card answers it.
        val info2 = "BF2281AA810302030182030202028303040200840D810100820400035088830239768505077F3E1F80860309020087" +
            "0302030088020490A92C04140B1359633D2AB469761CA9D82B2229350DB722D7041481370F5125D0B1D408D4C3B232E6D25E" +
            "795BEBFBAA2C04140B1359633D2AB469761CA9D82B2229350DB722D7041481370F5125D0B1D408D4C3B232E6D25E795BEBFB" +
            "8B01009902064004030100000C0D45442D5A492D55502D30383234"
        val card = object : ApduBackend {
            override fun isReady() = true
            override fun openChannel(slot: Int, port: Int, aidHex: String) = OpenResult(1, 1, null)
            override fun transmit(slot: Int, port: Int, channel: Int, cla: Int, ins: Int, p1: Int, p2: Int, p3: Int,
                                  dataHex: String) = info2 + "9000"
            override fun closeChannel(slot: Int, port: Int, channel: Int) {}
        }
        val info = Lpa(card).at(Card("89086030000000000000000000000017", 1, Lpa.ISD_R_OPEN, false)).cardInfo()
        assertEquals("ED-ZI-UP-0824", info.factory)
        assertNull(info.category) // 0, "other"
        assertEquals(217_224L, info.freeBytes)
        assertEquals(14_710L, info.freeRam)
        assertEquals("2.2.2", info.standard)
        assertEquals("4.2.0", info.firmware)
    }
}

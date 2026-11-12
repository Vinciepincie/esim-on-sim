package com.esimonsim.lpa

import com.esimonsim.adb.CardShell
import com.esimonsim.apdu.SimRecord

/** Facts about a card that aren't in its eSIM list: the name it gives itself and who made its chip. */
object CardFacts {
    /** A card's own name, and how it's known: [From] in order of trust. [iccid]: the profile of Android's it came from,
     *  so it can be dropped once that turns out to be one of the card's eSIMs. */
    data class Name(val value: String, val from: From, val iccid: String? = null) {
        enum class From {
            /** The card's built-in profile, which Android is using now, as none of the card's eSIMs is on. */
            IN_USE,
            /** The card's built-in profile, as Android saw it at some point with none of the card's eSIMs on. */
            SEEN,
            /** The maker's own eSIM manager ID, which the card answered to. */
            MANAGER_ID,
        }
    }

    /** Android's NAME_SOURCE_SIM_SPN and NAME_SOURCE_SIM_PNN: names read from the card. Before Android 10 the source
     *  says nothing, so the helper leaves it out ([SimRecord.nameSource] null). */
    private val FROM_CARD = setOf(1, 4)

    /** The name Android gives a SIM it has no name for: "CARD 1", "CARD 2". */
    private val PLACEHOLDER = Regex("CARD [0-9]+", RegexOption.IGNORE_CASE)

    /** The makers' own eSIM manager IDs ([CardShell.ISD_R_AIDS]) and the brand each belongs to. */
    private val BY_MANAGER_ID = mapOf(
        "A0000005591010000000008900000300" to "eSIM.me",
        "A0000005591010FFFFFFFF8900050500" to "5ber",
        "A0000005591010FFFFFFFF8900000177" to "XeSIM",
        "A000000559104C696E6B736669656C64" to "Linksfield",
        "A06573746B6D65FFFF4953442D522030" to "ESTKme",
        "A06573746B6D65FFFF4953442D522031" to "ESTKme",
        "A06573746B6D65FFFFFFFF4953442D52" to "ESTKme",
    )

    /** The card's own name: the provider name of its built-in profile (shown while none of its eSIMs is on, e.g.
     *  "5ber"), from Android's [records] of the card. A record not among the card's eSIMs ([iccids]) with a name read
     *  from the card: one in use now wins, else the earliest. Fallback: the brand of a maker's eSIM manager ID ([isdR]). */
    fun name(records: List<SimRecord>, iccids: Collection<String>, isdR: String?): Name? {
        val own = records.filter { r ->
            r.name.isNotBlank() && !PLACEHOLDER.matches(r.name.trim()) &&
                (r.nameSource == null || r.nameSource in FROM_CARD) && !isEsim(r.iccid, iccids)
        }
        own.firstOrNull { it.inUse }?.let { return Name(it.name.trim(), Name.From.IN_USE, it.iccid) }
        // A brand known from the maker's ID is sure: a record under another name is more likely an eSIM deleted before
        // this app saw it than the card's own profile.
        val brand = isdR?.uppercase()?.let(BY_MANAGER_ID::get)
        val seen = if (brand == null) own.minByOrNull { it.record }
            else own.filter { it.name.contains(brand, ignoreCase = true) }.minByOrNull { it.record }
        seen?.let { return Name(it.name.trim(), Name.From.SEEN, it.iccid) }
        return brand?.let { Name(it, Name.From.MANAGER_ID) }
    }

    /** Whether [iccid] is one of the card's eSIMs, [iccids]. */
    fun isEsim(iccid: String, iccids: Collection<String>) = iccids.any { same(it, iccid) }

    /** Whether two ICCIDs are the same: Android may keep the padding F, or the check digit an eSIM list leaves off. */
    private fun same(a: String, b: String): Boolean {
        val x = a.uppercase().trimEnd('F')
        val y = b.uppercase().trimEnd('F')
        if (x.length < 18 || y.length < 18) return x == y
        return x == y || (kotlin.math.abs(x.length - y.length) == 1 && (x.startsWith(y) || y.startsWith(x)))
    }

    /** The company that made the card, with where, by the company and site parts of its factory's GSMA accreditation
     *  number for UICC production (SAS-UP: company-site-UP-expiry, such as "ED-ZI-UP-0824"). By both parts, as
     *  companies' codes clash ("TS-WI" isn't Thales). Read from the GSMA's own SAS-UP certificates, 2026-11. */
    private val SITES = mapOf(
        "ED-ZI" to ("Eastcompeace" to "Zhuhai, China"),
        "KN-DN" to ("Kigen" to "Dublin, Ireland"),
        "KN-NA" to ("Kigen" to "Noida, India"),
        "GD-NT" to ("Giesecke+Devrient" to "Neustadt, Germany"),
        "GD-BA" to ("Giesecke+Devrient" to "Barcelona, Spain"),
        "GI-BA" to ("Giesecke+Devrient" to "Barcelona, Spain"),
        "GD-NG" to ("Giesecke+Devrient" to "Nanchang, China"),
        "GD-CI" to ("Giesecke+Devrient" to "Chennai, India"),
        "GD-MM" to ("Giesecke+Devrient" to "Markham, Canada"),
        "TS-PA" to ("Thales" to "Pont-Audemer, France"),
        "TS-DN" to ("Thales" to "Ditzingen, Germany"),
        "TS-SI" to ("Thales" to "Shanghai, China"),
        "TS-NA" to ("Thales" to "Noida, India"),
        "TS-CA" to ("Thales" to "Cuernavaca, Mexico"),
        "TS-ME" to ("Thales" to "Montgomeryville, USA"),
        "TS-PS" to ("Thales" to "Pinhais, Brazil"),
        "TS-TW" to ("Thales" to "Tczew, Poland"),
        "GO-SA" to ("Thales" to "Johannesburg, South Africa"),
        "IA-VE" to ("IDEMIA" to "Vitré, France"),
        "IA-FK" to ("IDEMIA" to "Flintbek, Germany"),
        "ID-SN" to ("IDEMIA" to "Shenzhen, China"),
        "ID-NA" to ("IDEMIA" to "Noida, India"),
        "ID-YO" to ("IDEMIA" to "Yumbo, Colombia"),
        "VD-MD" to ("Valid" to "Madrid, Spain"),
        "VD-SU" to ("Valid" to "Suzhou, China"),
        "WD-BG" to ("Watchdata" to "Beijing, China"),
        "HO-DG" to ("Hengbao" to "Danyang, China"),
        "HG-BG" to ("Huahong IC Design" to "Beijing, China"),
        "CD-BG" to ("CEC Huada Electronic Design" to "Beijing, China"),
        "CD-DN" to ("Chutian Dragon" to "Dongguan, China"),
        "GC-ZI" to ("Goldpac" to "Zhuhai, China"),
        "HS-ZI" to ("Ho&Bros" to "Zhuhai, China"),
        "XH-ZI" to ("XH Smart Tech" to "Zhuhai, China"),
        "TS-WI" to ("Tongxin Microelectronics" to "Wuxi, China"),
        "BM-SN" to ("Tsingteng Microsystem" to "Shenzhen, China"),
        "CH-SN" to ("Chengtian Weiye" to "Shenzhen, China"),
        "CW-CI" to ("Chengtian Weiye" to "Cixi, China"),
        "PT-BI" to ("Chengtian Weiye" to "Bekasi, Indonesia"),
        "TY-SN" to ("Top Wise Technology" to "Shenzhen, China"),
        "IR-SN" to ("iGear Technology" to "Shenzhen, China"),
        "IF-CG" to ("IF Weibinnai Technology" to "Chongqing, China"),
        "ZG-JG" to ("Zhejiang Chip Advanced Technology" to "Jiaxing, China"),
        "KM-JG" to ("Kona M" to "Jincheon, South Korea"),
        "NC-GP" to ("Nordic eSIM" to "Glostrup, Denmark"),
        "TA-DI" to ("Trasna" to "Dubai, UAE"),
        "TA-PS" to ("Tata Communications" to "Paris, France"),
        "MS-HI" to ("MK Smart" to "Hanoi, Vietnam"),
        "BC-TN" to ("Beautiful Card" to "Taoyuan, Taiwan"),
        "CT-NA" to ("Colorplast Systems" to "Noida, India"),
        "ET-IL" to ("E-Kart" to "Istanbul, Turkey"),
        "ER-AN" to ("Ebtikar Card Systems" to "Ajman, UAE"),
        "GP-DM" to ("Golden Chip Company" to "Dammam, Saudi Arabia"),
        "SI-RH" to ("Saudi Telecom Company" to "Riyadh, Saudi Arabia"),
        "SI-BU" to ("Seshaasai Technologies" to "Bengaluru, India"),
        "IT-BT" to ("Inkript" to "Lebanon"),
        "KD-HD" to ("KL Hi-Tech Secure Print" to "Hyderabad, India"),
        "MT-CI" to ("Mobismart Card Technology" to "Chennai, India"),
        "RS-PE" to ("Rosmerta Technologies" to "Pune, India"),
        "SH-ID" to ("Secure Tech Cards" to "Islamabad, Pakistan"),
        "SL-TA" to ("Sintel Security Print" to "Thika, Kenya"),
        "SN-BE" to ("Sinc Novation" to "Belgrade, Serbia"),
        "SN-FN" to ("Sinc Novation" to "Falkenstein, Germany"),
        "IN-DN" to ("Infineon" to "Dresden, Germany"),
        "IN-WI" to ("Infineon" to "Wuxi, China"),
        "NP-HG" to ("NXP" to "Hamburg, Germany"),
        "NP-KG" to ("NXP" to "Kaohsiung, Taiwan"),
        "NP-TN" to ("NXP" to "Tianjin, China"),
        "SM-CA" to ("STMicroelectronics" to "Caserta, Italy"),
        "SS-RT" to ("STMicroelectronics" to "Rousset, France"),
        "ST-TP" to ("STMicroelectronics" to "Singapore"),
        "SS-SN" to ("STS Microelectronics" to "Shenzhen, China"),
        "SG-YN" to ("Samsung Electronics" to "Yongin, South Korea"),
        "QM-SO" to ("Qualcomm" to "San Diego, USA"),
    )

    /** Makers by the start of the EID, which their GSMA-signed certificate ties to them; checked against it. */
    private val BY_EID = listOf("89086030" to "Eastcompeace")

    /** Who made the card, and where when its factory's number says: by its [eid] first, then by [factory], the
     *  accreditation number it reports ([Lpa.CardInfo.factory]). Null when neither is known. A number from another
     *  scheme than UICC production is never read as one: its codes mean other companies. */
    fun maker(eid: String?, factory: String?): Pair<String, String?>? {
        val number = factory?.trim()?.uppercase()
        val site = number?.takeIf { Regex("[A-Z]{2}-[A-Z]{2}(-UP)?-[0-9]{4}(-.*)?").matches(it) }?.take(5)?.let(SITES::get)
        val byEid = eid?.let { e -> BY_EID.firstOrNull { e.startsWith(it.first) }?.second }
        return when {
            byEid != null -> byEid to site?.takeIf { it.first == byEid }?.second
            site != null -> site
            else -> null
        }
    }

    /** What [category] means, for the Details sheet. */
    const val CATEGORY_HELP = "The class of eSIM chip the card says it is, as the GSMA groups them."

    /** euiccCategory in words: the class of eSIM chip the card says it is. 0 ("other") says nothing, so it's null. */
    fun category(category: Int?): String? = when (category) {
        1 -> "Basic"
        2 -> "Medium"
        3 -> "Contactless"
        else -> null
    }
}

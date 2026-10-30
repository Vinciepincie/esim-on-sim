package com.esimonsim.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.esimonsim.apdu.AccessMethod
import com.esimonsim.apdu.MemoryPrefs
import com.esimonsim.lpa.ActivationCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the store saves, and what it reads back. */
class ProfileStoreStorageTest {
    private val prefs = MemoryPrefs()
    private val store = ProfileStore(prefs)
    private val ac = ActivationCode.parse("LPA:1\$smdp.example.com\$ABC-123")!!

    @Test fun anInstallReadsBackAsItWasRecorded() {
        store.recordInstall(ac, "8901", "Travel", 42L)
        assertEquals(ProfileStore.Install("8901", "Travel", 42L), store.installOf(ac))
    }

    @Test fun anInstallWithoutAnIccidReadsBackWithout() {
        store.recordInstall(ac, null, "Travel", 42L)
        assertNull(store.installOf(ac)!!.iccid)
    }

    @Test fun theSameCodeTypedDifferentlyFindsTheInstall() {
        store.recordInstall(ac, "8901", "Travel", 42L)
        assertEquals("8901", store.installOf(ActivationCode.parse("lpa:1\$SMDP.example.com\$abc-123")!!)!!.iccid)
    }

    @Test fun codesWithoutAMatchingIdAreNeitherSavedNorFound() {
        val bare = ActivationCode.parse("LPA:1\$smdp.example.com\$")!!
        assertEquals("", bare.matchingId)
        store.recordInstall(bare, "8901", "Travel", 42L)
        assertTrue(prefs.values.isEmpty())
        assertNull(store.installOf(bare))
    }

    @Test fun aDamagedRecordCountsAsNoInstall() {
        prefs.edit().putString("install:" + ProfileStore.fingerprint(ac), "{not json").apply()
        assertNull(store.installOf(ac))
    }

    @Test fun notesAreTrimmedRemovedWhenBlankAndNeverIncludeInstalls() {
        store.recordInstall(ac, "8901", "Travel", 42L)
        store.setNote("8901", "  work  ")
        assertEquals(mapOf("8901" to "work"), store.notes())
        store.setNote("8901", "   ")
        assertEquals(emptyMap<String, String>(), store.notes())
    }

    @Test fun hiddenEsimsComeAndGo() {
        store.setHidden("a", true); store.setHidden("b", true)
        assertEquals(setOf("a", "b"), store.hidden())
        store.setHidden("a", false)
        assertEquals(setOf("b"), store.hidden())
    }

    @Test fun switchSetupDefaultsToBothAndKeepsTheLastChoice() {
        assertEquals(ProfileStore.SwitchSetup(true, true), store.switchSetup())
        store.setSwitchSetup(ProfileStore.SwitchSetup(false, true))
        assertEquals(ProfileStore.SwitchSetup(false, true), store.switchSetup())
    }

    @Test fun dismissedSameNameWarningsAddUp() {
        store.dismissSameName("a"); store.dismissSameName("b")
        assertEquals(setOf("a", "b"), store.dismissedSameName())
    }

    @Test fun theLastSlotIsKeptPerWayOfReachingTheCard() {
        assertEquals(-1, store.lastSlot(AccessMethod.ADB))
        store.setLastSlot(AccessMethod.ADB, 1)
        store.setLastSlot(AccessMethod.DIRECT, 0)
        assertEquals(1, store.lastSlot(AccessMethod.ADB))
        assertEquals(0, store.lastSlot(AccessMethod.DIRECT))
        assertEquals(-1, store.lastSlot(AccessMethod.ROOT))
        assertTrue(store.notes().isEmpty())
    }

    @Test fun stoppingCardAccessLastsUntilItIsCleared() {
        assertFalse(store.stopped())
        store.setStopped(true)
        assertTrue(ProfileStore(prefs).stopped()) // as after the app restarted
        store.setStopped(false)
        assertFalse(ProfileStore(prefs).stopped())
    }

    @Test fun ownCardsAddUp() {
        assertEquals(emptySet<String>(), store.ownCards())
        store.addOwnCard("89001"); store.addOwnCard("89002")
        assertEquals(setOf("89001", "89002"), store.ownCards())
    }

    @Test fun theAppsContextReachesTheSamePreferences() {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
        }
        ProfileStore(context).setNote("8901", "x")
        assertEquals("x", store.notes()["8901"])
    }
}

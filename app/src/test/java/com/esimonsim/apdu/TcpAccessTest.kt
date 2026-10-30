package com.esimonsim.apdu

import com.esimonsim.adb.AdbIdentity
import io.github.muntashirakon.adb.AdbAuthenticationFailedException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey

/** Android 10 and older: card access over the debugging port a computer turned on ("adb tcpip"). The phone
 *  itself can't be faked here (the emulator's adbd doesn't listen in the guest either), so these cover the
 *  decisions around it. */
class TcpAccessTest {
    @Test fun portsTriedInOrder() {
        assertEquals(listOf(5555), AdbApduBackend.tcpPorts(typed = null, last = null))
        assertEquals(listOf(5037, 5555), AdbApduBackend.tcpPorts(typed = null, last = 5037))
        assertEquals(listOf(5555), AdbApduBackend.tcpPorts(typed = null, last = 5555))
        assertEquals(listOf(6000), AdbApduBackend.tcpPorts(typed = 6000, last = 5037)) // typed means only that one
    }

    @Test fun theStreamHelperRunsAsTheShellOrAsRoot() {
        fun hello(version: Int, uid: Int) = JSONObject().put("version", version).put("uid", uid)
        assertTrue(AdbApduBackend.isStreamHelper(hello(1, 2000)))
        assertTrue(AdbApduBackend.isStreamHelper(hello(1, 0))) // debugging that itself runs as root
        assertFalse(AdbApduBackend.isStreamHelper(hello(1, 10123)))
        assertFalse(AdbApduBackend.isStreamHelper(hello(2, 2000)))
    }

    @Test fun commandsRunWithoutATerminalBelowAndroid11() {
        assertEquals("exec:", AdbApduBackend.shellService(23))
        assertEquals("exec:", AdbApduBackend.shellService(29))
        assertEquals("shell:", AdbApduBackend.shellService(30)) // Wireless debugging, as verified on the phone
    }

    @Test fun failuresSayWhatToDo() {
        // The phone answered the app's key with another question: it doesn't know the key.
        val unknown = AdbApduBackend.tcpFailure(IOException("x", AdbAuthenticationFailedException()), cancelled = false)
        assertTrue(unknown is AllowNeededException && !unknown.asked)
        val direct = AdbApduBackend.tcpFailure(AdbAuthenticationFailedException(), cancelled = false)
        assertTrue(direct is AllowNeededException && !direct.asked)
        // Asked, and no answer came: as it was.
        val asked = AllowNeededException(asked = true)
        assertSame(asked, AdbApduBackend.tcpFailure(asked, cancelled = false))
        val closed = DebuggingPortClosedException()
        assertSame(closed, AdbApduBackend.tcpFailure(closed, cancelled = false))
        // People stopped waiting: no error to show for that.
        val stopped = AdbApduBackend.tcpFailure(IOException("Connection failed"), cancelled = true)
        assertTrue(stopped is AllowNeededException && stopped.asked && stopped.cancelled)
        // Anything else is no reason to send people back to the computer or the question.
        val other = AdbApduBackend.tcpFailure(IOException("Unexpected card helper."), cancelled = false)
        assertFalse(other is AllowNeededException || other is DebuggingPortClosedException)
        assertTrue(other.message!!.startsWith("Couldn't connect to Android's debugging tool"))
        assertTrue(closed.message!!.contains("adb tcpip 5555"))
    }

    /** Settings.Global as the shell changes it: [shell] false is an app posing as adbd, which can't. */
    private class FakeSettings(private val shell: Boolean = true) {
        val values = HashMap<String, String>()
        val commands = ArrayList<String>()
        fun run(service: String) {
            commands += service
            if (!shell) return
            val words = service.removePrefix("exec:").split(' ')
            when (words.take(3)) {
                listOf("settings", "put", "global") -> values[words[3]] = words[4]
                listOf("settings", "delete", "global") -> values.remove(words[3])
            }
        }
    }

    @Test fun androidsDebuggingToolProvesItselfBeforeGettingAnything() {
        val settings = FakeSettings()
        AdbApduBackend.proveShell(5555, settings::run) { settings.values[AdbApduBackend.PROBE_KEY] }
        assertEquals(2, settings.commands.size)
        assertTrue(settings.commands[0], settings.commands[0].matches(
            Regex("exec:settings put global ${AdbApduBackend.PROBE_KEY} [0-9a-f]{32}")))
        assertEquals("exec:settings delete global ${AdbApduBackend.PROBE_KEY}", settings.commands[1])
        assertTrue(settings.values.isEmpty()) // nothing left behind
        // A fresh value each time: one seen before proves nothing.
        val again = FakeSettings()
        AdbApduBackend.proveShell(5555, again::run) { again.values[AdbApduBackend.PROBE_KEY] }
        assertFalse(again.commands[0] == settings.commands[0])
    }

    @Test fun anAppPosingAsTheDebuggingToolIsRefused() {
        val app = FakeSettings(shell = false)
        // Whatever it answers, the value can't be there; nor is one left from before.
        val e = assertThrows(NotDebuggingToolException::class.java) {
            AdbApduBackend.proveShell(5555, app::run) { "0123456789abcdef0123456789abcdef" }
        }
        assertEquals(1, app.commands.size) // nothing more goes to it
        assertTrue(e.message!!, e.message!!.contains("port 5555") && e.message!!.contains("adb tcpip 5556"))
        assertSame(e, AdbApduBackend.tcpFailure(e, cancelled = false))
        // A command that doesn't end in time fails the connection without blaming anyone.
        val slow = assertThrows(IOException::class.java) {
            AdbApduBackend.proveShell(5555, { throw IOException("Android's debugging tool didn't answer.") }) { null }
        }
        assertFalse(slow is NotDebuggingToolException)
    }

    @Test fun allowButtonIsWhatThePhoneShows() {
        // SystemUI's "Allow USB debugging?" question: "OK" until Android 10.
        assertEquals("OK", AdbIdentity.allowButton(23))
        assertEquals("OK", AdbIdentity.allowButton(28))
        assertEquals("Allow", AdbIdentity.allowButton(29))
    }

    @Test fun fingerprintIsWhatThePhoneShows() {
        val key = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().public as RSAPublicKey
        // libadb sends its own encoding of the key; the phone shows the MD5 of exactly that.
        val encode = Class.forName("io.github.muntashirakon.adb.AndroidPubkey")
            .getDeclaredMethod("encode", RSAPublicKey::class.java).apply { isAccessible = true }
        val libadb = encode.invoke(null, key) as ByteArray
        assertTrue(libadb.contentEquals(AdbIdentity.androidPubkey(key)))
        val fingerprint = AdbIdentity.fingerprint(key)
        assertTrue(fingerprint, fingerprint.matches(Regex("([0-9A-F]{2}:){15}[0-9A-F]{2}")))
        val md5 = MessageDigest.getInstance("MD5").digest(libadb).joinToString(":") { "%02X".format(it) }
        assertEquals(md5, fingerprint)
    }
}

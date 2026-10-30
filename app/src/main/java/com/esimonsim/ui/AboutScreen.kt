package com.esimonsim.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.esimonsim.R
import com.esimonsim.apdu.AccessMethod

/** What the app is for, the ways it reaches a card (and what root, Shizuku and Wireless debugging are), and what it
 *  sends over the internet. An activity of its own, so setup and the card screen can both open it; a code shared to the
 *  app meanwhile closes it, since MainActivity is singleTask. */
class AboutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val method = intent.getStringExtra(EXTRA_METHOD)?.let { m -> AccessMethod.entries.firstOrNull { it.name == m } }
        val helperWorks = intent.getBooleanExtra(EXTRA_HELPER_WORKS, true)
        setContent { AppTheme { AboutScreen(method, helperWorks, onBack = ::finish) } }
    }

    companion object {
        private const val EXTRA_METHOD = "method"
        private const val EXTRA_HELPER_WORKS = "helper-works"

        /** Marks [ui]'s card access as the one in use, while it's connected. */
        fun open(context: Context, ui: LpaUi) {
            context.startActivity(Intent(context, AboutActivity::class.java)
                .putExtra(EXTRA_METHOD, ui.method?.takeIf { ui.access == AccessStatus.READY }?.name)
                .putExtra(EXTRA_HELPER_WORKS, !ui.helperUnavailable))
        }
    }
}

/** [method]: the card access in use, if connected. [helperWorks]: the background helper can run on this phone. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutScreen(method: AccessMethod?, helperWorks: Boolean, onBack: () -> Unit) {
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back") }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppHeader()
            AboutCard("What it's for") {
                Body("$APP_NAME manages the eSIMs on a removable eUICC: a physical SIM card that holds several eSIMs " +
                    "at once. It's also commonly known as an eSIM adapter, a physical eSIM card or by its brand name, " +
                    "such as 5ber, 9eSIM, eSIM.me and ESTKme.")
                Body("Put one in the SIM slot and a phone without eSIM support can use eSIMs too. Add an eSIM with the " +
                    "QR code your provider gives you, then switch, rename and delete your eSIMs here.")
                Body("Your eSIMs are stored on the card itself, not in this app, so they go wherever the card goes. " +
                    "And you don't need the card maker's app, which helps when it's locked, gone or broken.")
                Body("A built-in eSIM, if your phone has one, is still set up in your phone's Settings as usual.")
            }
            Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("How it reaches your card", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() })
                Body("Your card decides which apps may manage it, and most cards only let their maker's app in. No " +
                    "Android permission can change that. So each time it starts, this app tries the ways below in " +
                    "order and uses the first that works.")
            }
            AboutCard("Root", inUse = method == AccessMethod.ROOT) {
                Body("Rooting gives you full control of a phone. It's something people do themselves; most phones " +
                    "aren't rooted, and this app doesn't need it.")
                Body("On a rooted phone, this app asks your root app for access when it starts. Nothing to set up, " +
                    "and it works without Wi-Fi. Its small helper ends when you swipe this app away from Recent apps " +
                    "or tap \"Stop card access\" in the app's menu. If your root app never asks, allow $APP_NAME in it.")
            }
            AboutCard("Direct", inUse = method == AccessMethod.DIRECT) {
                // Android's secure element API (OMAPI) came with Android 9.
                if (Build.VERSION.SDK_INT >= 28) {
                    Body("Some cards let any app manage them. Then this app reaches the card through Android itself, " +
                        "with nothing to set up.")
                    Body("Android keeps mobile data and roaming from apps, though, so after a switch the app offers " +
                        "your phone's SIM settings for those. To have the app set them, see Details › Mobile data " +
                        "and roaming.")
                } else Body("Some cards let any app manage them, through Android itself. That needs Android 9 or " +
                    "newer, so not on this phone.")
            }
            AboutCard("Shizuku", inUse = method == AccessMethod.SHIZUKU) {
                Body("Shizuku is a separate app that keeps Android's debugging tool running for the apps you allow, " +
                    "so you set that up in Shizuku instead of in each app. You don't need it: this app only uses it " +
                    "if it's on your phone.")
                Body("If Shizuku is running, this app asks it for access. After that there's nothing to set up here. " +
                    "This app starts a small helper through it, which only talks to the card's eSIM manager and " +
                    "applies the mobile data and roaming choices you ticked. It ends when you swipe this app away " +
                    "from Recent apps or tap \"Stop card access\" in the app's menu. Shizuku itself keeps running.")
                Body("Shizuku has to be started again after your phone restarts. If you start it with Wireless " +
                    "debugging, Android can stop it when Wi-Fi drops, so don't count on it without Wi-Fi. Turning on " +
                    "\"USB debugging\" in Developer options may help, but some banking apps won't open while it's on.")
                Body("To take this app's access away, stop Shizuku, or remove this app in Shizuku's own app.")
            }
            if (Build.VERSION.SDK_INT >= 30) WirelessDebuggingCard(method == AccessMethod.ADB, helperWorks)
            else UsbDebuggingCard(method == AccessMethod.ADB)
            AboutCard("Internet and privacy") {
                Lines(INTERNET_USE)
                Body("Names you give eSIMs are saved on the card, so other phones show them too. Notes and hidden " +
                    "eSIMs stay on this phone.")
            }
        }
    }
}

/** The app's icon, name and version. */
@Composable
private fun AppHeader() {
    val context = LocalContext.current
    val version = remember { versionName(context) }
    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // The launcher icon's own art: its 72dp visible circle is drawn at 56dp.
        Box(Modifier.size(56.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null,
                Modifier.requiredSize(84.dp), tint = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(APP_NAME, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            version?.let {
                Text("Version $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun versionName(context: Context): String? = runCatching {
    val pm = context.packageManager
    if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0)).versionName
    else @Suppress("DEPRECATION") pm.getPackageInfo(context.packageName, 0).versionName
}.getOrNull()

@Composable
private fun WirelessDebuggingCard(inUse: Boolean, helperWorks: Boolean) =
    AboutCard("Wireless debugging", inUse = inUse) {
        Body("A tool for app developers that comes with Android, in the hidden Developer options menu. Android's " +
            "debugging tool is allowed to talk to any SIM card. So after pairing, this app connects to Wireless " +
            "debugging on this phone itself, and reaches your card through it. It works with any card, without a " +
            "computer or root.")
        AboutItem("What the access is used for", "Pairing gives this app the same access a computer gets with " +
            "debugging. $ACCESS_USE")
        // Not where the helper can't start: there card access always runs over Wireless debugging.
        if (helperWorks) AboutItem("Without Wi-Fi",
            "The helper keeps running in the background after Wireless debugging goes off, until your phone restarts " +
                "or this app is updated, as long as \"USB debugging\" is on too. That way you can switch eSIMs abroad " +
                "without Wi-Fi. Without USB debugging, Android stops the helper when Wireless debugging goes off.")
        AboutItem("Does card access use the internet?", "No. Wireless debugging connects the app to Android on this same " +
            "phone; Android only asks for Wi-Fi before it lets you switch it on.")
        AboutItem("Turning it off",
            "Android switches Wireless debugging off after a restart or a Wi-Fi change, so you'll turn it back on now " +
                "and then.\n\n" +
                "While it's on, only devices paired with a code can connect. Some banking apps won't open while it's " +
                "on, so switch it off when you're done if you like: the pairing stays saved, though Android can " +
                "forget it after 7 days. " +
                (if (helperWorks) "Unless \"USB debugging\" is on, that also ends the background helper.\n\n" else "\n\n") +
                "Some banking apps also won't open while Developer options is on. Turning that off stops card access " +
                "until you turn it back on. It can also turn \"Disable adb authorization timeout\" back off, if you " +
                "turned that on, so Android may forget the pairing again.\n\n" +
                "To remove this app's access completely, tap \"Stop card access\" in the app's menu, then:\n" +
                path("Wireless debugging", "Paired devices", APP_NAME, "Forget"))
    }

/** Android 10 and older have no Wireless debugging: a computer turns on the debugging tool for apps on the phone, until
 *  it restarts. The helper outlives USB debugging being turned off on Android 7.1 and older only; from 8, Android ends
 *  it along with its debugging service. */
@Composable
private fun UsbDebuggingCard(inUse: Boolean) =
    AboutCard("USB debugging", inUse = inUse) {
        Body("A tool for app developers that comes with Android, in the hidden Developer options menu. Android's " +
            "debugging tool is allowed to talk to any SIM card. On Android ${Build.VERSION.RELEASE}, only a computer " +
            "can let apps on the phone reach it, so setting it up takes a computer, and again after each restart. " +
            "Wi-Fi isn't needed. It works with any card.")
        AboutItem("What the access is used for", "Allowing it gives this app the same access a computer gets with " +
            "USB debugging. $ACCESS_USE")
        AboutItem("Does card access use the internet?", "No: the app connects to Android on this same phone.")
        AboutItem("Turning it off",
            "Restarting your phone turns it off. Until then, other devices on your network can ask to connect too, so " +
                "only tap \"${allowButton()}\" when this app asks.\n\n" +
                "Some banking apps won't open while USB debugging or Developer options is on. " + (if (Build.VERSION.SDK_INT < 26)
                    "Once card access is running, it usually keeps working after you turn that off, until your phone " +
                        "restarts."
                    else "Card access stops when you turn it off, and works again when you turn it back on.") +
                "\n\nTo remove this app's access completely, tap \"Stop card access\" in the app's menu, then (this " +
                "also removes any computers you allowed):\n" + path("Developer options", "Revoke USB debugging authorizations"))
    }

/** What the debugging access is used for, the same with Wireless and USB debugging. */
private const val ACCESS_USE = "The app uses it only to start a small helper that talks to the card's eSIM manager " +
    "and, after a switch, applies the mobile data and roaming choices you ticked. It refuses everything else, and no " +
    "other app can use it."

/** What the app uses the internet for, the same on every Android version. */
private val INTERNET_USE = internetUse(QrScan.NETWORK_NOTE)

/** [scannerNote]: what the build's QR scanner sends, if anything (the google build's is Google Play services'). Lines
 *  starting "• " are a list ([Lines]). */
internal fun internetUse(scannerNote: String): String {
    val uses = "the internet to:\n" +
        "• download an eSIM you add, directly from that eSIM provider's server\n" +
        // SGP.22 notifications, which the card keeps for the events the eSIM's provider asked for.
        "• tell a provider when its eSIM is installed, turned on or off, or deleted, if it asks to, as phones with " +
        "eSIM do\n" +
        "• check for eSIMs sent to your card when you ask, by asking eSIM discovery servers (the one your card names, " +
        "plus the GSMA's and Google's) about your card's EID"
    return if (scannerNote.isBlank()) "The app only uses $uses\nNo account, no tracking."
        else "The app uses $uses\n${scannerNote.trim()} No account."
}

/** A titled card; [inUse] marks the way this app reaches the card right now. */
@Composable
private fun AboutCard(title: String, inUse: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), colors = CardDefaults.outlinedCardColors(containerColor = cardColor())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                if (inUse) { Spacer(Modifier.width(8.dp)); ActiveBadge("In use") }
            }
            content()
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** [text] line by line; lines starting "• " as a list, wrapping under their own text rather than the bullet. */
@Composable
private fun Lines(text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        text.lines().forEach { line ->
            if (line.startsWith("• ")) Row {
                Body("•", Modifier.padding(start = 4.dp, end = 8.dp))
                Body(line.removePrefix("• "))
            } else Body(line)
        }
    }
}

@Composable
private fun AboutItem(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Body(body)
    }
}

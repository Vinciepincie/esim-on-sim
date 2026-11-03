package com.esimonsim.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A row of the Licenses page. [license]: its license, in a word or two. [files]: what opens, one after the other,
 *  from assets/licenses: what only this library says, then the license texts that refers to. [note]: a line above
 *  those, for libraries with nothing else to say. */
internal class Library(val name: String, val license: String, val files: List<String>, val note: String? = null)

internal const val APACHE = "apache-2.0.txt"

/** This app's own license, shown apart from what it uses. */
internal val THIS_APP = Library(APP_NAME, "MIT", listOf("esim-on-sim.txt"))

private const val SOURCE_CODE = "https://github.com/Vinciepincie/esim-on-sim"

/** From these projects' license/NOTICE.txt, at the versions the app uses: their jars don't carry one. Not Kotlin's own
 *  line, which is about its compiler. */
private const val KOTLIN_NOTICES = "kotlinx.coroutines library. Copyright 2016-2024 JetBrains s.r.o and contributors\n" +
    "kotlinx.serialization library. Copyright 2017-2019 JetBrains s.r.o and respective authors and developers\n" +
    "Kotlin. Copyright JetBrains s.r.o and Kotlin Programming Language contributors."

/** What's in the APK, read from each library's own files. LicensesTest checks the versions against build.gradle.kts; a
 *  bump, or a new library, may change what's owed here. */
internal val LIBRARIES = listOf(
    Library("Jetpack Compose and AndroidX", "Apache-2.0", listOf(APACHE), "Copyright The Android Open Source Project."),
    Library("Kotlin and kotlinx", "Apache-2.0", listOf(APACHE), KOTLIN_NOTICES),
    Library("Material Icons", "Apache-2.0", listOf(APACHE), "Most of the app's icons, from Google's Material Icons."),
    Library("libadb-android 3.1.1", "Apache-2.0, BSD, MIT", listOf("libadb-android.txt", APACHE)),
    Library("spake2-android 2.2.1", "LGPL-3.0, ISC, MIT, Apache-2.0",
        listOf("spake2-android.txt", "lgpl-3.0.txt", "gpl-3.0.txt", APACHE)),
    Library("Bouncy Castle 1.81", "MIT-style", listOf("bouncy-castle.txt")),
    Library("HiddenApiBypass 4.3", "Apache-2.0", listOf(APACHE), "Copyright 2021 LSPosed."),
    Library("Shizuku-API 13.1.5", "MIT", listOf("shizuku-api.txt")),
    Library("ZXing core 3.5.4", "Apache-2.0", listOf("zxing.txt", APACHE)),
    Library("Small helper libraries", "Apache-2.0", listOf(APACHE),
        "JSpecify 1.0.0, JetBrains Annotations 23.0.0 and Guava ListenableFuture 1.0."),
) + QrScan.LIBRARIES

/** The page linked from About as "Libraries & licenses": the app's license and source code, then each library's. */
@Composable
internal fun LicensesPage(modifier: Modifier, scroll: ScrollState, onOpen: (Library) -> Unit) {
    val links = LocalUriHandler.current
    Column(modifier.verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        DetailsSection("This app") {
            InfoCard {
                InfoRow(THIS_APP.name, THIS_APP.license) { onOpen(THIS_APP) }
                InfoDivider()
                InfoRow("Source code", "GitHub") { runCatching { links.openUri(SOURCE_CODE) } }
            }
        }
        DetailsSection("Thanks to these projects") {
            InfoCard {
                LIBRARIES.forEachIndexed { i, library ->
                    if (i > 0) InfoDivider()
                    InfoRow(library.name, library.license) { onOpen(library) }
                }
            }
        }
    }
}

/** [library]'s files. In a lazy list, as the GPL alone is 35 KB of text; read from the APK only when opened. */
@Composable
internal fun LicenseText(library: Library, modifier: Modifier) {
    val context = LocalContext.current
    val links = LocalUriHandler.current
    val text by produceState(emptyList<String>(), library) {
        value = withContext(Dispatchers.IO) {
            paragraphs(library.files.joinToString("\n\n") { file ->
                context.assets.open("licenses/$file").bufferedReader().use { it.readText() }
            })
        }
    }
    // Balanced line breaks are for the app's own short texts: slow on paragraphs this long.
    val style = MaterialTheme.typography.bodySmall.copy(lineBreak = LineBreak.Simple)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Not before the text: a first layout with only this item would lose the position saved across a rotation.
        if (text.isNotEmpty()) library.note?.let { note -> item { Text(note, style = style, color = color) } }
        items(text) { paragraph ->
            Text(remember(paragraph) { linked(paragraph, links) }, style = style, color = color)
        }
    }
}

/** Under this, a line ended where its writer ended it. Above, it may have run up to the file's right margin. */
private const val WRAPPED = 50

/** The next line carries on from this one: it's long, and looks cut off mid-sentence. Not an address, or a line of
 *  rules like a "#" list or a row of dashes. */
private fun carriesOn(line: String) = line.length >= WRAPPED && (line.last().isLetterOrDigit() || line.last() in ",;)\"") &&
    !line.startsWith('#') && !line.startsWith("http")

/** A license file's paragraphs, for a screen narrower than its lines: the lines of a paragraph, wrapped for a wide
 *  screen, become one, so they don't break twice. Other lines, as in headings and copyright lists, keep their own. */
internal fun paragraphs(text: String): List<String> {
    val all = ArrayList<String>()
    val now = StringBuilder()
    var full = false
    for (line in text.lineSequence().map { it.trim() }) {
        if (line.isEmpty()) {
            if (now.isNotEmpty()) all += now.toString()
            now.clear()
        } else {
            if (now.isNotEmpty()) now.append(if (full) ' ' else '\n')
            now.append(line)
            full = carriesOn(line)
        }
    }
    if (now.isNotEmpty()) all += now.toString()
    return all
}

private val URL = Regex("""https?://[^\s<>()"]*[^\s<>()".,;:]""")

/** [text] with its web addresses tappable. A phone without a browser just does nothing. */
private fun linked(text: String, links: UriHandler): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (url in URL.findAll(text)) {
        append(text, at, url.range.first)
        withLink(LinkAnnotation.Url(url.value, TextLinkStyles(SpanStyle(textDecoration = TextDecoration.Underline))) {
            runCatching { links.openUri(url.value) }
        }) { append(url.value) }
        at = url.range.last + 1
    }
    append(text, at, text.length)
}

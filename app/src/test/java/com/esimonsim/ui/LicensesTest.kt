package com.esimonsim.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Licenses page: what it lists is what the build ships, and what it opens is there. */
class LicensesTest {
    private val build = File("build.gradle.kts").readText()
    private val everyRow = LIBRARIES + THIS_APP

    /** Only where this build has it: the standalone APK doesn't have the Google files. */
    private fun asset(name: String) = (listOf("src/main/assets/licenses") +
        if (QrScan.LIBRARIES.isEmpty()) emptyList() else listOf("src/google/assets/licenses"))
        .map { File(it, name) }.firstOrNull { it.exists() }

    /** The version build.gradle.kts asks for. */
    private fun version(library: String) =
        Regex("\"${Regex.escape(library)}:([^\"]+)\"").find(build)?.groupValues?.get(1) ?: error("$library isn't in the build")

    private fun assertListed(row: String, library: String) {
        val version = version(library)
        assertTrue("$library is at $version: check its license, then update the Licenses page",
            LIBRARIES.any { it.name == "$row $version" })
    }

    @Test fun everyRowOpensFilesThatExist() = everyRow.forEach { row ->
        assertTrue(row.name, row.files.isNotEmpty())
        row.files.forEach { assertTrue("${row.name}: $it", asset(it)?.exists() == true) }
    }

    @Test fun everyFileIsUsed() {
        val used = everyRow.flatMap { it.files }.toSet()
        // The Google files only show in the google build, whose rows the standalone build doesn't have.
        File("src/main/assets/licenses").list()!!.forEach { assertTrue(it, it in used) }
    }

    @Test fun libraryVersionsMatchTheBuild() {
        assertListed("libadb-android", "com.github.MuntashirAkon:libadb-android")
        assertListed("HiddenApiBypass", "org.lsposed.hiddenapibypass:hiddenapibypass")
        assertListed("Shizuku-API", "dev.rikka.shizuku:api")
        assertListed("ZXing core", "com.google.zxing:core")
        if (QrScan.LIBRARIES.isNotEmpty()) assertListed("Google code scanner", "com.google.android.gms:play-services-code-scanner")
    }

    @Test fun theAppsLicenseIsTheRepositorys() = assertEquals(File("../LICENSE").readText().replace("\r\n", "\n").trim(),
        asset("esim-on-sim.txt")!!.readText().replace("\r\n", "\n").trim())

    // ---------------- reading them on a phone ----------------

    @Test fun wrappedLinesBecomeOneParagraph() = assertEquals(listOf(
        "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated."),
        paragraphs("Permission is hereby granted, free of charge, to any person\n" +
            "   obtaining a copy of this software and associated.\n"))

    @Test fun shortLinesAndBlankLinesStay() = assertEquals(listOf(
        "Apache License\nVersion 2.0, January 2004", "1. Definitions.",
        "Copyright 2013 Cameron Gutman\nCopyright 2020 Sam Palmer"),
        paragraphs("      Apache License\n  Version 2.0, January 2004\n\n\n 1. Definitions.\n\n" +
            "Copyright 2013 Cameron Gutman\nCopyright 2020 Sam Palmer\n"))

    @Test fun longLinesThatEndThereStay() = assertEquals(listOf(
        "Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\nEveryone is permitted.",
        "https://github.com/MuntashirAkon/spake2-java/tree/2.2.1\nhttps://github.com/MuntashirAkon/spake2-c",
        "--------------------------------------------------------\nNOTICES FOR BARCODE4J",
        "############################################################\n# A rule, then a heading"),
        paragraphs("Copyright (C) 2007 Free Software Foundation, Inc. <https://fsf.org/>\nEveryone is permitted.\n\n" +
            "https://github.com/MuntashirAkon/spake2-java/tree/2.2.1\nhttps://github.com/MuntashirAkon/spake2-c\n\n" +
            "--------------------------------------------------------\nNOTICES FOR BARCODE4J\n\n" +
            "############################################################\n# A rule, then a heading\n"))

    @Test fun nothingInAnEmptyFile() = assertEquals(emptyList<String>(), paragraphs("\n \n"))

    @Test fun everyWordOfALicenseSurvives() {
        val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() } }
        listOf("apache-2.0.txt", "gpl-3.0.txt", "lgpl-3.0.txt", "libadb-android.txt", "spake2-android.txt").forEach {
            val text = asset(it)!!.readText()
            assertEquals(it, words(text), words(paragraphs(text).joinToString("\n\n")))
        }
    }
}

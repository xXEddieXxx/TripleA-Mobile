package org.triplea.mobile.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri

const val SOURCE_URL = "https://github.com/xXEddieXxx/TripleA-Mobile"
private const val UPSTREAM_URL = "https://github.com/triplea-game/triplea"
private const val GPL_URL = "https://www.gnu.org/licenses/gpl-3.0.html"

/** A third party component and the license it is distributed under. */
private class Component(val name: String, val license: String, val url: String)

private val COMPONENTS = listOf(
    Component("TripleA game engine, unit images, flags and sounds", "GPL-3.0", UPSTREAM_URL),
    Component("Android Jetpack (Compose, Activity, Lifecycle, Navigation, Core)", "Apache-2.0", "https://developer.android.com/jetpack"),
    Component("Kotlin and kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    Component("Guava", "Apache-2.0", "https://github.com/google/guava"),
    Component("Gson", "Apache-2.0", "https://github.com/google/gson"),
    Component("Apache Commons IO, Lang, Text, Math", "Apache-2.0", "https://commons.apache.org"),
    Component("Woodstox and StAX2 API", "Apache-2.0 / BSD-2-Clause", "https://github.com/FasterXML/woodstox"),
    Component("SnakeYAML Engine", "Apache-2.0", "https://bitbucket.org/snakeyaml/snakeyaml-engine"),
    Component("SLF4J", "MIT", "https://www.slf4j.org"),
    Component("Project Lombok (build time only)", "MIT", "https://projectlombok.org"),
    Component("Jakarta XML Binding API", "BSD-3-Clause (EDL 1.0)", "https://github.com/jakartaee/jaxb-api"),
    Component("JSR-305 annotations", "BSD-3-Clause", "https://github.com/findbugsproject/findbugs"),
    Component("JetBrains annotations", "Apache-2.0", "https://github.com/JetBrains/java-annotations"),
    Component("desugar_jdk_libs", "GPL-2.0 with Classpath Exception", "https://github.com/google/desugar_jdk_libs"),
)

/** About page: version, license, source code, and the licenses of everything the app ships. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    fun open(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "back") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("TripleA Mobile $version", style = MaterialTheme.typography.headlineSmall)
            Text(
                "An unofficial Android port of TripleA. Not made by or affiliated with the TripleA project.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Section("License")
            Text(
                "This program is free software: you can redistribute it and/or modify it under the terms of the " +
                    "GNU General Public License as published by the Free Software Foundation, either version 3 of the " +
                    "License, or (at your option) any later version. It is distributed WITHOUT ANY WARRANTY; see the " +
                    "license for details.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "It is based on the TripleA game engine, Copyright © the TripleA developers, licensed under the " +
                    "GPL-3.0. The engine was modified for Android (no Swing/AWT, no networking); the complete source " +
                    "code of this app, including those modifications, is available at the address below.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinkRow("Source code of this app", SOURCE_URL) { open(SOURCE_URL) }
            LinkRow("TripleA (upstream project)", UPSTREAM_URL) { open(UPSTREAM_URL) }
            LinkRow("GNU General Public License v3", GPL_URL) { open(GPL_URL) }

            Section("Maps")
            Text(
                "Maps are made by the TripleA community and downloaded from their own repositories at " +
                    "github.com/triplea-maps. Each map belongs to its authors and is subject to its own terms; " +
                    "see the README or description of the map.",
                style = MaterialTheme.typography.bodyMedium,
            )

            Section("Open source components")
            COMPONENTS.forEach { component ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), onClick = { open(component.url) }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Text(component.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                        Text(component.license, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text(
                "Apache-2.0 components: Licensed under the Apache License, Version 2.0; you may not use these files " +
                    "except in compliance with the License, available at http://www.apache.org/licenses/LICENSE-2.0. " +
                    "Software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES " +
                    "OR CONDITIONS OF ANY KIND.\n\nMIT and BSD components: Permission is hereby granted, free of charge, " +
                    "to any person obtaining a copy of the software, to deal in the software without restriction, " +
                    "subject to the copyright notice and permission notice being included; THE SOFTWARE IS PROVIDED " +
                    "\"AS IS\", WITHOUT WARRANTY OF ANY KIND.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp))
    HorizontalDivider(Modifier.padding(bottom = 6.dp))
}

@Composable
private fun LinkRow(label: String, url: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Text(url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
    }
}

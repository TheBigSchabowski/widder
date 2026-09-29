import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    // Dispatchers.Main on the Swing event thread; same version as the coroutines core Compose pulls in.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
}

// jpackage lives in a full JDK; the JDK 21 that Gradle provisions may be a JBR without it.
fun systemJdk(): String {
    val javaHome = File("/usr/libexec/java_home")
    if (!javaHome.canExecute()) return System.getProperty("java.home")
    val process = ProcessBuilder(javaHome.path).start()
    return process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
}

compose.desktop {
    application {
        mainClass = "widder.desktop.MainKt"
        javaHome = systemJdk()
        // Skiko loads its native library; newer JDKs warn unless this is allowed explicitly.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
        // ./gradlew :desktopApp:run -PdataDir=/some/folder keeps test runs away from the real collection.
        (findProperty("dataDir") as String?)?.let { jvmArgs += "-Dwidder.dataDir=$it" }
        (findProperty("windowSize") as String?)?.let { jvmArgs += "-Dwindow.size=$it" }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "Widder"
            packageVersion = property("app.version") as String
            // javax.sound needs these modules; jpackage only bundles what it is told to.
            modules("java.desktop")
            macOS {
                bundleID = "de.privat.widder"
                iconFile.set(project.file("icon.icns"))
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>Widder nimmt Audio für deine Karteikarten auf.</string>
                    """.trimIndent()
                }
            }
        }
    }
}

tasks.register<JavaExec>("renderScreens") {
    description = "Renders the main screens at phone and desktop size into build/screens."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "widder.desktop.RenderScreensKt"
    workingDir = projectDir
}

tasks.register<JavaExec>("renderIcon") {
    description = "Renders the app icon as a 1024 px PNG into build/icon-1024.png."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass = "widder.desktop.RenderIconKt"
    workingDir = projectDir
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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
// Only macOS has java_home; elsewhere (the Linux packages) Gradle's own JVM does.
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
            // Each format can only be built on its own operating system: the DMG on the Mac, the DEB on Linux.
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "Widder"
            packageVersion = property("app.version") as String
            description = "Karteikarten und Akkord-Gehörtraining für Musik, mit Spaced Repetition"
            vendor = "TheBigSchabowski"
            // javax.sound needs java.desktop, the AAC decoder (jaad) logs through java.logging; jpackage only
            // bundles what it is told to.
            modules("java.desktop", "java.logging")
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
            linux {
                iconFile.set(project.file("icon.png"))
                packageName = "widder"
                debMaintainer = "TheBigSchabowski@users.noreply.github.com"
                appCategory = "education"
                menuGroup = "Education;"
            }
        }
    }
}

// jpackage works out the .deb dependencies from the libraries installed on the build machine. A server or
// container without the X11 libraries AWT loads would leave them out, and the app would not start on a desktop without them.
// No spaces in the list: jpackage reads its arguments from a file that splits at them.
tasks.withType<AbstractJPackageTask>().configureEach {
    if (targetFormat == TargetFormat.Deb) freeArgs.addAll("--linux-package-deps", "libxtst6,libxi6,libxrandr2")
}

// The same icon.png is the window icon on Linux and Windows (see Main.kt).
tasks.processResources { from("icon.png") }

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

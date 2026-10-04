plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
    id("com.android.kotlin.multiplatform.library")
}

kotlin {
    jvmToolchain(21)

    jvm("desktop")

    android {
        namespace = "widder.shared"
        compileSdk = 36
        minSdk = 26
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Desktop and Android both run on the JVM: files, time and the audio thread live here once.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        named("desktopMain") {
            dependsOn(jvmCommonMain)
            dependencies {
                // MP3, M4A/AAC, FLAC and OGG without a tool from the system: macOS has afconvert, Linux has nothing.
                implementation("com.googlecode.soundlibs:jlayer:1.0.1.4") {
                    // Its POM lists JUnit as a normal dependency, which would end up in the app.
                    exclude(group = "junit", module = "junit")
                }
                implementation("com.googlecode.soundlibs:jorbis:0.0.17.4")
                implementation("org.jflac:jflac-codec:1.5.2")
                implementation("com.tianscar.javasound:jaad:0.9.4")
            }
        }
        named("androidMain") { dependsOn(jvmCommonMain) }

        commonMain.dependencies {
            api("org.jetbrains.compose.runtime:runtime:1.11.1")
            api("org.jetbrains.compose.foundation:foundation:1.11.1")
            api("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

// NaviDroid für Linux: Compose for Desktop, Wiedergabe über VLC (vlcj), KDE/GNOME-Mediensteuerung über MPRIS.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

val appVersionName = providers.gradleProperty("appVersionName").getOrElse("1.0.0")

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("uk.co.caprica:vlcj:4.8.3")
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
    implementation("com.github.hypfvieh:dbus-java-core:5.1.0")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.1.0")
    implementation("org.slf4j:slf4j-nop:2.0.16")
}

compose.desktop {
    application {
        mainClass = "dev.navidroid.desktop.MainKt"
        jvmArgs += listOf("-Dawt.useSystemAAFontSettings=on", "-Dsun.java2d.uiScale.enabled=true")
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.AppImage)
            packageName = "navidroid"
            packageVersion = appVersionName.substringBefore("-")
            description = "NaviDroid – Navidrome-Client"
            vendor = "NaviDroid"
            modules("java.naming", "java.net.http", "jdk.unsupported")
            linux {
                iconFile.set(project.file("icon.png"))
                shortcut = true
                menuGroup = "AudioVideo;Audio;Player"
                appCategory = "Audio"
            }
        }
    }
}

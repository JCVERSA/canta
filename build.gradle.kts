// Root build script — mirrors SwiftSlate-ng's plugin layout exactly: the Android
// plugin and the Compose compiler plugin are declared here and applied in :app,
// and the Kotlin toolchain comes from AGP's built-in Kotlin support.
plugins {
    id("com.android.application") version "9.4.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

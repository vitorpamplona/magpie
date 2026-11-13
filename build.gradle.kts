plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.serialization) apply false
    alias(libs.plugins.samWithReceiver) apply false
    alias(libs.plugins.pluginPublish) apply false
    alias(libs.plugins.spotless)
}

allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    apply(plugin = "com.diffplug.spotless")
    spotless {
        kotlin {
            target("src/**/*.kt")
            ktlint("1.7.1")
            licenseHeaderFile(
                rootProject.file("spotless/copyright.kt"),
                "@file:|package|import|class|object|sealed|open|interface|abstract |fun |internal |public |private ",
            )
        }
        java {
            target("src/**/*.java")
            licenseHeaderFile(rootProject.file("spotless/copyright.kt"))
        }
    }
}

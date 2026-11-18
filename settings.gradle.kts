pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

// Quartz and its crypto-kmp and libschnorr256k1-kotlin dependencies are released on Nostr + Blossom:
// the published Magpie plugin serves them to this build, trusting the key releases@vitorpamplona.com
// names, and gradle/magpie.lock pins it. The plugin this repo builds embeds them in its jar, so its
// users never resolve them.
plugins {
    id("com.vitorpamplona.magpie") version "0.1.1"
}

magpie {
    group("com.vitorpamplona.quartz")
    group("com.vitorpamplona.crypto")
    group("com.vitorpamplona.schnorr256k1")
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "magpie"

include(":spec", ":bridge", ":core", ":gradle-plugin", ":testkit")

// To change Quartz alongside Magpie, point Gradle at a local checkout and it builds that checkout
// instead of the published artifact: ./gradlew -PquartzDir=../quartz test
providers.gradleProperty("quartzDir").orNull?.let { dir ->
    includeBuild(dir) {
        dependencySubstitution {
            substitute(module("com.vitorpamplona.quartz:quartz")).using(project(":quartz"))
        }
    }
}

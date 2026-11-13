// Hermetic test infrastructure: an in-memory Nostr relay (Quartz's NostrServer over an in-memory
// SQLite store) and an in-memory Blossom server (BUD-01/02/06), both on 127.0.0.1 through Ktor,
// plus Gradle TestKit fixtures. Test-only: nothing ships with it.
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.kotlin.stdlib)
    api(project(":spec"))
    api(libs.quartz)
    api(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.okhttp)
}

tasks.test {
    useJUnit()
}

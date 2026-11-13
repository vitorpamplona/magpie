// The resolver as a standalone HTTP service, for clients that can't run the settings plugin
// (Maven, sbt, a shared CI cache). Same Router, same caches, same bytes.
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.vitorpamplona.magpie.gateway.GatewayKt")
    applicationName = "magpie-gateway"
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":core"))
    implementation(project(":bridge"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.logback.classic)

    testImplementation(project(":testkit"))
    testImplementation(libs.kotlin.test.junit)
}

// The gateway never signs; it still verifies with Quartz's pure-Kotlin secp256k1 and needs no JNI.
// Tests sign fixtures with the JNI, which targets Java 21 (as does the gateway image).
tasks.test {
    useJUnit()
}

listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) } }
}

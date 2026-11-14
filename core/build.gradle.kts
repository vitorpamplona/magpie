// The JVM engine: resolution (relays, Blossom, caches, Maven metadata, the HTTP router) and
// publishing (scan, upload, sign, publish). It runs inside an isolated class loader in the Gradle
// daemon (resolution) or in a worker process (publishing), and inside the gateway.
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        // Gradle 8 daemons may run on Java 17.
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
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
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    // Loaded from the plugin's class loader at runtime; never packaged into the engine.
    compileOnly(project(":bridge"))

    testImplementation(project(":bridge"))
    testImplementation(project(":testkit"))
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.test {
    useJUnit()
    maxHeapSize = "1g"
}

// Signing (tests, the publish worker) uses Quartz's secp256k1 JNI, which needs Java 21.
// Resolution never loads it, so the engine itself stays Java 17 bytecode.
listOf("testCompileClasspath", "testRuntimeClasspath").forEach { name ->
    configurations.named(name) {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) }
    }
}

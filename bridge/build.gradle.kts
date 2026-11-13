// The contract between the Gradle plugin (on Gradle's classpath) and the Magpie engine (Quartz,
// OkHttp, coroutines and its own Kotlin stdlib, in an isolated class loader). Plain Java with
// JDK types only, so both sides load the same classes from the plugin's loader.
plugins {
    `java-library`
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    // Gradle 8 daemons may run on Java 17.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

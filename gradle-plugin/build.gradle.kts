import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import java.security.MessageDigest

// The two Gradle plugins. They hold only Gradle-facing code and the bridge interfaces; the
// engine (core, Quartz, OkHttp, coroutines and its own Kotlin stdlib) ships inside the plugin jar
// as nested jars under META-INF/magpie/engine/ and runs in an isolated class loader, so it can
// neither clash with other plugins' dependencies nor with the Kotlin stdlib Gradle embeds.
plugins {
    `java-gradle-plugin`
    `maven-publish`
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.samWithReceiver)
    alias(libs.plugins.pluginPublish)
}

// Gradle's Action<T> lambdas get T as `this`, as in build scripts.
samWithReceiver {
    annotation("org.gradle.api.HasImplicitReceiver")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Readable by the Kotlin DSL of Gradle 8.x (Kotlin 2.0) and running on its stdlib.
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
        languageVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// The bridge interfaces, as class directories, merged into this plugin's jar.
val embedded: Configuration = configurations.create("embedded") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.CLASSES))
    }
}

// Everything the engine needs at runtime, minus what must not load in the daemon.
val engineRuntime: Configuration = configurations.create("engineRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
        attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling.EXTERNAL))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 17)
    }
    // The secp256k1 JNI: native code, loadable once per JVM. Resolution verifies with Quartz's
    // pure-Kotlin secp256k1 instead; signing runs in a worker process with engineSigning.
    exclude(group = "fr.acinq.secp256k1", module = "secp256k1-kmp-jni-jvm")
    // Quartz's event store; the engine never opens a database.
    exclude(group = "androidx.sqlite")
}

// Added to the worker process that signs (publishing only).
val engineSigning: Configuration = configurations.create("engineSigning") {
    isCanBeConsumed = false
    isCanBeResolved = true
    // Its per-OS native libraries come in transitively; anything the runtime already has is skipped.
    exclude(group = "org.jetbrains.kotlin")
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
    compileOnly(project(":bridge"))
    embedded(project(":bridge"))
    engineRuntime(project(":core"))
    engineSigning("fr.acinq.secp256k1:secp256k1-kmp-jni-jvm:0.24.0")
}

val packageEngine = tasks.register<PackageEngine>("packageEngine") {
    runtimeJars.from(engineRuntime)
    signingJars.from(engineSigning)
    outputDir.set(layout.buildDirectory.dir("generated/magpie-engine"))
}

sourceSets.main {
    resources.srcDir(packageEngine)
}

tasks.jar {
    from(embedded)
}

gradlePlugin {
    website.set("https://github.com/vitorpamplona/magpie")
    vcsUrl.set("https://github.com/vitorpamplona/magpie")
    plugins {
        create("magpieSettings") {
            id = "com.vitorpamplona.magpie"
            implementationClass = "com.vitorpamplona.magpie.gradle.MagpieSettingsPlugin"
            displayName = "Magpie: resolve Maven artifacts from Nostr + Blossom"
            description = "Resolves dependencies published as Nostr release events and Blossom blobs, pinned per group to a publisher key."
            tags.set(listOf("nostr", "blossom", "maven", "dependencies", "repository"))
        }
        create("magpiePublish") {
            id = "com.vitorpamplona.magpie.publish"
            implementationClass = "com.vitorpamplona.magpie.gradle.MagpiePublishPlugin"
            displayName = "Magpie: publish Maven artifacts to Nostr + Blossom"
            description = "Publishes maven-publish output as Blossom blobs and a signed Nostr release event."
            tags.set(listOf("nostr", "blossom", "maven", "publishing"))
        }
    }
}

// Published as com.vitorpamplona.magpie:magpie-gradle-plugin (the plugin markers point here).
publishing.publications.withType<MavenPublication>().configureEach {
    if (name == "pluginMaven") artifactId = "magpie-gradle-plugin"
}

// Functional tests: real Gradle builds (TestKit) against the in-memory relay and Blossom server.
val functionalTest: SourceSet = sourceSets.create("functionalTest")
configurations[functionalTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
listOf(functionalTest.compileClasspathConfigurationName, functionalTest.runtimeClasspathConfigurationName).forEach { name ->
    configurations.named(name) { attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21) } }
}

dependencies {
    "functionalTestImplementation"(project(":testkit"))
    "functionalTestImplementation"(libs.kotlin.stdlib)
    "functionalTestImplementation"(libs.kotlin.test.junit)
    "functionalTestImplementation"(gradleTestKit())
}

// The plugin, published to a directory the test builds resolve it from, exactly as users would.
val testRepo = layout.buildDirectory.dir("functional-test-repo")
publishing {
    repositories {
        maven {
            name = "functionalTest"
            url = uri(testRepo)
        }
    }
}

val functionalTestTask =
    tasks.register<Test>("functionalTest") {
        description = "Runs Gradle TestKit builds that publish to and resolve from Nostr + Blossom."
        group = "verification"
        testClassesDirs = functionalTest.output.classesDirs
        classpath = functionalTest.runtimeClasspath
        useJUnit()
        dependsOn("publishAllPublicationsToFunctionalTestRepository")
        systemProperty("magpie.testRepo", testRepo.get().asFile.absolutePath)
        systemProperty("magpie.version", project.version.toString())
        systemProperty("magpie.kmpTargets", providers.gradleProperty("magpie.kmpTargets").getOrElse("jvm"))
        systemProperty("magpie.gradleVersion", providers.gradleProperty("magpie.gradleVersion").getOrElse(""))
        providers.environmentVariable("ANDROID_HOME").orNull?.let { environment("ANDROID_HOME", it) }
        maxParallelForks = 1
        maxHeapSize = "1g"
        // Every scenario runs fresh Gradle builds; don't cache the result.
        outputs.upToDateWhen { false }
    }

tasks.check { dependsOn(functionalTestTask) }

/**
 * Copies the engine's jars under META-INF/magpie/engine/ with an index (`<kind> <name> <sha256>`),
 * which the plugin extracts to ~/.gradle/magpie/engine/<hash>/ and loads in its own class loader.
 */
abstract class PackageEngine : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val runtimeJars: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val signingJars: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun pack() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        val dir = File(root, "META-INF/magpie/engine")
        if (!dir.mkdirs()) throw GradleException("Could not create $dir")
        val names = mutableSetOf<String>()
        val index = StringBuilder()
        fun add(
            kind: String,
            jar: File,
        ) {
            var name = jar.name
            var n = 1
            while (!names.add(name)) name = "${n++}-${jar.name}"
            jar.copyTo(File(dir, name))
            val sha = MessageDigest.getInstance("SHA-256").digest(jar.readBytes()).joinToString("") { b -> "%02x".format(b) }
            index.append("$kind $name $sha\n")
        }
        runtimeJars.files.filter { it.name.endsWith(".jar") }.sortedBy { it.name }.forEach { add("runtime", it) }
        val runtimeNames = runtimeJars.files.map { it.name }.toSet()
        signingJars.files.filter { it.name.endsWith(".jar") && it.name !in runtimeNames }.sortedBy { it.name }.forEach { add("signing", it) }
        File(dir, "index.txt").writeText(index.toString())
    }
}

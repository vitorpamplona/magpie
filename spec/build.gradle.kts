plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlin.stdlib)
            api(libs.quartz)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

tasks.register("test") {
    group = "verification"
    description = "Runs the JVM tests of this multiplatform module."
    dependsOn("jvmTest")
}

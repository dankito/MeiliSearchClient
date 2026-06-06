import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    // Meili SDK's OkHttp dependency requires at least Kotlin 2.2
    kotlin("jvm") version "2.2.21"
}

group = "net.dankito.meilisearch"
version = "1.0.0-SNAPSHOT"


kotlin {
    jvmToolchain(17) // Meili SDK requires JDK 17 or newer

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        javaParameters = true

        // avoid "variable has been optimised out" in debugging mode
        val isDebuggerAttached = System.getProperty("debug")?.toIntOrNull() != null
        if (isDebuggerAttached) {
            freeCompilerArgs.add("-Xdebug")
        }
    }
}


repositories {
    mavenCentral()
}


val meiliVersion: String = "0.20.1"
// do not use a newer version, it would require Kotlin > 2.0
val jacksonVersion: String = "2.20.2"
val coroutinesVersion: String = "1.9.0"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")

    implementation("com.meilisearch.sdk:meilisearch-java:$meiliVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")


    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
}


tasks.test {
    useJUnitPlatform()
}
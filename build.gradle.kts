import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    kotlin("jvm") version "2.0.21"
}

group = "net.dankito.meilisearch"
version = "1.0.0-SNAPSHOT"


kotlin {
    jvmToolchain(11)

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

dependencies {
    testImplementation(kotlin("test"))
}


tasks.test {
    useJUnitPlatform()
}
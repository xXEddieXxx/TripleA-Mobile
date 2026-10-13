import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "The app's connection to the game server: REST calls and the game WebSocket"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":online"))
    api(libs.okhttp)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testImplementation(libs.mockwebserver3)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    // the connection tests install the same minimap as the engine tests
    test { resources.srcDir(project(":engine").file("src/test/resources")) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

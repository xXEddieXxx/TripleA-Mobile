import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

description = "Human seats of a TripleA game played over the JSON protocol: host side and device side"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    api(project(":engine"))
    api(project(":protocol"))

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    // the game tests install the same minimap as the engine tests
    test { resources.srcDir(project(":engine").file("src/test/resources")) }
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

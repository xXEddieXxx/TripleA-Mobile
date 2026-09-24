plugins {
    `java-library`
}

description = "TripleA game engine, stripped of Swing/AWT and networking so it runs on Android"

// No toolchain requirement: the engine compiles with whatever JDK runs Gradle (17 or newer,
// e.g. the JDK bundled with Android Studio). `--release 17` still guarantees that only
// Java 17 language features and library APIs are used.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(libs.guava)
    api(libs.slf4j.api)
    api(libs.jsr305)
    api(libs.jetbrains.annotations)
    implementation(libs.gson)
    implementation(libs.snakeyaml.engine)
    implementation(libs.commons.math3)
    implementation(libs.commons.io)
    implementation(libs.commons.lang3)
    implementation(libs.commons.text)
    implementation(libs.jakarta.xml.bind.api)

    compileOnly(libs.lombok)
    annotationProcessor(libs.lombok)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.slf4j.simple)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testCompileOnly(libs.lombok)
    testAnnotationProcessor(libs.lombok)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(17)
    options.compilerArgs.addAll(listOf("-parameters", "-Xmaxerrs", "2000"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

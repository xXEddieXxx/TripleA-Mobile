plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.sonarqube)
}

// SonarQube Cloud analysis of both modules (`./gradlew sonar`, see docs/DEVELOPMENT.md).
// The token comes from the SONAR_TOKEN environment variable, never from the repository.
sonar {
    properties {
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.organization", "xxeddiexxx")
        property("sonar.projectKey", "xXEddieXxx_TripleA-Mobile")
        property("sonar.projectName", "TripleA-Mobile")
        property("sonar.sourceEncoding", "UTF-8")
        // bundled maps and engine images are data, not code
        property("sonar.exclusions", "**/assets/**")
        // the caller compiles first (the Stop hook and the `sonar` skill do); the sonar task itself
        // does not trigger compilation
        property("sonar.gradle.skipCompile", "true")
    }
}

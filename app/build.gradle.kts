plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.triplea.mobile.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.triplea.mobile"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release builds are signed with a real key when release.keystore / RELEASE_* properties are
    // given (see README); never with the debug key. Without a key the APK is left unsigned.
    val keystoreFile = rootProject.file(providers.gradleProperty("RELEASE_STORE_FILE").orNull ?: "release.keystore")
    if (keystoreFile.exists()) {
        signingConfigs.create("release") {
            storeFile = keystoreFile
            storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull ?: System.getenv("RELEASE_STORE_PASSWORD")
            keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull ?: System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull ?: System.getenv("RELEASE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 shrinks the AndroidX / Kotlin side; the engine is kept whole because it relies on
            // reflection and Java serialization (see proguard-rules.pro)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            pickFirsts += setOf(
                "META-INF/services/javax.xml.stream.XMLInputFactory",
                "META-INF/services/javax.xml.stream.XMLOutputFactory",
                "META-INF/services/javax.xml.stream.XMLEventFactory",
            )
            excludes += setOf(
                "META-INF/*.md",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/versions/9/module-info.class",
                "module-info.class",
                // Woodstox validation schema factories: the schema classes are not shipped
                "META-INF/services/org.codehaus.stax2.validation.*",
            )
        }
    }
}

dependencies {
    implementation(project(":engine"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)

    // StAX XML parser: the JDK ships one, Android does not.
    implementation("javax.xml.stream:stax-api:1.0-2")
    implementation(libs.stax2.api)
    implementation(libs.woodstox.core)

    implementation(libs.slf4j.simple)

    // parses the map listing (triplea_maps.yaml) in the map browser
    implementation(libs.snakeyaml.engine)

    debugImplementation(libs.compose.ui.tooling)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version: GitHub sets these when you publish a release (tag v1.2.3). Local builds use 1.0 / 1.
val appVersionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
val appVersionCode = ((project.findProperty("appVersionCode") as String?) ?: "1").toInt()

// Signing: the keystore file and passwords come from environment variables (GitHub secrets).
// They are NEVER written in this file. Without them the release build is simply left unsigned.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val hasKeystore = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.softalarm.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.softalarm.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        create("release") {
            if (hasKeystore) {
                storeFile = file(keystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (hasKeystore) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

// Firebase is switched on only when app/google-services.json exists,
// so the app still builds (without cloud features) before you add the file.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

dependencies {
    // Base libraries (needed later for Stripe's payment sheet and Firebase)
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Firebase (versions are controlled by the BoM)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")

    // Unit tests (run with: gradle testDebugUnitTest)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

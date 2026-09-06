plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "nl.yannick.b04cbridge"
    compileSdk = 35

    // Het buildnummer van GitHub Actions komt in de versienaam terecht, zodat de app in zijn
    // eigen log kan zeggen welke build er daadwerkelijk op de telefoon staat. Daarvoor stond
    // er een handmatig bijgewerkte tekst in BleManager, en die klopte al lang niet meer.
    val ciBuild: String? = System.getenv("GITHUB_RUN_NUMBER")

    defaultConfig {
        applicationId = "nl.yannick.b04cbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = ciBuild?.toIntOrNull() ?: 1
        versionName = if (ciBuild != null) "build $ciBuild" else "lokale build"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}

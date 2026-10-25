plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.moinstudioz.advanceplay"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.moinstudioz.advanceplay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += setOf("META-INF/*.md", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // YouTube extractor. v0.24.x is broken by YouTube ("page needs to be reloaded"); 0.26.5 is the fixed one.
    // If downloads stop later, bump this tag to the newest release.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-datasource:1.5.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
}

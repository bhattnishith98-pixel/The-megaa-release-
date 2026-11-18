plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.tom.rv2ide.studio"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.core.ktx)

    // Real Android Code Studio CodeEditor AAR (Sora Editor by Rosemoe)
    api(platform(libs.sora.editor.bom))
    api(libs.sora.editor)

    testImplementation(libs.junit)
}

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Where feedback reports go: set feedback.url and feedback.key in local.properties (not committed). Empty = no server built in.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val feedbackUrl: String = localProps.getProperty("feedback.url", "")
val feedbackKey: String = localProps.getProperty("feedback.key", "")

android {
    namespace = "dk.cbavf.studio"
    compileSdk = 35

    defaultConfig {
        applicationId = "dk.cbavf.studio"
        minSdk = 33          // S9+ on LineageOS 13 = API 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One edition for now ("studio"); the flavor dimension stays so the task names do not change.
    flavorDimensions += "edition"
    productFlavors {
        create("studio") {
            dimension = "edition"
            isDefault = true
            buildConfigField("boolean", "FEEDBACK_ENABLED", "true")
            buildConfigField("String", "FEEDBACK_URL", "\"$feedbackUrl\"")
            buildConfigField("String", "FEEDBACK_KEY", "\"$feedbackKey\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.dyn4j)   // 2D physics for the Physics room
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "app.msplayer.offline"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.msplayer.offline"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.4.0"
        ndk { abiFilters += (project.findProperty("testAbi")?.toString() ?: "arm64-v8a") }
        testInstrumentationRunner = "app.msplayer.offline.SafeTestRunner"
        manifestPlaceholders["appLabel"] = "MS PLAYER"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs.useLegacyPackaging = false; resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    signingConfigs {
        if(System.getenv("MSPLAYER_KEYSTORE") != null) create("localTest") {
            storeFile=file(System.getenv("MSPLAYER_KEYSTORE"))
            storePassword=System.getenv("MSPLAYER_STORE_PASSWORD")
            keyAlias="msplayer"
            keyPassword=System.getenv("MSPLAYER_STORE_PASSWORD")
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; manifestPlaceholders["appLabel"] = "MS PLAYER DEBUG" }
        release { isMinifyEnabled = false; if(signingConfigs.findByName("localTest") != null) signingConfig=signingConfigs.getByName("localTest") } }
    lint { abortOnError = true }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.fragment:fragment-ktx:1.8.8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.room:room-runtime:2.7.1")
    annotationProcessor("androidx.room:room-compiler:2.7.1")
    if(project.findProperty("localVlcAar") != null) {
        implementation(files(project.property("localVlcAar")))
        implementation("androidx.legacy:legacy-support-v4:1.0.0")
    } else implementation("org.videolan.android:libvlc-all:3.7.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

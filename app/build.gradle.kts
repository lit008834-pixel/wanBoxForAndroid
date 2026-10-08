// @author 雾晚
@file:Suppress("UnstableApiUsage")

plugins {
    id("com.android.application")
    id("kotlin-android")
    id("com.google.devtools.ksp")
    id("kotlin-parcelize")
}

setupApp()

val generatedLicenseAssets = layout.buildDirectory.dir("generated/assets/rootLicense")
val generateRootLicenseAsset by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("LICENSE"))
    into(generatedLicenseAssets)
}

android {
    defaultConfig {
        resourceConfigurations += listOf("en-rUS", "zh-rCN", "zh-rHK", "zh-rTW")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionName = requireMetadata().getProperty("VERSION_NAME")
        versionCode = requireMetadata().getProperty("VERSION_CODE").toInt() * 5
    }
    // Internal x86_64 verification is never a published ABI.
    if (providers.gradleProperty("wanboxReleaseTests").isPresent) {
        testBuildType = "release"
        // @author 雾晚: external test APKs need stable shared APIs; shipping builds omit these rules.
        buildTypes.named("release") { proguardFiles("proguard-release-tests.pro") }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
    ksp {
        arg("room.incremental", "true")
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    bundle {
        language {
            enableSplit = false
        }
    }
    buildFeatures {
        buildConfig = true
        viewBinding = true
        aidl = true
    }
    namespace = "io.nekohasekai.sagernet"
    packaging {
        jniLibs {
            // @author 雾晚: compressed APK and extracted Root PIE in nativeLibraryDir.
            useLegacyPackaging = true
            // @author 雾晚: the standalone core belongs to the module, not the manager APK.
            // Keep executableSo outputs for module packaging and all JNI/plugin libraries.
            excludes += "**/librootbox.so"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
    sourceSets.named("main") {
        assets.srcDir(generatedLicenseAssets)
    }
    sourceSets.named("androidTest") {
        assets.srcDir("schemas")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(generateRootLicenseAsset)
}

dependencies {

    // @author 雾晚: the native build produces this single, explicit dependency.
    implementation(files("libs/libcore.aar"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.5.6")
    implementation("androidx.browser:browser:1.5.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")
    implementation("androidx.preference:preference-ktx:1.2.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.work:work-runtime-ktx:2.8.1")
    implementation("androidx.work:work-multiprocess:2.8.1")

    implementation("com.google.android.material:material:1.8.0")
    implementation("com.google.code.gson:gson:2.9.0")

    implementation("com.github.jenly1314:zxing-lite:2.1.1")
    implementation("com.blacksquircle.ui:editorkit:2.6.0")
    implementation("com.blacksquircle.ui:language-base:2.6.0")
    implementation("com.blacksquircle.ui:language-json:2.6.0")

    implementation("com.squareup.okhttp3:okhttp:5.0.0-alpha.3")
    implementation("org.yaml:snakeyaml:1.30")
    implementation("com.github.daniel-stoneuk:material-about-library:3.2.0-rc01")
    implementation("com.jakewharton:process-phoenix:2.1.2")
    implementation("com.esotericsoftware:kryo:5.2.1")
    implementation("com.google.guava:guava:31.0.1-android")
    implementation("org.ini4j:ini4j:0.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.room:room-testing:2.6.1")

    implementation("com.simplecityapps:recyclerview-fastscroll:2.0.1") {
        exclude(group = "androidx.recyclerview")
        exclude(group = "androidx.appcompat")
    }

    implementation("androidx.room:room-runtime:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    implementation("com.github.MatrixDev.Roomigrant:RoomigrantLib:0.3.4")
    ksp("com.github.MatrixDev.Roomigrant:RoomigrantCompiler:0.3.4")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.3")
}

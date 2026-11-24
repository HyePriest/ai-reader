plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }
    namespace = "com.jknl.englishreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.jknl.englishreader"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val signingPath = providers.environmentVariable("AI_READER_KEYSTORE").orNull
        if (!signingPath.isNullOrBlank()) {
            create("publicRelease") {
                storeFile = file(signingPath)
                storePassword = providers.environmentVariable("AI_READER_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("AI_READER_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("AI_READER_KEY_PASSWORD").get()
            }
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("publicRelease")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    implementation("org.jsoup:jsoup:1.23.2")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("org.json:json:20240303")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    // Renders screens on the JVM so layout changes can be checked as images.
    testImplementation("org.robolectric:robolectric:4.18.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// Keep the in-app instructions and the editable preparation document identical.
abstract class PrepareImportGuide : DefaultTask() {
    @get:InputFile abstract val sourceDocument: RegularFileProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
    @TaskAction fun prepare() {
        val output = outputDirectory.get().asFile
        output.mkdirs()
        sourceDocument.get().asFile.copyTo(output.resolve("ai-book-preparation.md"), overwrite = true)
    }
}
val prepareImportGuide = tasks.register<PrepareImportGuide>("prepareImportGuide") {
    sourceDocument.set(rootProject.layout.projectDirectory.file("docs/ai-book-preparation.md"))
    outputDirectory.set(layout.buildDirectory.dir("generated/import-guide-assets"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareImportGuide, PrepareImportGuide::outputDirectory)
}


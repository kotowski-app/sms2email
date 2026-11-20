import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
    id("dagger.hilt.android.plugin")
}

android {
    namespace = "app.kotowski.sms2email"
    compileSdk = 37

    // Release signing (reproducible F-Droid builds):
    // - the keystore and its passwords are gitignored and must never reach the public repo
    // - when the keystore is absent (F-Droid's build server) the release builds unsigned
    //   and F-Droid signs it with the developer key they hold for reproducible builds
    val releaseKeystore = rootProject.file("release.keystore")
    val releaseKeystoreProps = rootProject.file("release-keystore.properties")
    val hasReleaseKeystore = releaseKeystore.exists() && releaseKeystoreProps.exists()

    bundle {
        language {
            enableSplit = false
        }
    }

    androidResources {
        localeFilters += listOf("en", "ru", "zh")
    }

    defaultConfig {
        applicationId = "app.kotowski.sms2email"
        minSdk = 26
        targetSdk = 37
        // Version encoding: Each version component is padded to 3 digits.
        // Formula: (Major * 1,000,000) + (Minor * 1,000) + Patch
        // Examples:
        //   - 0.7.22  -> (0 * 1,000,000) + (7 * 1,000) + 22 = 7022
        //   - 0.9     -> (0 * 1,000,000) + (9 * 1,000)      = 9000
        //   - 2.0.13  -> (2 * 1,000,000) + (0 * 1,000) + 13 = 2000013
        versionCode = 1000000
        versionName = "1.0.0"
        flavorDimensions += "store"
        productFlavors {
            create("vanilla") {
                dimension = "store"
                applicationIdSuffix = ".vanilla"
                buildConfigField("String", "FLAVOR_LABEL", "\"FOSS\"")
            }
        }

        signingConfigs {
            create("release") {
                if (hasReleaseKeystore) {
                    val props = Properties()
                    releaseKeystoreProps.inputStream().use(props::load)
                    storeFile = rootProject.file(props.getProperty("storeFile").orEmpty())
                    storePassword = props.getProperty("storePassword").orEmpty()
                    keyAlias = props.getProperty("keyAlias").orEmpty()
                    keyPassword = props.getProperty("keyPassword").orEmpty()
                }
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        // Same key as release so debug and release installs can be swapped on a device
        // without uninstalling (Android matches update signatures; data survives the switch).
        debug {
            isMinifyEnabled = false
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/LICENSE.md"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // The NATIVE-graphics Robolectric sandboxes (Skia + bitmaps) grow through a
                // full-suite run and OOM a 2g test JVM near the end; 4g leaves headroom.
                it.maxHeapSize = "4g"
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("${rootProject.name.lowercase()}-v${android.defaultConfig.versionName}.apk")
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.13.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    implementation("androidx.hilt:hilt-lifecycle-viewmodel-compose:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")
    testImplementation("com.google.dagger:hilt-android-testing:2.60.1")

    // Room
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    implementation("androidx.hilt:hilt-work:1.4.0")

    // JavaMail
    implementation("com.sun.mail:android-mail:1.6.8")
    implementation("com.sun.mail:android-activation:1.6.8")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-guava:1.11.0")

    // Phone number normalization (E.164) for contact matching
    implementation("com.googlecode.libphonenumber:libphonenumber:9.0.43")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.4.10")
    testImplementation("org.mockito:mockito-core:5.24.0")
    testImplementation("org.mockito:mockito-inline:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:6.4.0")
    // The real org.json (the android.jar stubs throw "not mocked" in plain JVM tests that
    // serialize the message filter's JSON).
    testImplementation("org.json:json:20260814")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.work:work-testing:2.12.0")
    testImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2026.09.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

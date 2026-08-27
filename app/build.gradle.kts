plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.parentalcontrol.childapp"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.parentalcontrol.childapp"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true
    }

    signingConfigs {
        create("release") {
            storeFile = file("C:/Projects/ParentalControl/ChildApp/keystore.jks")
            storePassword = "YOUR_STORE_PASSWORD"
            keyAlias = "YOUR_KEY_ALIAS"
            keyPassword = "YOUR_KEY_PASSWORD"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false   // change to true when ready for Play Store
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {

    // ---------------- CORE ----------------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")

    //---------lottie animation---------
    implementation("com.airbnb.android:lottie:6.4.0")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // ViewPager2 (Onboarding)
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // ---------------- FIREBASE ----------------
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-storage-ktx:20.3.0")

    // ---------------- LOCATION + MAPS ----------------
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("com.google.android.gms:play-services-maps:19.0.0")

    // ---------------- WORK MANAGER ----------------
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // ---------------- MULTIDEX ----------------
    implementation("androidx.multidex:multidex:2.0.1")

    // ---------------- QR CODE ----------------
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.1")

    // ---------------- TFLITE (AI CONTENT DETECTION) ----------------
    implementation("org.tensorflow:tensorflow-lite:2.12.0")
    implementation("org.tensorflow:tensorflow-lite-support:0.4.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.12.0")

    // Optional ML Kit helpers
    implementation("com.google.mlkit:vision-common:16.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    //implementation(libs.androidx.ui.text)
    //implementation(libs.androidx.compose.ui.text)

    // ---------------- TESTING ----------------
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
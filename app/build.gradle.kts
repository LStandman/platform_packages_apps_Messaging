plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.android.messaging"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.android.messaging"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    afterEvaluate {
        tasks.withType(JavaCompile::class) {
            options.compilerArgs.add("-Xlint:unchecked")
            options.compilerArgs.add("-Xlint:deprecation")
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.libphonenumber)
    implementation(libs.guava)
    implementation(libs.exifinterface)
    testImplementation(libs.mockito.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    testImplementation(libs.espresso.core)
}
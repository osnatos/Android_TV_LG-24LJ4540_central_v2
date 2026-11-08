import java.text.SimpleDateFormat
import java.util.Date
plugins {
    alias(libs.plugins.android.application)
}
// Извлекаем текущее системное время в миллисекундах прямо во время сборки
val buildTime = System.currentTimeMillis()

fun generateVersionName(): String {
    // Превращаем миллисекунды в читаемую дату (например: 1.5.260709)
    return "1.5." + SimpleDateFormat("yyMMdd").format(Date(buildTime))
}
android {
    namespace = "com.example.android_lg_tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.android_lg_tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = generateVersionName()

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
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
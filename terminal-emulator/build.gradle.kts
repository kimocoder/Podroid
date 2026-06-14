plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.termux.emulator"
    compileSdk = 36

    defaultConfig {
        minSdk = 26

        externalNativeBuild {
            ndkBuild {
                cFlags += listOf("-std=c11", "-Wall", "-Wextra", "-Werror", "-Os", "-fno-stack-protector")
                // Linker flags (16 KB page alignment + gc-sections) live in Android.mk via LOCAL_LDFLAGS.
            }
        }

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.9.0")
}

import javax.inject.Inject
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.elio.recipecraft"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.elio.recipecraft"
        minSdk = 34
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

/**
 * Android matches `values-zh` only for Simplified-script locales, so zh-TW and zh-HK would fall
 * back to English. Copying the Chinese strings to `values-b+zh+Hant` keeps one source of truth.
 */
abstract class TraditionalChineseResTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val chineseValues: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(chineseValues)
            into(outputDir.dir("values-b+zh+Hant"))
        }
    }
}

androidComponents {
    onVariants { variant ->
        val task = tasks.register<TraditionalChineseResTask>("generate${variant.name.replaceFirstChar(Char::uppercase)}TraditionalChineseRes") {
            chineseValues.set(layout.projectDirectory.dir("src/main/res/values-zh"))
        }
        variant.sources.res?.addGeneratedSourceDirectory(task, TraditionalChineseResTask::outputDir)
    }
}

kover {
    reports {
        filters {
            includes {
                packages("com.elio.recipecraft.domain", "com.elio.recipecraft.data")
            }
        }
        verify {
            rule("domain and data line coverage") {
                // No domain/data code exists yet, so there is nothing to measure. T4 sets this to false.
                disabled = true
                minBound(80, CoverageUnit.LINE)
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
}

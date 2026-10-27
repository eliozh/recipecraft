import javax.inject.Inject
import kotlinx.kover.gradle.plugin.dsl.CoverageUnit

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
    alias(libs.plugins.room)
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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    bundle {
        // A shared recipe uses its own language's strings, whatever the device language, so every
        // install needs both languages.
        language {
            enableSplit = false
        }
    }

    sourceSets {
        // The Compose tests render the same synthetic LLM replies the JVM tests parse.
        getByName("androidTest").resources.directories.add("src/test/resources")
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

room {
    // tools/nutrition/build_usda.py copies the table DDL and identity hash from these files.
    schemaDirectory("$projectDir/schemas")
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
            excludes {
                // Thin wrappers over the Android Keystore, LocaleManager and AssetManager that a JVM test cannot reach;
                // TinkSecretStoreInstrumentedTest and the manual checks cover them.
                classes(
                    "com.elio.recipecraft.data.settings.KeystoreAeadProvider*",
                    "com.elio.recipecraft.data.settings.LocaleManagerAppLanguageSettings*",
                    "com.elio.recipecraft.data.llm.AndroidAssetReader*",
                    // Room's database class and its generated code; UsdaDatabaseInstrumentedTest opens the real asset.
                    "com.elio.recipecraft.data.nutrition.UsdaDatabase*",
                    "com.elio.recipecraft.data.nutrition.UsdaFoodDao_Impl*",
                    // AppDatabase and its generated code; NutritionTableDaoTest runs them on a device.
                    "com.elio.recipecraft.data.AppDatabase*",
                    "com.elio.recipecraft.data.nutrition.NutritionTableDao_Impl*",
                    // RecipeDaoTest and AppDatabaseMigrationTest run them on a device.
                    "com.elio.recipecraft.data.recipe.RecipeDao_Impl*",
                    // ChatDaoTest and AppDatabaseMigrationTest run them on a device.
                    "com.elio.recipecraft.data.chat.ChatDao_Impl*",
                    // A thin wrapper over the ContentResolver; the manual SAF import covers it.
                    "com.elio.recipecraft.data.nutrition.AndroidCsvDocuments*",
                    // The same for export files; ExportImportTest and the manual SAF export cover it.
                    "com.elio.recipecraft.data.recipe.AndroidRecipeDocuments*",
                )
            }
        }
        verify {
            rule("domain and data line coverage") {
                disabled = false
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

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.tink.android)

    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.turbine)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // Compose ui-test pulls Espresso 3.5, which calls an InputManager method removed in API 37.
    androidTestImplementation(libs.androidx.test.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

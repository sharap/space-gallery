import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Разделение релиза по архитектурам включается только для релизных задач: в debug-сборке
 * лежат ещё и модели, и плодить из неё несколько APK по 750 МБ незачем. AGP не разрешает
 * держать `splits` и `abiFilters` одновременно, поэтому признак один на оба места.
 */
val abiSplit = gradle.startParameter.taskNames.any { it.contains("elease") }

android {
    namespace = "ai.recommend.spacegallery"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ai.recommend.spacegallery"
        minSdk = 28
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Откуда релиз скачивает модели (файлы под путями из assets/models_manifest.json).
        // Задаётся при сборке: -PmodelsBaseUrl=https://…/ или в gradle.properties. Пусто —
        // загрузка недоступна (debug-сборка берёт модели из своих assets).
        val modelsBaseUrl = (project.findProperty("modelsBaseUrl") as String?).orEmpty()
        buildConfigField("String", "MODELS_BASE_URL", "\"$modelsBaseUrl\"")
        if (modelsBaseUrl.isEmpty() && gradle.startParameter.taskNames.any { it.contains("elease") }) {
            // Без адреса релиз соберётся, но AI-функции в нём будут недоступны навсегда.
            logger.warn("ВНИМАНИЕ: -PmodelsBaseUrl не задан — релиз не сможет скачать модели (см. models/README.md)")
        }

    }

    signingConfigs {
        // Ключ подписи описан в keystore.properties рядом с проектом — файл не в репозитории
        // (см. .gitignore) и не попадает ни в историю, ни в APK:
        //   storeFile=/путь/к/keystore.jks
        //   storePassword=…
        //   keyAlias=…
        //   keyPassword=…
        // Пока файла нет, релиз подписывается отладочным ключом — иначе APK не установить.
        val keystoreProperties = rootProject.file("keystore.properties")
        if (keystoreProperties.exists()) {
            val key = Properties().apply { keystoreProperties.inputStream().use { stream -> load(stream) } }
            create("release") {
                storeFile = file(key.getProperty("storeFile"))
                storePassword = key.getProperty("storePassword")
                keyAlias = key.getProperty("keyAlias")
                keyPassword = key.getProperty("keyPassword")
            }
        }
    }

    /**
     * Релиз собирается отдельным APK на каждую архитектуру: `libonnxruntime.so` весит
     * 33–39 МБ на каждую, и класть в один файл все — значит раздать телефону сотню
     * мегабайт, которой он никогда не воспользуется.
     */
    splits {
        abi {
            isEnable = abiSplit
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            // Общий APK со всеми архитектурами: -PuniversalApk=true (для раздачи «одним файлом»).
            isUniversalApk = (project.findProperty("universalApk") as String?) == "true"
        }
    }

    buildTypes {
        debug {
            // Отладочная сборка одна на всё: телефон и эмулятор.
            if (!abiSplit) ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            // Пока идёт разработка — подписываем отладочным ключом: иначе APK не поставить
            // на телефон. Как появится настоящий keystore, сборка сама переключится на него.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.lifecycle("Релиз подписывается отладочным ключом (нет keystore.properties)")
            }
            // Отладочный релиз: ./gradlew assembleRelease -PdebuggableRelease=true
            // Нужен, чтобы заглянуть в базу на устройстве (run-as работает только с
            // отлаживаемым приложением), не переустанавливая его и не теряя данные:
            // подпись и applicationId те же, что у обычного релиза. В публикацию не идёт.
            if ((project.findProperty("debuggableRelease") as String?) == "true") {
                isDebuggable = true
                logger.warn("ВНИМАНИЕ: релиз собирается ОТЛАЖИВАЕМЫМ (-PdebuggableRelease)")
            }
            // R8: выбрасывает неиспользуемый код и ресурсы. Всё, к чему обращаются не из
            // Java-кода (ONNX через JNI, воркеры по имени класса, Room, сериализация),
            // перечислено в proguard-rules.pro — иначе ломается во время работы, а не при сборке.
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Юнит-тесты не трогают Android: Log и SystemClock возвращают значения по умолчанию.
        unitTests.isReturnDefaultValues = true
    }
    androidResources {
        // ONNX-модели читаются из assets через mmap/стрим — не сжимаем их в APK.
        noCompress += "onnx"
    }
}

/**
 * У каждого APK должен быть свой versionCode, иначе их не выложить рядом — магазин считает
 * их одной и той же сборкой. Номер собирается как «общий × 10 + код архитектуры», причём
 * 64-битная версия получает номер больше 32-битной: если устройству подходят обе, ставится
 * та, что новее по номеру.
 */
private val abiVersionOffsets = mapOf("armeabi-v7a" to 1, "x86_64" to 2, "arm64-v8a" to 3)

androidComponents {
    onVariants { variant ->
        for (output in variant.outputs) {
            val abi = output.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier ?: continue
            output.versionCode.set((android.defaultConfig.versionCode ?: 1) * 10 + (abiVersionOffsets[abi] ?: 0))
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.exifinterface)
    implementation(libs.zxing.core)

    implementation(libs.coil.compose)
    implementation(libs.coil.video)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui.compose)
    implementation(libs.androidx.media3.ui.compose.material3)

    implementation(libs.onnxruntime.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

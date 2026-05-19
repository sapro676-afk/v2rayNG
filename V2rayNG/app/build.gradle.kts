import org.gradle.api.GradleException

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

fun String.asBuildConfigString(): String =
    "\"" + replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val olcrtcRepoPath = providers.environmentVariable("OLCRTC_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("olcrtc").absolutePath)
val olcrtcRepoDir = file(olcrtcRepoPath.get())
val androidLibXrayRepoPath = providers.environmentVariable("ANDROID_LIB_XRAY_REPO")
    .orElse(rootProject.layout.projectDirectory.asFile.parentFile.resolve("AndroidLibXrayLite").absolutePath)
val androidLibXrayRepoDir = file(androidLibXrayRepoPath.get())
val combinedGoMobileWorkDir = layout.buildDirectory.dir("generated/gomobile-work")
val combinedGoMobileAar = layout.buildDirectory.file("generated/gomobile/libv2ray-olcrtc.aar")
val combinedGoMobileAarFile = combinedGoMobileAar.get().asFile

fun readGoModulePath(goMod: File): String =
    goMod.readLines()
        .firstOrNull { it.startsWith("module ") }
        ?.removePrefix("module ")
        ?.trim()
        ?: throw GradleException("Missing module directive in ${goMod.absolutePath}")

val buildCombinedGoMobileAar by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds one gomobile Android AAR containing AndroidLibXrayLite and olcRTC."

    inputs.dir(androidLibXrayRepoDir)
    inputs.dir(olcrtcRepoDir.resolve("mobile"))
    inputs.dir(olcrtcRepoDir.resolve("internal"))
    inputs.files(androidLibXrayRepoDir.resolve("go.mod"), androidLibXrayRepoDir.resolve("go.sum"))
    inputs.files(olcrtcRepoDir.resolve("go.mod"), olcrtcRepoDir.resolve("go.sum"))
    outputs.file(combinedGoMobileAar)

    workingDir = combinedGoMobileWorkDir.get().asFile

    doFirst {
        if (!androidLibXrayRepoDir.resolve("go.mod").exists()) {
            throw GradleException(
                "ANDROID_LIB_XRAY_REPO must point to an AndroidLibXrayLite checkout before building this APK: " +
                    androidLibXrayRepoDir.absolutePath
            )
        }
        if (!olcrtcRepoDir.resolve("go.mod").exists()) {
            throw GradleException(
                "OLCRTC_REPO must point to an olcrtc checkout before building this APK: ${olcrtcRepoDir.absolutePath}"
            )
        }
        combinedGoMobileAarFile.parentFile.mkdirs()
        workingDir.mkdirs()

        val goWork = workingDir.resolve("go.work")
        goWork.writeText(
            """
            go 1.26

            use (
                ${androidLibXrayRepoDir.absolutePath}
                ${olcrtcRepoDir.absolutePath}
            )
            """.trimIndent() + "\n"
        )

        commandLine(
            "gomobile",
            "bind",
            "-target=android/arm,android/arm64,android/amd64",
            "-androidapi",
            "21",
            "-ldflags",
            "-s -w -checklinkname=0",
            "-o",
            combinedGoMobileAarFile.absolutePath,
            readGoModulePath(androidLibXrayRepoDir.resolve("go.mod")),
            "${readGoModulePath(olcrtcRepoDir.resolve("go.mod"))}/mobile"
        )
    }
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.v2ray.ang"
        minSdk = 24
        targetSdk = 36
        versionCode = 725
        versionName = "2.1.5"
        multiDexEnabled = true
        buildConfigField("String", "OLCRTC_KEY", (System.getenv("OLCRTC_KEY") ?: "").asBuildConfigString())
        buildConfigField("String", "OLCRTC_ROOM_ID", (System.getenv("OLCRTC_ROOM_ID") ?: "").asBuildConfigString())
        buildConfigField("String", "OLCRTC_CLIENT_ID", (System.getenv("OLCRTC_CLIENT_ID") ?: "").asBuildConfigString())
        buildConfigField("String", "OLCRTC_CARRIER", (System.getenv("OLCRTC_CARRIER") ?: "wbstream").asBuildConfigString())
        buildConfigField("String", "OLCRTC_TRANSPORT", (System.getenv("OLCRTC_TRANSPORT") ?: "vp8channel").asBuildConfigString())
        buildConfigField("String", "OLCRTC_LINK", (System.getenv("OLCRTC_LINK") ?: "direct").asBuildConfigString())
        buildConfigField("String", "OLCRTC_CONFIG_URL", (System.getenv("OLCRTC_CONFIG_URL") ?: "").asBuildConfigString())
        buildConfigField("String", "OLCRTC_CONFIG_TOKEN", (System.getenv("OLCRTC_CONFIG_TOKEN") ?: "").asBuildConfigString())

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        val buildUniversalApk =
            (properties["UNIVERSAL_APK"] as? String)?.toBooleanStrictOrNull() ?: abiFilterList.isNullOrEmpty()
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = buildUniversalApk
            }
        }

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

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".olcrtc"
            buildConfigField("String", "DISTRIBUTION", "\"olcRTC\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        if (isFdroid) {
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 1, "x86" to 4, "x86_64" to 3, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName = "v2rayNG_${variant.versionName}-olcrtc_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            val versionCodes =
                mapOf("armeabi-v7a" to 4, "arm64-v8a" to 4, "x86" to 4, "x86_64" to 4, "universal" to 4)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName = "v2rayNG_${variant.versionName}_${abi}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(
        fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"), "exclude" to listOf("libv2ray.aar")))
    )
    implementation(files(combinedGoMobileAarFile).builtBy(buildCombinedGoMobileAar))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // UI Libraries
    implementation(libs.material)
    implementation(libs.toasty)
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}

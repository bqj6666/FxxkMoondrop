plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fxxkmoondrop.secret"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fxxkmoondrop.secret"
        minSdk = 26
        targetSdk = 36
        versionCode = 327
        versionName = "3.2.7"

        // DexKit 自带 4 个 ABI 的 libdexkit.so；x86/x86_64 只服务模拟器，
        // 剔除后单 APK 省约 0.8MB（模块只跑在真机上）。
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("app2.keystore")
            storePassword = providers.environmentVariable("FXXK_KEYPASS").orElse(providers.gradleProperty("fxxkKeypass")).getOrElse("")
            keyAlias = providers.gradleProperty("fxxkKeyAlias").getOrElse("fxxk")
            keyPassword = providers.environmentVariable("FXXK_KEYPASS").orElse(providers.gradleProperty("fxxkKeypass")).getOrElse("")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    // 源码复用现有 src/（单一权威源，不复制双份）
    sourceSets {
        getByName("main") {
            java.srcDirs("../src")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    // 纯 JVM 单元测试（GaiaCommands 帧构造/映射）；无 Android 依赖，缺失的框架桩返回默认值
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/**"
            )
        }
    }
}

dependencies {
    // AndroidX（版本与旧链 libs/ 一致，2026-08-23 对齐）
    implementation("androidx.activity:activity:1.9.0")
    implementation("androidx.annotation:annotation:1.7.1")
    implementation("androidx.annotation:annotation-experimental:1.0.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.appcompat:appcompat-resources:1.7.0")
    implementation("androidx.arch.core:core-runtime:2.2.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.cursoradapter:cursoradapter:1.0.0")
    implementation("androidx.customview:customview:1.1.0")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.dynamicanimation:dynamicanimation:1.0.0")
    implementation("androidx.emoji2:emoji2:1.4.0")
    implementation("androidx.emoji2:emoji2-views:1.4.0")
    implementation("androidx.emoji2:emoji2-views-helper:1.4.0")
    implementation("androidx.fragment:fragment:1.7.1")
    implementation("androidx.interpolator:interpolator:1.0.0")
    implementation("androidx.lifecycle:lifecycle-livedata:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-core:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.7.0")
    implementation("androidx.loader:loader:1.0.0")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.savedstate:savedstate:1.2.1")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.transition:transition:1.5.1")
    implementation("androidx.vectordrawable:vectordrawable:1.2.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")
    implementation("androidx.versionedparcelable:versionedparcelable:1.1.1")
    implementation("androidx.viewpager:viewpager:1.0.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Kotlin（与旧链 libs/ 版本一致）
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Xposed API：仅编译期（运行时由 LSPosed 提供）
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit（org.luckypray:dexkit）：运行时解析目标 APK 的 dex，
    // 用特征（字符串/调用关系/修饰符）反查被混淆的类与方法，
    // 避免把混淆名硬编码进来。
    // 文档：https://luckypray.org/DexKit/
    implementation("org.luckypray:dexkit:2.2.0")

    // 单元测试（纯 JVM，验证 GaiaCommands 帧构造）
    testImplementation("junit:junit:4.13.2")
    // 3.0.3: HookGuardTest 需要 XposedInterface.Chain 做桩，测试期才把它放进 classpath
    // （主代码仍是 compileOnly，运行时由 LSPosed 提供，不进 APK）
    testImplementation("io.github.libxposed:api:102.0.0")
}

// —— LSPosed 推荐作用域 EDF 注入（构建后处理：注入 scope.list/ascope.list + 重签）——
// AGP 8.x 签名内嵌 packageRelease，无 signRelease 任务；zip 追加会破坏 v2/v3，故 assemble 后重签。
val postEdf by tasks.registering(Exec::class) {
    group = "build"
    description = "注入 META-INF/xposed/* + 重签（LSPosed 推荐作用域 EDF）"
    val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk")
    val edfDir = file("src/main/resources/META-INF/xposed")
    val ksPass = providers.environmentVariable("FXXK_KEYPASS")
        .orElse(providers.gradleProperty("fxxkKeypass")).getOrElse("")
    doFirst {
        // 动态解析 apksigner：优先 ANDROID_HOME / ANDROID_SDK_ROOT，其次 local.properties 的 sdk.dir，最后 fallback
        val androidSdk = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: run {
                val lp = rootProject.file("local.properties")
                if (lp.exists()) lp.readLines()
                    .map { it.trim() }
                    .firstOrNull { it.startsWith("sdk.dir=") }
                    ?.substringAfter("sdk.dir=") else null
            }
        val buildTools = (androidSdk ?: "/workspace/sdk") + "/build-tools/34.0.0"
        val apksigner = file(buildTools + "/apksigner")
        if (!apksigner.exists()) throw GradleException("apksigner not found: ${apksigner.absolutePath}（请确认 SDK build-tools 34.0.0 已安装且 ANDROID_HOME 正确）")
        commandLine(
            "python3", "$rootDir/tools/post_edf.py",
            apk.get().asFile.absolutePath,
            "$edfDir/scope.list", "$edfDir/ascope.list",
            "$rootDir/app2.keystore", ksPass,
            apksigner.absolutePath
        )
    }
}
tasks.configureEach {
    if (name == "assembleRelease") {
    }
}

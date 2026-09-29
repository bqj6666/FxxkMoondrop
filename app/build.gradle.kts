plugins {
    id("com.android.application")
    // 3.2.13: AGP 9.0 起 Kotlin 支持由 AGP 内置，
    // 不能再声明 org.jetbrains.kotlin.android（会直接报错，见 kotl.in/gradle/agp-built-in-kotlin）
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fxxkmoondrop.secret"
    // 3.2.13: 35 -> 37。Miuix（HyperOS 观感轨）全部版本的
    // aar-metadata.properties 都写死 minCompileSdk=37，没有 35 可用的版本。
    // 升的是**编译期**可见 API，targetSdk 仍是 36，运行时行为不变。
    // 实测使用面极窄：全仓仅 2 处 VERSION_CODES.Q（API 35），
    // 最高 SDK_INT 判断为 34，因此升级无行为变更风险。
    compileSdk = 37

    defaultConfig {
        applicationId = "com.fxxkmoondrop.secret"
        minSdk = 26
        targetSdk = 36
        versionCode = 332
        versionName = "3.2.12"

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
            // 3.2.13: srcDirs() 在 AGP 9 已废弃，改用 directories 可变集合。
            //
            // ⚠️ 必须**同时**注册 kotlin 与 java：原来的 java.srcDirs("../src")
            // 会同时把该目录纳入 Kotlin 与 Java 两套源；只写 java.directories 时
            // compileDebugKotlin 会报 NO-SOURCE，随后 Java 侧因找不到 Kotlin
            // 生成的类（如 AppLog.hex）报「cannot find symbol」——症状离根因很远。
            // 本项目源码在仓库根 src/（不是 app/src/），这个布局不能改。
            kotlin.directories.add("../src")
            java.directories.add("../src")
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    buildFeatures {
        // 3.2.13: HyperOS 观感轨（UiStyle.MIUIX）需要 Compose。
        // Material 轨不构建任何 Compose 源文件（源文件在 src/ 下按命名区分），
        // 但 Compose 运行时仍会进 APK —— 体积影响见 docs/XIAOMI_INTEGRATION_PROGRESS.md。
        compose = true
    }

    compileOptions {
        // 3.2.13: 17 -> 21，与 CI 的 JDK 21 及上方 jvmTarget 保持一致。
        // 三处（compileOptions / jvmTarget / CI java-version）必须同步改，
        // 否则会出现「Kotlin 字节码 21 + Java 目标 17」的混编警告或失败。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    // Kotlin 2.3+ 起 kotlinOptions.jvmTarget 由警告升级为错误，须用 compilerOptions DSL。
    // 位置必须在顶层 kotlin {}（不能放在 android {} 内），见 kotl.in/u1r8ln

    // 纯 JVM 单元测试（GaiaCommands 帧构造/映射）；无 Android 依赖，缺失的框架桩返回默认值
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

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
    implementation("androidx.core:core:1.16.0")
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
    implementation("com.google.android.material:material:1.14.0")
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
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Xposed API：仅编译期（运行时由 LSPosed 提供）
    compileOnly("io.github.libxposed:api:102.0.0")

    // DexKit（org.luckypray:dexkit）：运行时解析目标 APK 的 dex，
    // 用特征（字符串/调用关系/修饰符）反查被混淆的类与方法，
    // 避免把混淆名硬编码进来。
    // 文档：https://luckypray.org/DexKit/
    implementation("org.luckypray:dexkit:2.3.0")

    // 单元测试（纯 JVM，验证 GaiaCommands 帧构造）
    testImplementation("junit:junit:4.13.2")
    // 测试专用：android.jar 里的 org.json 是桩（且 unitTests.isReturnDefaultValues=true
    // 会让它静默返回默认值），必须用真实实现才能验证 release 响应的解析。
    // 仅测试期生效，不进 APK、不增加运行时体积。
    testImplementation("org.json:json:20240303")
    // 3.0.3: HookGuardTest 需要 XposedInterface.Chain 做桩，测试期才把它放进 classpath
    // （主代码仍是 compileOnly，运行时由 LSPosed 提供，不进 APK）
    testImplementation("io.github.libxposed:api:102.0.0")

    // ── 3.2.13: HyperOS 观感轨（UiStyle.MIUIX）────────────────────────
    // 许可：Miuix 为 Apache-2.0（不同于 HyperEars 的 AGPL-3.0，可放心引入）。
    // 兼容性已核：miuix-ui-android 0.9.2 自身 minSdk=23，本模块 minSdk 26 满足。
    // 参考项目 OppoPods / HyperEars 用 minSdk 35，本模块不跟随。
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.2")
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
        // 3.2.13: 不再硬编码 build-tools 34.0.0（compileSdk 已升到 37，CI 装的是 36.0.0）。
        // 改为按版本号倒序探测：取**最新的** build-tools 里那个可用的 apksigner。
        // 硬编码版本号在 SDK 升级时必然失效，而这里失效的表现是构建莫名中断。
        val sdkRoot = androidSdk ?: "/workspace/sdk"
        val btRoot = file("$sdkRoot/build-tools")
        val candidates = (btRoot.listFiles()?.toList() ?: emptyList())
            .filter { it.isDirectory && File(it, "apksigner").exists() }
            .sortedByDescending { it.name }   // 版本号字符串倒序：36 > 35 > 34
        // 注意：AGP 9 的 file(File, String) 重载已移除（第二参要 PathValidation），
        // 这里直接用 java.io.File 拼接，避免踩这层 API 变化。
        val apksigner = candidates.firstOrNull()?.let { File(it, "apksigner") }
            ?: throw GradleException(
                "apksigner not found under $btRoot（请确认已安装 build-tools，" +
                "且 ANDROID_HOME / local.properties 的 sdk.dir 指向正确；" +
                "已装版本：" + (btRoot.list()?.toList()?.joinToString() ?: "无")
            )
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

kotlin {
    compilerOptions {
        // 3.2.13: 17 -> 21，与 CI 的 JDK 21 对齐（AGP 8.6 时代是 JDK 17）
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

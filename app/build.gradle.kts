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
            // 3.2.13: false -> true。引入 Compose + Miuix 后体积从 6.1M 涨到 18M，
            // 开 R8 压缩换回可接受的体积。
            //
            // 风险已逐项核实（全仓 91 处反射）：
            //   - 反射目标全是外部类（android.* / com.android.* / com.google.android.material.*
            //     / com.moondroplab.* / com.qualcomm.*），不在我们 APK 内，不受影响；
            //   - 真正的风险只有「LSPosed 按字符串加载入口」与「manifest 声明的组件
            //     被系统按字符串实例化」两类，已在 proguard-rules.pro 里按需 keep。
            //
            // ⚠️ 规则刻意不写 `-keep class com.fxxkmoondrop.secret.** { *; }`，
            // 那等于放弃 R8 对我们代码的全部优化，体积收益会大幅缩水。
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    // 3.2.13: miuix-preference 提供 **官方**的下拉选择组件
    // `OverlayDropdownPreference`（title/summary/items/selectedIndex/onSelectedIndexChange）。
    // 版���与 miuix-ui 严格一致（同 0.9.2），来自同一个 BOM。
    //
    // 用它的原因（用户要求「按 MIUI 官方的用法和规范」）：
    // 自己拼 Popup 反复踩坑 —— 点外部不关闭、宽度不可控、动画不对，
    // 而 ListPopupColumn / OverlayListPopup 又依赖 Scaffold + androidx.navigation。
    // 官方组件把这一整套都封装好了，排版与尺寸都按 HyperOS 规范。
    // 参考用法：OppoPods 的 SettingsPage（同样 miuix 0.9.2）。
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.2")

    // 3.2.13: androidx.navigation —— 只为拿到 Miuix 下拉所需的那个接口。
    //
    // `LocalWindowInfo.current` 由 androidx.navigation 提供，它要求宿主 Activity
    // 实现 `NavigationEventDispatcherOwner`；`ComponentActivity` **只有在
    // navigation 在 classpath 里时**才会实现该接口。没有它，Miuix 任何下拉
    // / 弹层一打开就崩：
    //   IllegalStateException: No NavigationEventDispatcher was provided
    //   via LocalNavigationEventDispatcherOwner
    //
    // ⚠️ 我们**不使用** NavHost/NavController、不改任何页面架构，
    // 纯粹是让 Miuix 的弹层能开。参考项目同样引了它
    // （HyperEars 用 navigation-compose 2.10.0，OppoPods 用 navigation3-runtime）。
    //
    // 只引 runtime（不含 compose 导航 UI），体积增量最小。
    implementation("androidx.navigation:navigation-runtime:2.10.0")
    // navigationevent 是 navigation-runtime 的**传递**依赖（runtime scope），
    // 编译期不可见，而 MainActivity 要实现它声明的接口 → 必须显式引入。
    implementation("androidx.navigationevent:navigationevent-android:1.1.1")
}

// 3.2.13: 删除 postEdf（EDF 作用域注入 + 重签）整段。
//
// 查证结论：这个任务**从未被执行过**，而且执行了也没意义。
//   - 它注册于 768d9db，但同一次提交里 `tasks.configureEach` 只留了空壳
//     （`if (name == "assembleRelease") { }`），没有任何 dependsOn/ finalizedBy，
//     所以 postEdf 一直是「注册了但没人调用」的死代码。
//   - 它唯一比 packaging 多做的事是注入 `ascope.list`，而该文件
//     `git log --all` 查不到任何记录 —— 从未存在过，post_edf.py 会直接 skip。
//   - `scope.list` 早由 packaging.resources.merges = "META-INF/xposed/*"
//     自动合入 APK（已下载 ci-209 产物核实：META-INF/xposed/ 下
//     java_init.list / module.prop / scope.list 三者俱全）。
//
// 即：功能由 packaging 完整覆盖，postEdf 纯属冗余。留着它有两个害处：
// 一是让后来人误以为「作用域靠它注入」，二是 build-tools 探测失败会让
// 构建莫名中断。ponytail 原则：删掉比留着当陷阱好。
//
// tools/post_edf.py 同步删除。

kotlin {
    compilerOptions {
        // 3.2.13: 17 -> 21，与 CI 的 JDK 21 对齐（AGP 8.6 时代是 JDK 17）
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

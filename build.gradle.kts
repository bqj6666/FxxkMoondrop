// 根构建脚本：仅声明插件版本（apply false），模块各自 apply
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // 3.2.13: HyperOS 观感轨（UiStyle.MIUIX）需要 Compose。
    // Kotlin 2.x 起 Compose 编译器由 Kotlin 插件自带，不再用 composeOptions.kotlinCompilerExtensionVersion。
    // 版本必须与 kotlin 一致（2.4.21），否则报「此 Compose 版本需要更新的编译器」。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    // exp/yukihook-1.5：YukiHook 1.5.0 的 yukihook-compiler 是 KSP 处理器。
    // KSP 自 2.x 起改为独立版本号（不再与 Kotlin 版本绑定），
    // 2.3.12 就是 YukiHook 官方自己配 Kotlin 2.4.21 用的那一个。
    id("com.google.devtools.ksp") version "2.3.12" apply false
}

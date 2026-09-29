// 根构建脚本：仅声明插件版本（apply false），模块各自 apply
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    // 3.2.13: HyperOS 观感轨（UiStyle.MIUIX）需要 Compose。
    // Kotlin 2.x 起 Compose 编译器由 Kotlin 插件自带，不再用 composeOptions.kotlinCompilerExtensionVersion。
    // 版本必须与 kotlin 一致（2.3.21），否则报「此 Compose 版本需要更新的编译器」。
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}

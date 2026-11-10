pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenCode"

// 唯一产品模块：WebView 宿主（官方 packages/app UI + 平台桥）。
// 旧原生壳 :app（Compose）与 termux vendored 终端已于 2.0.37 后退役删除，
// 历史代码见 tag archive/pre-cleanup-2.0.37。
include(":webapp")

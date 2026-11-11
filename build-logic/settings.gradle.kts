dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()       // kotlin-dsl 플러그인을 여기서 받음
    }
    versionCatalogs {
        create("libs") {           // 루트의 버전 카탈로그를 build-logic에서도 쓰기 위해 연결
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
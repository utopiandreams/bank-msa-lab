pluginManagement {
    includeBuild("build-logic")     // "build-logic이 만든 플러그인도 찾아 써라"
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()              // 모든 모듈의 저장소를 여기서 한 번에 지정
    }
}

rootProject.name = "bank-msa-lab"

include(":services:core")
include(":services:transfer")
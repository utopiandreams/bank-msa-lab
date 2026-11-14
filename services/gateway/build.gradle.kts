plugins {
	id("bank-msa-lab")
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "lab.bank"
version = "0.0.1-SNAPSHOT"

// Spring Cloud는 Boot와 따로 버전을 매기는 "릴리스 트레인"이다. BOM 하나로 gateway 등 하위 모듈 버전을 맞춘다
dependencyManagement {
	imports {
		mavenBom(libs.spring.cloud.dependencies.get().toString())
	}
}

dependencies {
	// WebFlux(Netty) 기반 gateway. 2025.0부터 이름이 바뀌었다 (구 spring-cloud-starter-gateway → -server-webflux)
	implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

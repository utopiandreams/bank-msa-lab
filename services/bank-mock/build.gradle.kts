plugins {
	id("bank-msa-lab")
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "lab.bank"
version = "0.0.1-SNAPSHOT"

// 외부 결제망(금융결제원 + 타행) 흉내. DB 없음: 남의 은행이라 우리가 내구성을 책임지지 않는다 (ADR-0004)
dependencies {
	implementation(platform(libs.aws.sdk.bom))                                 // S3 클라이언트 (ADR-0006 대사 추출 파일, Garage). Garage는 경로 방식이라 forcePathStyle(true)
	implementation(libs.aws.s3)
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

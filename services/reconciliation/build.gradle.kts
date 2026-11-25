plugins {
	id("bank-msa-lab")
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	kotlin("plugin.jpa") version "2.3.21"   // JPA 엔티티에 기본 생성자를 만들어 줌 (Kotlin 클래스엔 없음)
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "lab.bank"
version = "0.0.1-SNAPSHOT"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-kafka")    // 추출 완료 이벤트 구독 (인박스, ADR-0006)
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	runtimeOnly("io.micrometer:micrometer-registry-prometheus")              // /actuator/prometheus (버전은 Boot가 관리)
	implementation("org.springframework.boot:spring-boot-starter-webmvc")   // actuator(프로브) HTTP 입구
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")            // 이벤트 JSON → Kotlin data class
	implementation(platform(libs.aws.sdk.bom))                                 // S3 클라이언트 (ADR-0006 대사 추출 파일, Garage). Garage는 경로 방식이라 forcePathStyle(true)
	implementation(libs.aws.s3)
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")  // @ServiceConnection
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-kafka")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

allOpen {
	annotation("jakarta.persistence.Entity")
	annotation("jakarta.persistence.MappedSuperclass")
	annotation("jakarta.persistence.Embeddable")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

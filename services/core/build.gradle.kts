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
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")   // 스키마를 SQL 파일로 버전 관리
	runtimeOnly("org.flywaydb:flyway-database-postgresql")                   // Flyway 10+는 DB별 모듈이 분리됨
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")  // @ServiceConnection
	testImplementation("org.testcontainers:testcontainers-postgresql")        // Testcontainers 2.x 모듈 이름
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation(libs.mockito.kotlin)                                   // 동시성 테스트에서 spy로 실행 순서를 강제
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// JPA는 지연 로딩 프록시를 만들려고 엔티티를 상속한다. Kotlin 클래스는 기본이 final이라 열어 준다
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



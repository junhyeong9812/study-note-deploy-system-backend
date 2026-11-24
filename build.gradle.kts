plugins {
	kotlin("jvm") version "2.3.21"
	kotlin("plugin.spring") version "2.3.21"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "xyz.junproject"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework:spring-jdbc")      // JdbcTemplate 만 — starter-jdbc 는 DB 헬스를 기본 헬스에 묶어 DB 가 죽으면 자리 전환이 막힌다
	implementation("com.zaxxer:HikariCP")                  // 연결 풀은 직접 만든다(뜰 때 DB 가 없어도 죽지 않게)
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("io.mockk:mockk:1.13.13")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

// 단위 테스트만 — Docker 빌드의 test 단계가 이것을 돈다(빌드 안에서는 DB 를 띄울 수 없다).
// withType 이 아니라 test 하나에만 건다 — withType 이면 engineTest 에도 excludeTags 가 붙어 아무것도 돌지 않는다.
tasks.named<Test>("test") {
	useJUnitPlatform { excludeTags("engine") }
}

// 실제 PostgreSQL 이 있어야 도는 테스트 — scripts/engine-test.sh 가 컨테이너를 띄우고 부른다
tasks.register<Test>("engineTest") {
	description = "실제 PostgreSQL 에 대고 도는 테스트"
	group = "verification"
	testClassesDirs = sourceSets["test"].output.classesDirs
	classpath = sourceSets["test"].runtimeClasspath
	useJUnitPlatform { includeTags("engine") }
	outputs.upToDateWhen { false }
}

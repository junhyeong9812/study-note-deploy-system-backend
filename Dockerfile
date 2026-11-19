# 빌드 — gradle wrapper가 배포판을 받아 bootJar 생성
FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true   # 의존성 레이어 캐시
COPY src ./src
RUN ./gradlew --no-daemon bootJar

# 테스트 — 배포 전에 ci-cd agent 가 `docker build --target test` 로 실행한다 (실패하면 배포 안 함)
FROM build AS test
RUN ./gradlew --no-daemon test

# 실행
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl git && rm -rf /var/lib/apt/lists/*
COPY --from=build /build/build/libs/*.jar app.jar
EXPOSE 8090
CMD ["java", "-jar", "app.jar"]

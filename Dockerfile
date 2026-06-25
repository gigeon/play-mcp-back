# syntax=docker/dockerfile:1

# ---------- build stage ----------
FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace

# 의존성 캐시 최적화: 빌드 스크립트 먼저 복사
COPY settings.gradle build.gradle ./
COPY gradle ./gradle
# (선택) 의존성 미리 받아두기 - 실패해도 무시
RUN gradle dependencies --no-daemon > /dev/null 2>&1 || true

# 소스 복사 후 부트 jar 빌드
COPY src ./src
RUN gradle bootJar --no-daemon

# ---------- run stage ----------
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app

# 비루트 사용자로 실행 (보안)
RUN groupadd -r app && useradd -r -g app app

COPY --from=build /workspace/build/libs/*.jar app.jar
USER app

EXPOSE 8000

# 컨테이너 메모리에 맞춰 힙 자동 조정
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

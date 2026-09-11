# API/Worker가 공유하는 단일 이미지. 어느 프로세스로 뜨는지는 오직 컨테이너 실행 시점의
# SPRING_PROFILES_ACTIVE(experiment-api / experiment-worker)로만 갈린다 - 이미지/JAR은 하나다.

# ---- build stage: JAR만 만든다. bootJar는 test task에 의존하지 않으므로 여기서 테스트를
# 실행하지 않는다(테스트는 CI/로컬 ./gradlew test가 담당 - 이미지 빌드 시간·컨텍스트에서
# 테스트 결과물을 분리한다). ----
FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /workspace

# 의존성 레이어를 소스 변경과 분리해 캐시 히트율을 높인다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
# 이 저장소는 Windows(CRLF)에서도 체크아웃된다 - gradlew가 CRLF로 복사되면 Alpine의 /bin/sh가
# shebang을 못 읽어 "gradlew: not found"(exit 127)로 죽는다. 실제로 겪은 문제라 sed로
# 정규화한다(호스트 파일 자체는 건드리지 않는다 - 이 빌드 스테이지 안에서만 고친다).
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
RUN ./gradlew --no-daemon dependencies

COPY src/main src/main
RUN ./gradlew --no-daemon bootJar

# ---- run stage ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /workspace/build/libs/*.jar app.jar
ENTRYPOINT ["java","-jar","app.jar"]

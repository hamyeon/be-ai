FROM eclipse-temurin:17-jdk-alpine
ARG JAR_FILE=build/libs/*.jar

# #127: AnalysisStatus.CANCELLED를 이해하는 이미지임을 빌드 시점에 명시한다. scripts/aws/deploy.sh의
# 자동 롤백이 이 라벨로만 호환성을 판정한다 - 태그 이름이나 빌드 시각으로 추측하지 않는다. 이
# 라벨이 없는 이미지(이 변경 이전에 빌드됨)로는 자동 롤백하지 않는다 - DB에 이미 CANCELLED
# 행이 있을 수 있는데, 그 상태를 모르는 구버전 AnalysisStatus enum으로 되돌리면 역직렬화가
# 실패한다. 이 값을 지우거나 이름을 바꾸지 않는다 - 지우면 이후 빌드되는 모든 이미지가
# "호환성 정보 없음"으로 판정돼 자동 롤백 대상에서 전부 빠진다.
LABEL com.autique.compat.analysis-cancellation="supported"

COPY ${JAR_FILE} app.jar
ENTRYPOINT ["java","-jar","/app.jar"]
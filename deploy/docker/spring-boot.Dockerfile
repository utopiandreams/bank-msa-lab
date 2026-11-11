# 모든 Spring Boot 서비스가 같이 쓰는 Dockerfile (공통 Helm 차트와 같은 발상: 서비스마다 복붙하지 않는다)
#
# 사용법 (빌드 컨텍스트 = 서비스의 build/libs, jar는 Gradle이 미리 만들어 둔다):
#   ./gradlew :services:core:bootJar
#   docker build -f deploy/docker/spring-boot.Dockerfile \
#     --build-arg JAR_FILE=core-0.0.1-SNAPSHOT.jar \
#     -t core:local services/core/build/libs
#
# 왜 jar를 밖에서 만들어 넣나: Gradle 빌드·테스트는 CI가 이미 돌린다. Docker 안에서 또 빌드하면
# 의존성 캐시가 없어 느리고, 같은 일을 두 번 한다. Docker는 "포장"만 맡는다.

# 1단계: fat jar를 레이어별 디렉터리로 풀기
FROM eclipse-temurin:25-jre AS extract
WORKDIR /work
ARG JAR_FILE
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# 2단계: 실행 이미지
FROM eclipse-temurin:25-jre
# root로 돌리지 않는다. 컨테이너가 뚫려도 노드 권한까지 넘어가기 어렵게.
RUN useradd --system --uid 10001 --no-create-home app
WORKDIR /app

# 잘 안 바뀌는 것부터 복사한다. 라이브러리(수십 MB)는 거의 안 바뀌고 우리 코드(수십 KB)만 바뀌므로,
# 코드만 고친 재배포에서는 마지막 레이어만 새로 받는다 → push·pull이 빨라진다.
COPY --from=extract /work/extracted/dependencies/ ./
COPY --from=extract /work/extracted/spring-boot-loader/ ./
COPY --from=extract /work/extracted/snapshot-dependencies/ ./
COPY --from=extract /work/extracted/application/ ./

USER 10001
EXPOSE 8080

# 힙을 고정값(-Xmx)이 아니라 컨테이너 메모리 limit의 비율로 잡는다.
# 나머지 25%는 메타스페이스·스레드 스택·다이렉트 버퍼 몫. 100%로 잡으면 OOMKilled.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

ENTRYPOINT ["java", "-jar", "app.jar"]

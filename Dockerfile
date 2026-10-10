# syntax=docker/dockerfile:1
#
# agentswitchyard 统一构建镜像。
# 多阶段构建：builder 阶段用 mvnw 打 exec.jar，runtime 阶段只带 JRE + 两个 jar。
# 刻意不 COPY contract/ 与 docs/ —— 镜像越小，嵌入业务应用越容易被接受。

# ---------- 构建阶段 ----------
FROM eclipse-temurin:25-jdk-jammy AS builder

WORKDIR /build

# 先只拷 pom 与 wrapper，利用 Docker 层缓存：依赖不变时不重新解析
COPY .mvn/ .mvn/
COPY mvnw mvnw.cmd ./
COPY pom.xml ./
COPY core/pom.xml core/
COPY store/pom.xml store/
COPY starter/pom.xml starter/
COPY mock-llm/pom.xml mock-llm/
COPY sample/pom.xml sample/
COPY LICENSE LICENSE-HEADER.txt ./

# 预热依赖（此步失败会让镜像构建失败，属预期行为：依赖解析不了就不该继续）
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline -DexcludeArtifactIds=agentswitchyard-core,agentswitchyard-store,agentswitchyard-spring-boot-starter,agentswitchyard-mock-llm || true

COPY core/src core/src
COPY store/src store/src
COPY starter/src starter/src
COPY mock-llm/src mock-llm/src
COPY sample/src sample/src

# 打包两个可执行 jar（mock-llm 与 sample）；跳过测试，因为 CI 已跑过
RUN ./mvnw -B -q clean package -DskipTests -Dspotless.check.skip=true -Djacoco.skip=true

# ---------- 运行阶段 ----------
FROM eclipse-temurin:25-jre-jammy AS runtime

# 中文场景：容器内必须有 UTF-8 与东八区，否则审批留痕里的中文会变成问号
ENV LANG=C.UTF-8 \
    TZ=Asia/Shanghai \
    JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

# 以非 root 运行：安全基线，且能提前暴露镜像里的权限问题
RUN groupadd --system --gid 1001 agentswitchyard \
 && useradd --system --uid 1001 --gid agentswitchyard --home /app agentswitchyard

COPY --from=builder /build/mock-llm/target/agentswitchyard-mock-llm-*-exec.jar /app/mock-llm.jar
COPY --from=builder /build/sample/target/agentswitchyard-sample-*-exec.jar /app/sample.jar

USER agentswitchyard

# 18081 = mock-llm，18080 = sample
EXPOSE 18081 18080

# 默认启动 mock-llm；docker-compose 会按服务覆盖 command
CMD ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/mock-llm.jar"]

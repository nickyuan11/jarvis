# syntax=docker/dockerfile:1
# 多阶段构建：第一阶段用带 Maven 的镜像编译打包，第二阶段只带运行需要的 JRE 和 jar。
# 最终镜像里没有源码、Maven 和编译工具，体积小，可被攻击的东西也少。

# ---------- 第一阶段：编译打包 ----------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
# --mount=type=cache：把 Maven 下载的依赖缓存在构建机上，下次构建不用重新下载。
# 这里跳过测试：测试在提交前已经在本地跑过，镜像构建只负责打包
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---------- 第二阶段：运行 ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# 不用 root 运行：万一应用被攻破，攻击者拿到的也只是一个没有权限的普通用户
RUN useradd --system --no-create-home jarvis
COPY --from=build /build/target/jarvis-*.jar app.jar
USER jarvis
EXPOSE 8080
# JAVA_OPTS 由 compose 传入（内存上限等）。exec 让 java 成为容器的主进程，
# docker stop 时它能直接收到停止信号，正常关闭（把手头的事做完再退出）
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

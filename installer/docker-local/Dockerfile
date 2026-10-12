FROM registry.cn-qingdao.aliyuncs.com/dataease/alpine-openjdk21-jre:latest

LABEL org.opencontainers.image.title="Cordys CRM Custom 1.6.0"
WORKDIR /opt/cordys
COPY app.jar /app/cordys-crm.jar
RUN mkdir -p /opt/cordys/conf /opt/cordys/data/files \
    /opt/cordys/runtime/logs/cordys-crm /opt/cordys/runtime/tmp/upload \
    /opt/cordys/runtime/task-attachments /opt/cordys/runtime/uploads/knowledge

ENV TZ=Asia/Hong_Kong
EXPOSE 8081
HEALTHCHECK --interval=15s --timeout=5s --start-period=90s --retries=10 \
    CMD curl --fail --silent http://127.0.0.1:8081/ >/dev/null || exit 1
ENTRYPOINT ["java", "-Xms256m", "-Xmx1536m", "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", "-Djava.io.tmpdir=/opt/cordys/runtime/tmp", "--add-opens=java.base/jdk.internal.loader=ALL-UNNAMED", "--add-opens=java.base/java.util=ALL-UNNAMED", "-jar", "/app/cordys-crm.jar"]

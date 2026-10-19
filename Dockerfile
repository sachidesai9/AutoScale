# AutoScaleSim Lab: original AutoScaleSim (unchanged) + extension + dashboard, in one container.
# Base images come from mirror.gcr.io (Google's mirror of the official Docker Hub images) to avoid Docker Hub pull limits.
# Build stage compiles everything with the same script used on a laptop.
FROM mirror.gcr.io/library/eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY lib ./lib
COPY src ./src
COPY extension ./extension
COPY scripts ./scripts
RUN chmod +x scripts/*.sh && scripts/build.sh

FROM mirror.gcr.io/library/eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/dist ./dist
# datasets and the authors' committed outputs (used by the reproduction check)
COPY src/others ./src/others
COPY samples ./samples
ENV DATA_DIR=/data PORT=8080 SIM_HEAP=1g
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /api/health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"status\":\"ok\"" <&3'
CMD ["sh", "-c", "exec java -Xmx512m -jar dist/autoscalesim-lab.jar serve --port ${PORT} --data-dir ${DATA_DIR} --data src/others"]

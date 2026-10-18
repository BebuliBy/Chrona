# Candidate official runtimes. Sysadmin must verify/pin immutable digests and CPU
# compatibility on the actual non-AVX2 amd64 host BEFORE deployment.
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /source
COPY gradlew ./gradlew
COPY gradle/wrapper ./gradle/wrapper
COPY backend ./backend
RUN sh ./gradlew -p backend --no-daemon installDist

FROM eclipse-temurin:17-jre-jammy
WORKDIR /opt/chrona
COPY --from=build --chown=10001:10001 /source/backend/build/install/chrona-backend/ ./
USER 10001:10001
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 CMD ["/opt/chrona/bin/chrona-backend", "healthcheck"]
ENTRYPOINT ["/opt/chrona/bin/chrona-backend"]
CMD ["api"]

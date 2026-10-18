# Runtime-only packaging of developer's already-tested installDist.
# Build context MUST contain ONLY this Dockerfile + reviewed distribution/.
ARG JVM_IMAGE
FROM ${JVM_IMAGE}
WORKDIR /opt/chrona
COPY --chown=10001:10001 distribution/ /opt/chrona/
USER 10001:10001
EXPOSE 8080 8081
HEALTHCHECK --interval=30s --timeout=10s --start-period=30s --retries=3 CMD ["/opt/chrona/bin/chrona-backend", "healthcheck"]
ENTRYPOINT ["/opt/chrona/bin/chrona-backend"]
CMD ["api"]

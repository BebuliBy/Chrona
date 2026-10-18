# Candidate cached PHP runtime. Pin supplied by reviewed runtime metadata;
# no pulling/packages/Nextcloud entrypoint or mounts are used by this component.
ARG PUBLISHER_BASE
FROM ${PUBLISHER_BASE}
WORKDIR /opt/chrona-distribution
COPY --chown=10002:10002 server.php ./server.php
COPY --chown=10002:10002 empty-root/ ./empty-root/
USER 10002:10002
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/php"]
CMD ["-d", "expose_php=0", "-d", "display_errors=0", "-d", "log_errors=0", "-d", "file_uploads=0", "-d", "enable_post_data_reading=0", "-d", "post_max_size=1K", "-d", "allow_url_fopen=0", "-d", "allow_url_include=0", "-d", "memory_limit=64M", "-d", "max_execution_time=30", "-d", "open_basedir=/published:/opt/chrona-distribution:/tmp", "-d", "disable_functions=exec,passthru,shell_exec,system,proc_open,popen,pcntl_exec", "-S", "0.0.0.0:8080", "-t", "/opt/chrona-distribution/empty-root", "/opt/chrona-distribution/server.php"]

# Magpie gateway: the resolver as an HTTP Maven repository.
#   docker build -f gateway/Dockerfile -t magpie-gateway .
#   docker run -p 8080:8080 -v magpie-cache:/var/cache/magpie \
#     -e MAGPIE_RELAYS=wss://relay.example.com \
#     -e MAGPIE_TRUST=com.example=npub1... \
#     magpie-gateway
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY . .
RUN ./gradlew --no-daemon --no-configuration-cache :gateway:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 magpie && mkdir -p /var/cache/magpie && chown magpie /var/cache/magpie
COPY --from=build /src/gateway/build/install/magpie-gateway /opt/magpie-gateway
ENV MAGPIE_CACHE_DIR=/var/cache/magpie \
    PORT=8080 \
    JAVA_OPTS="-Xmx512m"
VOLUME /var/cache/magpie
EXPOSE 8080
USER magpie
HEALTHCHECK --interval=30s --timeout=3s CMD ["sh", "-c", "exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf 'GET /healthz HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q ok <&3"]
ENTRYPOINT ["/opt/magpie-gateway/bin/magpie-gateway"]

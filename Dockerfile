FROM rust:1-bookworm@sha256:ade39a710ef85f3506cffcfec3e8a24ba090badef1dff2fb798f71d878833fea AS build
WORKDIR /build
COPY server/Cargo.toml server/Cargo.lock ./
COPY server/src ./src
COPY server/web ./web
# BuildKit caches contain dependencies/build products only; neither cache enters runtime.
RUN --mount=type=cache,id=pocketlog-registry,target=/usr/local/cargo/registry \
    --mount=type=cache,id=pocketlog-target,target=/build/target \
    find src web -type f -exec touch {} + && \
    cargo build --release --locked --jobs 2 && \
    mkdir -p /out && cp target/release/pocketlog-server target/release/pocketlog-worker /out/

FROM debian:bookworm-slim@sha256:e67eeead736ff7bbe258ef088bff683635c561b1fcfefad0806c1df4ab49ce0e
RUN groupadd --gid 10001 pocketlog && useradd --uid 10001 --gid 10001 --no-create-home pocketlog && mkdir /data /config && chown 10001:10001 /data /config && chmod 0700 /config
COPY --from=build /out/pocketlog-server /usr/local/bin/pocketlog-server
COPY --from=build /out/pocketlog-worker /usr/local/bin/pocketlog-worker
USER 10001:10001
ENV POCKETLOG_LISTEN=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["pocketlog-server"]

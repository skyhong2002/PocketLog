FROM rust:1-bookworm@sha256:75d26c23c24cc6ea1cdabf0714a6bff8fd7bf3248716043fd7ae86dac7f41b94 AS build
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

FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
RUN groupadd --gid 10001 pocketlog && useradd --uid 10001 --gid 10001 --no-create-home pocketlog && mkdir /data /config && chown 10001:10001 /data /config && chmod 0700 /config
COPY --from=build /out/pocketlog-server /usr/local/bin/pocketlog-server
COPY --from=build /out/pocketlog-worker /usr/local/bin/pocketlog-worker
USER 10001:10001
ENV POCKETLOG_LISTEN=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["pocketlog-server"]

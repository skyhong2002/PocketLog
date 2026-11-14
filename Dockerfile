FROM rust:1-bookworm@sha256:ade39a710ef85f3506cffcfec3e8a24ba090badef1dff2fb798f71d878833fea AS build
WORKDIR /build
COPY server/Cargo.toml server/Cargo.lock ./
COPY server/src ./src
COPY server/web ./web
RUN cargo build --release --locked --jobs 2

FROM debian:bookworm-slim@sha256:734a72b3d6142a71993b4bc580ca01d8ca687f77a2c422d96a961d3376b179bc
RUN groupadd --gid 10001 pocketlog && useradd --uid 10001 --gid 10001 --no-create-home pocketlog && mkdir /data /config && chown 10001:10001 /data /config && chmod 0700 /config
COPY --from=build /build/target/release/pocketlog-server /usr/local/bin/pocketlog-server
COPY --from=build /build/target/release/pocketlog-worker /usr/local/bin/pocketlog-worker
USER 10001:10001
ENV POCKETLOG_LISTEN=0.0.0.0:8080
EXPOSE 8080
ENTRYPOINT ["pocketlog-server"]

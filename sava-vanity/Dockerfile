ARG PROJECT="sava-vanity"
ARG MAJOR_JAVA_VERSION="27"
ARG SAVA_OPENJDK_IMAGE="jpe7s/sava-openjdk"
ARG SAVA_OPENJDK_TAG="27.0.2-jdk27-debian-trixie"
# docker buildx imagetools inspect <image>:<tag> --format '{{.Manifest.Digest}}'
ARG SAVA_OPENJDK_DIGEST="sha256:9bd0ee26845c6ea829a353918c3b1f10df6438f9dfa7a5539c3b46454cf9a788"
# UID:GID for the unprivileged build and runtime users. Numeric on purpose: the
# final image is FROM scratch (no /etc/passwd), so the runtime user can only be
# referenced by id.
ARG BUILD_UID=1000
ARG RUNTIME_UID=1000

FROM ${SAVA_OPENJDK_IMAGE}:${SAVA_OPENJDK_TAG}@${SAVA_OPENJDK_DIGEST} AS jlink

ARG BUILD_UID
RUN groupadd --gid ${BUILD_UID} build \
 && useradd --uid ${BUILD_UID} --gid ${BUILD_UID} --create-home --shell /usr/sbin/nologin build \
 && mkdir -p /home/build/src \
 && chown build:build /home/build/src

ENV GRADLE_USER_HOME=/home/build/.gradle
WORKDIR /home/build/src

COPY --chown=build:build gradlew .
COPY --chown=build:build settings.gradle.kts .
COPY --chown=build:build gradle.properties .
COPY --chown=build:build gradle/modules.properties gradle/sava.properties ./gradle/
COPY --chown=build:build gradle/wrapper ./gradle/wrapper
COPY --chown=build:build gradle/plugins ./gradle/plugins

ARG CACHEBUST

# Copy source
COPY --chown=build:build sava-core ./sava-core
ARG PROJECT
COPY --chown=build:build ${PROJECT} ./${PROJECT}

# Run the build as the unprivileged user. The Gradle cache mount is owned by the
# same uid/gid so it stays writable; the GitHub Packages credentials are still
# injected as BuildKit secrets (env), never baked into a layer.
USER build
ARG MAJOR_JAVA_VERSION
RUN --mount=type=cache,target=/home/build/.gradle,uid=${BUILD_UID},gid=${BUILD_UID},sharing=locked \
    --mount=type=secret,id=ORG_GRADLE_PROJECT_savaGithubPackagesUsername,env=ORG_GRADLE_PROJECT_savaGithubPackagesUsername \
    --mount=type=secret,id=ORG_GRADLE_PROJECT_savaGithubPackagesPassword,env=ORG_GRADLE_PROJECT_savaGithubPackagesPassword \
    mkdir ./gradle/aggregation \
    && ./gradlew --console=plain --stacktrace -PnoVersionTag=true -PjavaVersion=${MAJOR_JAVA_VERSION} :${PROJECT}:image

FROM scratch

# Includes glibc's UTF-8 locale for environment/console passwords.
COPY --from=jlink /rootfs-libs/ /
# Copy the parent tree to preserve /tmp's 1777 mode for the runtime user.
COPY --from=jlink /rootfs/ /
ENV LANG=C.UTF-8

WORKDIR /sava

ARG PROJECT
COPY --from=jlink /home/build/src/${PROJECT}/build/images/${PROJECT} /sava

ARG RUNTIME_UID
USER ${RUNTIME_UID}:${RUNTIME_UID}

ENTRYPOINT [ "./bin/java" ]

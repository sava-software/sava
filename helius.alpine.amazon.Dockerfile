ARG PROJECT="sava-helius"
ARG JAVA_VERSION=26
ARG ALPINE_VERSION=3.23

FROM amazoncorretto:${JAVA_VERSION}-alpine${ALPINE_VERSION}-jdk AS jlink

WORKDIR /tmp

# Copy build context
COPY gradlew .
COPY settings.gradle.kts .
COPY gradle.properties .
COPY gradle ./gradle

# Copy source
COPY sava-core ./sava-core
COPY sava-rpc ./sava-rpc
ARG PROJECT
COPY ${PROJECT} ./${PROJECT}

ARG JAVA_VERSION
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew -PjavaVersion=${JAVA_VERSION} -PjavaVendor=AMAZON --console=plain --stacktrace --exclude-task=test :${PROJECT}:image -PnoVersionTag=true

FROM alpine:${ALPINE_VERSION}

ARG UID=6148
ARG GID=6148

RUN addgroup -g "${GID}" -S glam && \
    adduser \
        -H \
        -D \
        -S \
        -G glam \
        -u "${UID}" \
        -s "/sbin/nologin" \
        glam

ARG PROJECT
COPY --from=jlink /tmp/${PROJECT}/build/images/${PROJECT} /glam

RUN chown -R root:glam /glam && chmod 775 /glam

WORKDIR /glam

USER glam

ENTRYPOINT [ "./bin/java" ]

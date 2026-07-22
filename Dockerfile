# syntax=docker.io/docker/dockerfile:1.7-labs@sha256:b99fecfe00268a8b556fad7d9c37ee25d716ae08a5d7320e6d51c4dd83246894
ARG HOME="/home/unitycatalog"
ARG PREBUILT=false
ARG BUILD_ROOT=
ARG BUILD_HOME=

# Build stage, using Amazon Corretto jdk 17 on alpine with arm64 support
FROM amazoncorretto:17-alpine3.20-jdk@sha256:c045f0537bc890f9e61924f33f35e9667f696b4f372dad4a73861a9396b5d0b5 as base

# Dependencies are installed in $HOME/.cache by sbt
ARG USER="unitycatalog"
ARG HOME=/home/unitycatalog
ARG PREBUILT
ARG BUILD_ROOT
ARG BUILD_HOME
ENV HOME=$HOME

WORKDIR $HOME

COPY --parents dev/ build/ project/ examples/ server/ api/ clients/ version.sbt build.sbt ./

RUN apk add --no-cache bash \
 && addgroup -S "$USER" \
 && adduser -S -G "$USER" -h "$HOME" "$USER" \
 && chown -R "$USER:$USER" "$HOME"

USER root
RUN if [ "$PREBUILT" = "true" ] && [ -d "$HOME/build/ci-staging/home/.cache" ]; then \
      mkdir -p "$HOME/.cache" \
      && cp -a "$HOME/build/ci-staging/home/.cache/." "$HOME/.cache/" \
      && chown -R "$USER:$USER" "$HOME/.cache"; \
    fi
USER $USER
RUN if [ "$PREBUILT" != "true" ]; then ./build/sbt -info clean package; fi

USER root
RUN if [ "$PREBUILT" = "true" ] && [ -f "$HOME/server/target/classpath" ]; then \
      sed -i "s|${BUILD_HOME}|${HOME}|g; s|${BUILD_ROOT}|${HOME}|g" "$HOME/server/target/classpath" \
      && chown "$USER:$USER" "$HOME/server/target/classpath"; \
    fi
USER $USER

# Small runtime image
FROM alpine:3.20@sha256:a4f4213abb84c497377b8544c81b3564f313746700372ec4fe84653e4fb03805 as runtime

# Specific JAVA_HOME from Amazon Corretto
ARG JAVA_HOME="/usr/lib/jvm/default-jvm"
ARG USER="unitycatalog"
ARG HOME=/home/unitycatalog

# Copy Java from base
COPY --from=base $JAVA_HOME $JAVA_HOME

ENV HOME=$HOME \
    JAVA_HOME=$JAVA_HOME \
    PATH="${JAVA_HOME}/bin:${PATH}"

# Copy build artifacts from base stage. Use directory-to-directory COPY (not
# --parents with trailing slashes) so server/target/ is preserved under server/.
COPY --from=base $HOME/examples $HOME/examples
COPY --from=base $HOME/server $HOME/server
COPY --from=base $HOME/api $HOME/api
COPY --from=base $HOME/clients $HOME/clients
COPY --from=base $HOME/target $HOME/target
COPY --from=base $HOME/.cache $HOME/.cache

# Create a service user with read and execute permissions and write permissions of the ./etc directory
RUN <<EOF
apk add --no-cache bash
addgroup -S $USER
adduser -S -G $USER $USER
chmod -R 550 $HOME
mkdir -p $HOME/etc/
chmod -R 770 $HOME/etc/
chown -R $USER:$USER $HOME
EOF

USER $USER

# Copy remaining directories here for caching optimization
COPY --chown=$USER:$USER --parents bin/ etc/ $HOME/

WORKDIR $HOME

CMD ["./bin/start-uc-server"]

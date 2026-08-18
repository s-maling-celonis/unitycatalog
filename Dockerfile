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

# Corporate Maven mirror for the sbt launcher / Ivy (see build/sbt).
# Pass at build time: --build-arg MAVEN_PROXY_URL=$MAVEN_PROXY_URL
ARG MAVEN_PROXY_URL
ENV MAVEN_PROXY_URL=${MAVEN_PROXY_URL}

WORKDIR $HOME

COPY --parents dev/ build/ project/ examples/ server/ api/ clients/ version.sbt build.sbt ./

RUN apk add --no-cache bash \
 && addgroup -S "$USER" \
 && adduser -S -G "$USER" -h "$HOME" "$USER" \
 && chown -R "$USER:$USER" "$HOME"

USER root
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/server/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/server/target from host sbt" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/server/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/server/target/." "$HOME/server/target/" \
      && chown -R "$USER:$USER" "$HOME/server/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/examples/cli/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/examples/cli/target from host sbt" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/examples/cli/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/examples/cli/target/." "$HOME/examples/cli/target/" \
      && chown -R "$USER:$USER" "$HOME/examples/cli/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/target/control/java/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/target/control/java/target from host sbt" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/target/control/java/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/target/control/java/target/." "$HOME/target/control/java/target/" \
      && chown -R "$USER:$USER" "$HOME/target/control/java/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ -d "$HOME/build/ci-staging/prebuilt/home/.cache" ]; then \
        CACHE_SRC="$HOME/build/ci-staging/prebuilt/home/.cache"; \
      elif [ -d "$HOME/build/ci-staging/home/.cache" ]; then \
        CACHE_SRC="$HOME/build/ci-staging/home/.cache"; \
      else \
        echo "PREBUILT build requires coursier cache under build/ci-staging" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/.cache" \
      && cp -a "$CACHE_SRC/." "$HOME/.cache/" \
      && chown -R "$USER:$USER" "$HOME/.cache"; \
    fi
# Staging tree is only needed to enter the build context. Drop it after
# promoting artifacts so the image does not keep two copies of the cache.
RUN rm -rf /home/unitycatalog/build/ci-staging
USER $USER
RUN if [ "$PREBUILT" != "true" ]; then ./build/sbt -info clean package; fi

USER root
RUN if [ "$PREBUILT" = "true" ] && [ -f "$HOME/server/target/classpath" ]; then \
      sed -i "s|${BUILD_HOME}|${HOME}|g; s|${BUILD_ROOT}|${HOME}|g" "$HOME/server/target/classpath" \
      && chown "$USER:$USER" "$HOME/server/target/classpath"; \
    elif [ "$PREBUILT" = "true" ]; then \
      echo "PREBUILT build requires $HOME/server/target/classpath" >&2; \
      exit 1; \
    fi
RUN if [ "$PREBUILT" = "true" ] && [ -f "$HOME/examples/cli/target/classpath" ]; then \
      sed -i "s|${BUILD_HOME}|${HOME}|g; s|${BUILD_ROOT}|${HOME}|g" "$HOME/examples/cli/target/classpath" \
      && chown "$USER:$USER" "$HOME/examples/cli/target/classpath"; \
    elif [ "$PREBUILT" = "true" ]; then \
      echo "PREBUILT build requires $HOME/examples/cli/target/classpath" >&2; \
      exit 1; \
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
# Root target/ exists only after in-Docker sbt (non-PREBUILT). Coursier cache
# lives at $HOME/.cache after base RUN (PREBUILT) or in-Docker sbt (non-PREBUILT).
RUN --mount=type=bind,from=base,source=/home/unitycatalog,target=/base,readonly <<'EOF'
set -e
if [ -d /base/.cache ]; then
  cp -a /base/.cache /home/unitycatalog/.cache
elif [ -d /base/build/ci-staging/prebuilt/home/.cache ]; then
  cp -a /base/build/ci-staging/prebuilt/home/.cache /home/unitycatalog/.cache
elif [ -d /base/build/ci-staging/home/.cache ]; then
  cp -a /base/build/ci-staging/home/.cache /home/unitycatalog/.cache
else
  echo "No coursier cache found in base image" >&2
  exit 1
fi
if [ -d /base/target ]; then
  cp -a /base/target /home/unitycatalog/target
fi
EOF

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

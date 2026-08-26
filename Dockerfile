# syntax=docker.io/docker/dockerfile:1.7-labs@sha256:b99fecfe00268a8b556fad7d9c37ee25d716ae08a5d7320e6d51c4dd83246894
ARG HOME="/home/unitycatalog"
ARG PREBUILT=false
ARG BUILD_ROOT=
ARG BUILD_HOME=

# Build stage, using Amazon Corretto jdk 17 on alpine with arm64 support
FROM amazoncorretto:17-alpine3.20-jdk@sha256:c045f0537bc890f9e61924f33f35e9667f696b4f372dad4a73861a9396b5d0b5 as base

# Dependencies are installed in $HOME/.m2 by Maven
ARG USER="unitycatalog"
ARG HOME=/home/unitycatalog
ARG PREBUILT
ARG BUILD_ROOT
ARG BUILD_HOME
ENV HOME=$HOME
ENV MAVEN_OPTS="-Duser.home=$HOME"

# The JVM derives user.home from the passwd entry and ignores HOME, so Maven
# would otherwise use /root/.m2. Maven writes absolute jar paths into
# server/target/classpath, so this directory must resolve identically in both
# stages or the server starts with some classpath entries missing.

# Corporate Maven mirror. Pass at build time: --build-arg MAVEN_PROXY_URL=$MAVEN_PROXY_URL
ARG MAVEN_PROXY_URL
ENV MAVEN_PROXY_URL=${MAVEN_PROXY_URL}

WORKDIR $HOME

# build/ carries Repo Depot PREBUILT trees (server/cli/control-api targets + slim .m2).
# Omit it and PREBUILT=true fails: those paths never enter the image.
COPY --parents .mvn/ dev/ build/ control-api/ server-shaded/ examples/ server/ api/ clients/ connectors/ integration-tests/ pom.xml ./

RUN apk add --no-cache bash maven \
 && addgroup -S "$USER" \
 && adduser -S -G "$USER" -h "$HOME" "$USER" \
 && chown -R "$USER:$USER" "$HOME"

USER root
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/server/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/server/target from host Maven" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/server/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/server/target/." "$HOME/server/target/" \
      && chown -R "$USER:$USER" "$HOME/server/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/examples/cli/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/examples/cli/target from host Maven" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/examples/cli/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/examples/cli/target/." "$HOME/examples/cli/target/" \
      && chown -R "$USER:$USER" "$HOME/examples/cli/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ ! -d "$HOME/build/ci-staging/prebuilt/control-api/target" ]; then \
        echo "PREBUILT build requires $HOME/build/ci-staging/prebuilt/control-api/target from host Maven" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/control-api/target" \
      && cp -a "$HOME/build/ci-staging/prebuilt/control-api/target/." "$HOME/control-api/target/" \
      && chown -R "$USER:$USER" "$HOME/control-api/target"; \
    fi
RUN if [ "$PREBUILT" = "true" ]; then \
      if [ -d "$HOME/build/ci-staging/prebuilt/home/.m2" ]; then \
        CACHE_SRC="$HOME/build/ci-staging/prebuilt/home/.m2"; \
      elif [ -d "$HOME/build/ci-staging/home/.m2" ]; then \
        CACHE_SRC="$HOME/build/ci-staging/home/.m2"; \
      else \
        echo "PREBUILT build requires Maven cache under build/ci-staging" >&2; \
        exit 1; \
      fi; \
      mkdir -p "$HOME/.m2" \
      && cp -a "$CACHE_SRC/." "$HOME/.m2/" \
      && chown -R "$USER:$USER" "$HOME/.m2"; \
    fi
# Staging tree is only needed to enter the build context. Drop it after
# promoting artifacts so the image does not keep two copies of the cache.
RUN rm -rf /home/unitycatalog/build/ci-staging
USER $USER
RUN if [ "$PREBUILT" != "true" ]; then mvn -q -pl server,examples/cli -am package -DskipTests; fi

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
COPY --from=base $HOME/control-api $HOME/control-api
# Root target/ exists after in-Docker Maven (generated OpenAPI). Maven cache
# lives at $HOME/.m2 after base RUN (PREBUILT) or in-Docker Maven (non-PREBUILT).
RUN --mount=type=bind,from=base,source=/home/unitycatalog,target=/base,readonly <<'EOF'
set -e
if [ -d /base/.m2 ]; then
  cp -a /base/.m2 /home/unitycatalog/.m2
elif [ -d /base/build/ci-staging/prebuilt/home/.m2 ]; then
  cp -a /base/build/ci-staging/prebuilt/home/.m2 /home/unitycatalog/.m2
elif [ -d /base/build/ci-staging/home/.m2 ]; then
  cp -a /base/build/ci-staging/home/.m2 /home/unitycatalog/.m2
else
  echo "No Maven cache found in base image" >&2
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

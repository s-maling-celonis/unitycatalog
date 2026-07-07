# Docker integration tests

Integration tests for Unity Catalog against the **celospark** Docker stack. This repo does
not ship its own compose files for MinIO, OAuth, Spark, or UC — use
[celospark/docker](https://github.com/celonis/celospark/tree/main/docker) instead.

## Prerequisites

1. Clone [celospark](https://github.com/celonis/celospark) as a sibling of this repo
   (`../celospark`), or set `CELOSPARK_DOCKER_DIR` to your `celospark/docker` path.

2. Start the UC stack from celospark:

```sh
cd ../celospark/docker
cp example.env .env
# Edit .env: GITHUB_TOKEN, UC_SERVER_IMAGE*, etc.
docker compose -f compose.uc.yml -f uc/oidc/compose.yaml up -d --build
```

3. Optional host entry for manual OAuth in a browser (automated tests do **not** need this):

```sh
echo '127.0.0.1 dev.dev.celonis.cloud' | sudo tee -a /etc/hosts
```

## Run tests

```sh
./docker/tests/run-tests.sh
./docker/tests/run-tests.sh io.unitycatalog.docker.tests.BootstrapTenantTest
```

Defaults assume celospark port mapping:

| Setting | Default |
|---------|---------|
| UC API | `http://localhost:8181` |
| Spark Thrift | `localhost:10000` |
| OAuth connect URL | `http://127.0.0.1:9010` (local Envoy) |
| OAuth `Host` header | `dev.dev.celonis.cloud` (tenant virtual host) |
| Spark → UC URI | `http://unitycatalog:8080` (Docker network) |

Tests reach OAuth via `127.0.0.1:9010` and send the tenant as a `Host` header, so `/etc/hosts`
is not required for the JUnit suite.

Override with `UC_SERVER_URL`, `UC_OAUTH_CONNECT_URL`, `UC_OAUTH_HOST`, `UC_ADMIN_TOKEN`, etc.

## Server image

UC runs from the ECR image configured in celospark `example.env` (`UC_SERVER_IMAGE`). Build
new images via celonis/unitycatalog RepoDepot CI — not from this repo's root `Dockerfile`
during integration testing.

## Further reading

- Celospark stack: `celospark/docker/README.md`
- OAuth overlay: `celospark/docker/uc/oidc/README.md`
- OSS quickstart (separate from celospark): root `compose.yaml` and `docs/docker_compose.md`

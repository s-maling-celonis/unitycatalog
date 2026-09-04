# Docker Compose

To start Unity Catalog in Docker Compose in one command, install the latest
version of [Docker Desktop](https://www.docker.com/products/docker-desktop/) and
run the following from the project's root directory:

```sh
docker compose up -d
```

This starts the Unity Catalog server and UI. You can access the UI at
`http://localhost:3000` and the server at `http://localhost:8080`. Clients like
DuckDB or Spark running on the host machine will be able to interact on those
ports with the containers running Unity Catalog.

From the host, list the sample tables through the REST API:

```sh
curl -s 'http://localhost:8080/api/2.1/unity-catalog/tables?catalog_name=unity&schema_name=default'
```

To remove the containers and persistent volumes, run the following from the host machine:

```sh
docker compose down --volumes --remove-orphans
```

Refer to the main [Quickstart](quickstart.md) for more examples of how to
interact with the catalog.

## Configurations

Docker Compose is configured in the `./compose.yaml` file.

The configuration will create a bind mount to the local files in `./etc/conf`.
The UC server can be configured by editing the configuration files on the host,
and the mount will reflect the changes in the container.

The configuration will also create a persistent, named volume to store the
server's data. This will persist between restarts of the container. See the
[deployment](./server/deployment.md) page for more details on how to configure other
databases like Postgres.

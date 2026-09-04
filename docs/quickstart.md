# Quickstart

This quickstart shows how to run Unity Catalog on localhost which is great for experimentation and testing.

## How to start the Unity Catalog server

Start by cloning the open source Unity Catalog GitHub repository:

```sh
git clone git@github.com:unitycatalog/unitycatalog.git
```

> To start Unity Catalog in Docker, refer to the [Docker
> Compose docs](docker_compose.md).

To run Unity Catalog, you need **Java 17** installed on your machine.  You can
always run the `java --version` command to verify that you have the right
version of Java installed such as the following example output.

```sh
% java --version
openjdk 17.0.12 2024-07-16
OpenJDK Runtime Environment Homebrew (build 17.0.12+0)
OpenJDK 64-Bit Server VM Homebrew (build 17.0.12+0, mixed mode, sharing)
```

From the repository root, build the server artifacts. The startup script can trigger this automatically on first run,
but building explicitly is recommended:

```sh
mvn package
```

From the repository root, run `bin/start-uc-server` to instantiate the server. Here is what you
should see:

```console
################################################################### 
#  _    _       _ _            _____      _        _              #
# | |  | |     (_) |          / ____|    | |      | |             #
# | |  | |_ __  _| |_ _   _  | |     __ _| |_ __ _| | ___   __ _  #
# | |  | | '_ \| | __| | | | | |    / _` | __/ _` | |/ _ \ / _` | #
# | |__| | | | | | |_| |_| | | |___| (_| | || (_| | | (_) | (_| | #
#  \____/|_| |_|_|\__|\__, |  \_____\__,_|\__\__,_|_|\___/ \__, | #
#                      __/ |                                __/ | #
#                     |___/      v0.5.0-SNAPSHOT           |___/  #
###################################################################
```

!!! note "Server version string"
    Released builds display `v0.5.0`. When you build from the `main` branch, the banner shows
    `v0.5.0-SNAPSHOT`.

Well, that was pretty easy!

## Verify Unity Catalog server is running

Let’s create a new Terminal window and verify that the Unity Catalog server is running.

Unity Catalog has a few built-in tables that are great for quick experimentation. List tables in the `unity` catalog
and `default` schema with the REST API:

```sh
curl -s 'http://127.0.0.1:8080/api/2.1/unity-catalog/tables?catalog_name=unity&schema_name=default'
```

You should see `marksheet`, `marksheet_uniform`, `numbers`, and `user_countries`. See the
[REST API docs](usage/api/index.md) for other catalog operations. Query table data with DuckDB or Spark.

## Unity Catalog structure

Unity Catalog stores all assets in a 3-level namespace:

1. catalog
2. schema
3. assets like tables, volumes, functions, etc.

![UC 3 Level](./assets/images/uc-3-level.png)

Here's an example Unity Catalog instance:

![UC Example Catalog](./assets/images/uc_example_catalog.png)

This Unity Catalog instance contains a single catalog named `cool_stuff`.

The `cool_stuff` catalog contains two schema: `thing_a` and `thing_b`.

`thing_a` contains a Delta table, a function, and a Lance volume. `thing_b` contains two Delta tables.

Unity Catalog provides a nice organizational structure for various datasets.

## List catalogs, schemas, and tables with the REST API

The Unity Catalog server is pre-populated with a few sample catalogs, schemas, Delta tables, etc.

```sh
curl -s 'http://127.0.0.1:8080/api/2.1/unity-catalog/catalogs'
curl -s 'http://127.0.0.1:8080/api/2.1/unity-catalog/schemas?catalog_name=unity'
curl -s 'http://127.0.0.1:8080/api/2.1/unity-catalog/tables?catalog_name=unity&schema_name=default'
```

You should see a catalog named `unity`, a schema named `default`, and several tables including `numbers`.
Create, update, and delete operations are documented in the [REST API](usage/api/index.md). Query table data with
DuckDB or Spark.

## Interact with the Unity Catalog UI

![UC UI](./assets/images/uc-ui.png)

This fork does not include the OSS UI sources. `docker compose up` starts the [published UI image](https://hub.docker.com/r/unitycatalog/unitycatalog-ui) at `http://localhost:3000`. To build the UI from source, use [upstream `ui/`](https://github.com/unitycatalog/unitycatalog/tree/main/ui).

## Manage models in Unity Catalog using MLflow

Unity Catalog supports the management and governance of ML models as securable assets. Starting with
[MLflow 2.16.1](https://mlflow.org/releases/2.16.1), MLflow offers integrated support for using Unity Catalog as the
backing resource for the MLflow model registry.  What this means is that with the MLflow client, you will be able to
interact directly with your Unity Catalog service for the creation and access of registered models.

### Setup MLflow for usage with Unity Catalog

Before registering models, configure managed model storage in `etc/conf/server.properties` and restart the UC server
if it is already running:

```properties
storage-root.models=file:/tmp/ucroot
```

```sh
mkdir -p /tmp/ucroot
```

In your desired development environment, install MLflow 2.16.1 or higher:

```sh
pip install mlflow
```

The installation of MLflow includes the MLflow CLI tool, so you can start a local MLflow server with UI by running the
command below in your terminal:

```sh
mlflow ui
```

It will generate logs with the IP address, for example:

```console
[2023-10-25 19:39:12 -0700] [50239] [INFO] Starting gunicorn 20.1.0
[2023-10-25 19:39:12 -0700] [50239] [INFO] Listening at: http://127.0.0.1:5000 (50239)
```

Next, from within a python script or shell, import MLflow and set the tracking URI and the registry URI.

```python
import mlflow

mlflow.set_tracking_uri("http://127.0.0.1:5000")
mlflow.set_registry_uri("uc:http://127.0.0.1:8080")
```

At this point, your MLflow environment is ready for use with the newly started MLflow tracking server and the
Unity Catalog server acting as your model registry.

### Train your model and register it into Unity Catalog

You can quickly train a test model and validate that the MLflow/Unity catalog integration is fully working.

```python
import os
from sklearn import datasets
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
import pandas as pd

X, y = datasets.load_iris(return_X_y=True, as_frame=True)
X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42)

with mlflow.start_run():
    # Train a sklearn model on the iris dataset
    clf = RandomForestClassifier(max_depth=7)
    clf.fit(X_train, y_train)
    # Take the first row of the training dataset as the model input example.
    input_example = X_train.iloc[[0]]
    # Log the model and register it as a new version in Unity Catalog.
    mlflow.sklearn.log_model(
        sk_model=clf,
        artifact_path="model",
        # The signature is automatically inferred from the input example and its predicted output.
        input_example=input_example,
        registered_model_name="unity.default.iris",
    )

loaded_model = mlflow.pyfunc.load_model(f"models:/unity.default.iris/1")
predictions = loaded_model.predict(X_test)
iris_feature_names = datasets.load_iris().feature_names
result = pd.DataFrame(X_test, columns=iris_feature_names)
result["actual_class"] = y_test
result["predicted_class"] = predictions
result[:4]
```

This code snippet will create a registered model `unity.default.iris` and log the trained model as model version 1. It
then loads the model from the Unity Catalog server, and performs batch inference on the test set using the loaded model.

The results can be seen in the Unity Catalog UI at [http://localhost:3000](http://localhost:3000), per the instructions
in the [Interact with the Unity Catalog tutorial](https://github.com/unitycatalog/unitycatalog?tab=readme-ov-file#interact-with-the-unity-catalog-ui).

![UC UI models](./assets/images/uc_ui_models.png)

## APIs and Compatibility

- Open API specification: See the [Unity Catalog Rest API](https://docs.unitycatalog.io/swagger-docs/).
- Compatibility and stability: The APIs are currently evolving and should not be assumed to be stable.

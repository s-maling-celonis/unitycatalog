# Documentation for Unity Catalog

User documentation lives as Markdown in this directory. This fork does not build or publish a MkDocs site; edit the files here and review them as Markdown.

When adding an integrations page, also link it from `integrations/index.md`.

## Guidelines for Markdown formatting

Use [markdownlint](https://github.com/DavidAnson/markdownlint) for a common Markdown style. Rules are defined in `.markdownlint.yaml` at the repository root.

markdownlint can be executed locally by installing it directly or via `npx`:

```sh
npx markdownlint-cli docs/README.md
```

### Formatting code snippets within a list

If code snippets are present within a list, they should be aligned with the content of the list. The following section
shows an example of a proper formatting:

- The tarball generated in the `target` directory can be unpacked using the following command:

    ```sh
    tar -xvf unitycatalog-<version>.tar.gz
    ```

- Unpacking the tarball will create the following directory structure:

    ```console
    unitycatalog-<version>
    ├── bin
    │   ├── start-uc-server
    │   └── uc
    ├── etc
    │   ├── conf
    │   ├── data
    │   ├── db
    │   └── logs
    └── jars
    ```

Please note that this ensures that the code snippet is aligned with the text in the bullet points. The final result
should look similar to the following

![Markdown code snippet alignment in a list](./assets/images/markdown-code-snippet-list-aligned.png)

In comparison an invalid alignment looks like this

![Markdown code snippet wrong alignment in a list](./assets/images/markdown-code-snippet-list-unaligned.png)

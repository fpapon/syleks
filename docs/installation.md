# Installation

Syleks is a working-source MVP. The supported starting point is a local source build. Node.js is only needed for documentation contributors; it is not needed to build or run the Java CLI.

## Requirements

- Java 25 and Maven 3.9 or newer.
- An interactive terminal with at least 35 columns and 15 rows. Truecolor and a Unicode monospace font are recommended.
- A working AI provider, such as local Ollama. Tool calls require model support for structured function calls.
- A filesystem supporting the application's protected storage operations; review [platform limits](storage-and-privacy.md).

Shell tools additionally need `/bin/bash` and an available [command isolation backend](command-sandbox.md). You can have a conversation without enabling shell tools.

## Build from source

```sh
git clone https://github.com/fpapon/syleks.git
cd syleks
java -version
mvn -version
mvn clean package
export PATH="$PWD/bin:$PATH"
syleks --help
```

The build runs unit tests and creates `target/syleks-0.1.0-SNAPSHOT.jar`. The help output begins with `Syleks — persistent local AI sessions`.

Keep the checkout and built JAR in place: `bin/syleks` locates that JAR relative to itself. To use the command in future terminals, add the checkout's absolute `bin` directory to your shell's `PATH`.

## Launch without changing PATH

```sh
java --enable-native-access=ALL-UNNAMED -jar target/syleks-0.1.0-SNAPSHOT.jar --help
```

Use an absolute JAR path when launching from another project. Run Syleks from the project you want the conversation to belong to.

An optional native executable and user-level installer are described in the [repository build instructions](https://github.com/fpapon/syleks#native-executable-with-apache-arthur). This documentation does not assume a prebuilt release binary is available.

## Next step

Continue with the [five-minute quickstart](quickstart.md). If Java or Maven fails, check [troubleshooting](troubleshooting.md).

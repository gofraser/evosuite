# ![EvoSuite](docs/images/evosuite-wordmark.png)

[![Development CI](https://github.com/gofraser/evosuite/actions/workflows/ci.yml/badge.svg?branch=master)](https://github.com/gofraser/evosuite/actions/workflows/ci.yml?query=branch%3Amaster)

EvoSuite automatically generates JUnit test suites for Java classes, targeting code coverage criteria such as branch coverage. It uses an evolutionary search to find tests, minimises the resulting suite, and adds regression assertions that capture the current behaviour of the classes under test.

## Downloads

[GitHub Releases](https://github.com/EvoSuite/evosuite/releases/latest) has the executable JAR and the matching standalone runtime JAR. For the Maven plugin and runtime published on Maven Central, check the versions there. The examples below use the current source checkout.

- [EvoSuite Maven plugin](https://central.sonatype.com/artifact/org.evosuite.plugins/evosuite-maven-plugin)
- [EvoSuite standalone runtime](https://central.sonatype.com/artifact/org.evosuite/evosuite-standalone-runtime)

## Quick start

Use JDK 17 for this example. From the root of the EvoSuite checkout, [build the JARs](#building-evosuite). Set `version` to the `revision` in the root `pom.xml` if it differs from the one below. Then compile a small class and ask EvoSuite to cover its branches.

```sh
version=1.2.1-SNAPSHOT
engine="$PWD/master/target/evosuite-master-$version.jar"
runtime="$PWD/standalone_runtime/target/evosuite-standalone-runtime-$version.jar"
cd "$(mktemp -d)"

cat > Calculator.java <<'EOF'
package example;

public class Calculator {
    public int sign(int value) {
        return value < 0 ? -1 : 1;
    }
}
EOF

mkdir -p classes test-classes
javac -d classes Calculator.java
java -jar "$engine" -class example.Calculator -projectCP classes \
  -seed 17 -criterion BRANCH -Dsearch_budget=3
```

EvoSuite writes `evosuite-tests/example/Calculator_ESTest.java`. The current CLI generates JUnit 5 tests by default. Run the generated tests with JUnit and the EvoSuite runtime from the same build.

```sh
junit=junit-platform-console-standalone-1.14.4.jar
curl -fLO "https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.14.4/$junit"
javac -cp "classes:$runtime:$junit" -d test-classes evosuite-tests/example/*.java
java "-javaagent:$runtime" -jar "$junit" execute \
  --class-path "classes:test-classes:$runtime" --select-class example.Calculator_ESTest
```

For your own project, give `-projectCP` the compiled classes and their dependencies, using the usual Java classpath separator (`:` on Unix and `;` on Windows). Use `-class` for a fully qualified class name, `-prefix` for a package and its subpackages, or `-target` for a JAR or directory of class files. Run `java -jar "$engine" -help` for the options available in your build.

## Requirements

- The standard generator needs a JDK (11 or newer) to compile and check the tests it generates. For Java 8, use the [compatibility CLI](#java-8-compatibility-cli).
- The core runtime targets Java 8. Use the matching compatibility runtime for tests that use functional mocks on Java 8.
- EvoSuite uses the Security Manager to sandbox the code under test. On JDK 18–23, pass `-Djava.security.manager=allow` before `-jar` to enable the sandbox. Java 24 permanently disabled the Security Manager, so EvoSuite runs with the sandbox off and prints a warning.

## Building EvoSuite

Use Maven 3.6.3 or later and JDK 11 or newer. To build the JARs, run the following from the repository root.

```sh
mvn clean package -DskipTests
```

The executable JAR is `master/target/evosuite-master-<version>.jar`. The matching test runtime is `standalone_runtime/target/evosuite-standalone-runtime-<version>.jar`. Run `mvn verify` to build and run the tests.

Release builds need Maven 3.9.2 or later and JDK 17 or newer to include the `RandomGenerator` mocks. See [CONTRIBUTING.md](CONTRIBUTING.md) for build, test, and release instructions.

### Java 8 compatibility CLI

Build with JDK 11 or newer, then run the resulting generator with JDK 8.

```sh
mvn -Pjava8-cli -Drevision=java8-SNAPSHOT -DskipTests clean package
JAVA_HOME=/path/to/jdk8 /path/to/jdk8/bin/java -jar master/target/evosuite-master-java8-SNAPSHOT.jar -help
```

Use `standalone_runtime/target/evosuite-standalone-runtime-java8-SNAPSHOT.jar` when compiling and running the generated tests. The compatibility profile uses Logback 1.3 and Mockito 4, which are out of active support.

## Maven, Eclipse, and IntelliJ plugins

The [Maven plugin](plugins/maven/README.md) requires Maven 3.9 or later. Add its `setup` goal and the matching standalone runtime test dependency as shown in the plugin guide. Run `mvn evosuite:generate` to generate tests in `src/test/evosuite`, then `mvn test` to run them. Later runs generate tests for classes that have no suite yet or whose bytecode, superclass bytecode, or EvoSuite version has changed.

The [Eclipse plugin](plugins/eclipse/README.md) runs EvoSuite in a separate JVM. The plugin guide explains how to build the p2 update site with Tycho and install it into Eclipse.

The [IntelliJ plugin](plugins/intellij/README.md) requires IntelliJ IDEA 2025.3 or later. It bundles EvoSuite and its runtime and runs generation in a separate JVM on the module's JDK. The plugin guide explains how to build the plugin ZIP with Gradle and install it from disk.

## Docker

The repository's Dockerfile builds EvoSuite from source and runs it on Temurin 17. Build the image from the repository root.

```sh
docker build -t evosuite/evosuite:local .
```

The image build skips tests. Pass `--build-arg SKIP_TESTS=false` to run the tests included in Maven's `package` phase. [Docker Hub](https://hub.docker.com/r/evosuite/evosuite/tags) also has images for older releases.

Run the image from the project directory. Docker mounts it at `/evosuite`, so EvoSuite reads the project and writes its results there. The user ID keeps the files owned by the user who started the container.

```sh
docker run --rm -it -u "$(id -u)" -v "$PWD:/evosuite" evosuite/evosuite:local -help
```

Replace `-help` with the usual EvoSuite arguments. Use `-d` in place of `-it` to run in the background.

### Large-scale experiment runner

Build the experiment image from the repository root. To use a particular EvoSuite version, check out its tag first.

```sh
docker build -f Dockerfile.experiment -t evosuite/evosuite:experiment .
```

Prepare a directory with the project JARs and two CSV files. Start with a directory that has no `results/` folder.

```text
projects/project1/project1.jar
projects/project1/dependency.jar
configurations.csv
projects.csv
```

`configurations.csv` lists the EvoSuite configurations to compare.

```csv
configuration_name,user_configuration
default60,-generateMOSuite -Dalgorithm=DynaMOSA -Dsearch_budget=60 -Dassertion_timeout=120 -Dminimization_timeout=120
default120,-generateMOSuite -Dalgorithm=DynaMOSA -Dsearch_budget=120 -Dassertion_timeout=120 -Dminimization_timeout=120
```

`projects.csv` names the project directory and the fully qualified class to test.

```csv
project_name,class
project1,com.project1.Application
```

Run the experiment image from that directory.

```sh
docker run --rm -it -u "$(id -u)" -v "$PWD:/evosuite" evosuite/evosuite:experiment \
  configurations.csv projects.csv
```

The runner supplies the classpath, class name, seed, and output paths. If a project needs a specific classpath, put it on the first line of `projects/<project>/CLASSPATH`. For each configuration, project, and class, the runner writes `logs/<round>`, `reports/<round>/`, and `tests/<round>/` under `results/<configuration>/<project>/<class>/`. It replaces dots in the class name with underscores.

Keep the `SEEDS` file to reuse the same seeds for a repeat run. The runner accepts `-r` for the number of rounds, `-p` for parallel runs, `-m` for client memory in MB, `-t` for the timeout, and `-s` for a different seeds file. Pass `-h` to see the defaults.

## Using a language model

EvoSuite can use a language model to seed the search or to suggest changes to tests during the search. LLM support is off by default. Set `llm_provider` to `OPENAI`, `ANTHROPIC`, or `OLLAMA` and enable the feature you want to use. For example, pass `-Dllm_provider=OPENAI -Dllm_seed_initial_population=true` to seed the initial population with OpenAI. For an API that needs a key, set `EVOSUITE_LLM_API_KEY` in the environment. Run `java -jar "$engine" -listParameters` to see the `llm_*` settings. The [Maven plugin guide](plugins/maven/README.md#llm-support) shows how to configure a provider in a Maven project.

## License and citation

EvoSuite is released under the [GNU Lesser General Public License, version 3 or later](LICENSE.txt).

If you use EvoSuite in your research, please cite Gordon Fraser and Andrea Arcuri, [EvoSuite: Automatic Test Suite Generation for Object-Oriented Software](https://doi.org/10.1145/2025113.2025179), ESEC/FSE 2011. See the project website for [other publications](https://www.evosuite.org/publications/).

## More information

- [Documentation](https://www.evosuite.org/documentation/)
- [Report an issue](https://github.com/EvoSuite/evosuite/issues)

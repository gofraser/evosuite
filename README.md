# What is EvoSuite?

[![CI](https://github.com/EvoSuite/evosuite/actions/workflows/ci.yml/badge.svg)](https://github.com/EvoSuite/evosuite/actions/workflows/ci.yml)


EvoSuite automatically generates JUnit test suites for Java classes, targeting code coverage criteria such as branch coverage. It uses an evolutionary approach based on a genetic algorithm to derive test suites. To improve readability, the generated unit tests are minimized, and regression assertions that capture the current behavior of the tested classes are added to the tests.

# Requirements

- To run EvoSuite, use a JDK (not just a JRE), because generated tests are compiled.
- To build EvoSuite, use JDK 17 (21 also works) and Maven 3.6.3 or later. All modules are compiled for Java 8 (`java.release` 8), and the `evosuite-runtime` module has to stay Java 8 compatible.
- EvoSuite's sandbox needs a Java security manager. It is available on Java 8 to 17; on Java 18 to 23 only if the JVM is started with `-Djava.security.manager=allow`; Java 24 and later removed the security manager, and the sandbox is then switched off, with a warning.

# Using EvoSuite

There are different ways to use EvoSuite:

### EvoSuite on the command line

EvoSuite comes as an executable jar file which you can call as follows:

```java -jar evosuite.jar <options>```

To generate a test suite using EvoSuite, use the following command:

```java -jar evosuite.jar <target> [options]```

The target can be a class:

```-class <ClassName>```

or a package prefix, in which case EvoSuite tries to generate a test
suite for each class in the classpath that matches the prefix:

```-prefix <PrefixName>```

or a classpath entry, in which case EvoSuite tries to generate a test
suite for each class in the given classpath entry:

```-target <jar file or directory>```

The most important option is to set the classpath, using standard Java
classpath syntax:

```-projectCP <classpath>```

For more options, see the
[Documentation](https://www.evosuite.org/documentation/commandline/)

```java -jar evosuite.jar -help```

### EvoSuite on Docker

The repository contains a `Dockerfile` that builds EvoSuite from the sources and runs it on a Temurin 17 JDK. Build the image locally:

```
git clone https://github.com/EvoSuite/evosuite.git
cd evosuite
docker build . --tag evosuite/evosuite:local
```

(Tests are skipped in the image build; pass `--build-arg SKIP_TESTS=false` to run them.) There is also an [EvoSuite repository on Docker Hub](https://hub.docker.com/r/evosuite/evosuite); check which tags it offers before pulling.

EvoSuite can be called as follows:

```docker run -it -u ${UID} -v ${PWD}:/evosuite evosuite/evosuite:local <options>```

It assumes that the project to be tested is located in the current directory the command is called from. The current directory, ```${PWD}```, is mapped to the ```/evosuite``` directory inside the container. This location is also the working directory of EvoSuite. All results will be mapped back to the directory on the host system. The ```-u ${UID}``` makes sure that the results have the same file ownership as the user initiating the command.

When EvoSuite needs to be run in the background, you can use ```-d``` instead of ```-it```.

The ```<options>``` are the same as they would be when EvoSuite is called from the command line.


#### Large-scale experiment runner

The repository also contains `Dockerfile.experiment`, an image to run large-scale experiments easily. Build it locally:

```
git clone https://github.com/EvoSuite/evosuite.git
cd evosuite
git checkout <tag> # optional: build a specific release
docker build -f Dockerfile.experiment . --tag evosuite/evosuite:experiment
```

The experiment runner can be called as follows:

```docker run -it -u ${UID} -v ${PWD}:/evosuite evosuite/evosuite:experiment [<options>] <configurations_file> <projects_file>```

and has the following options:
```
-h                       print help and exit
-m <memory>              memory limit (MB) for the EvoSuite client process (default: 2500)
-p <parallel_instances>  limit for the number of parallel executions (default: 1)
-r <rounds>              number of rounds to execute each experiment (default: 1)
-s <seeds_file>          file with the seeds for the executions of the experiment (default: SEEDS)
-t <timeout>             amount of time before EvoSuite process is killed (default: 10m)
```

It assumes that the current working directory has a folder named ```projects```, which contains a sub-directory for each project under test containing all jar files for that project. The current directory should also contain two csv files:

- One for the different configurations of the experiment, with two columns for the ```configuration_name``` and the ```user_configuration```
- One for the classes per project, with two columns for the ```project_name``` (which should be the same as the folder under ```projects```) and the ```class``` (which should be the full class path)

An example of the directory structure would be:

```
./projects/<project1_name>/<first jar file of project 1>
./projects/<project1_name>/<second jar file of project 1>
./projects/<project2_name>/<jar file of project 2>
./configurations.csv
./projects.csv
```

configurations.csv:
```
configuration_name,user_configuration
default60,-generateMOSuite -Dalgorithm=DynaMOSA -Dsearch_budget=60 -Dassertion_timeout=120 -Dminimization_timeout=120
default120,-generateMOSuite -Dalgorithm=DynaMOSA -Dsearch_budget=120 -Dassertion_timeout=120 -Dminimization_timeout=120
```

The configuration for the projectCP, class, seed, and output locations are already provided by the image.

projects.csv:
```
project_name,class
<project1_name>,com.project1.application
```

The image will put the output of the experiment in the following locations (inside the current directory):

- ```./results/<configuration_name>/<project_name>/<class_name>/logs/<round>```
- ```./results/<configuration_name>/<project_name>/<class_name>/reports/<round>/```
- ```./results/<configuration_name>/<project_name>/<class_name>/tests/<round>/```

When you run the image, it will automatically generate a SEEDS file in the current directory containing the seeds it used for the experiment. When you want to replicate the experiment, you can put that file back in the same place and instead of creating new seeds the image will now use those seeds.

When you want to set a manual class path instead of letting the script determine one for you, a file called, 'CLASSPATH' can be put inside the individual project folders where the first line is the class path for that project.

When EvoSuite needs to be run in the background, you can use ```-d``` instead of ```-it```.

### EvoSuite plugin for Eclipse

The Eclipse plugin is built from [plugins/eclipse](plugins/eclipse/) as a standalone Tycho build, which runs EvoSuite in a separate process. See [plugins/eclipse/README.md](plugins/eclipse/README.md) for how to use it, how to build it, and how to install the resulting p2 update site.

### EvoSuite plugin for Maven

EvoSuite has a Maven Plugin that can be used to generate new test cases as part of the build. This has at least the following advantages:

1. Can run EvoSuite from Continuous Integration servers (eg a CI server) with minimal configuration overheads
2. Generated tests can be put directly on the classpath of the system based on the pom.xml files
3. No need to install EvoSuite on local machine (Maven will take care of it automatically)

```xml
<plugin>
  <groupId>org.evosuite.plugins</groupId>
  <artifactId>evosuite-maven-plugin</artifactId>
  <version>${evosuite.version}</version>
  <executions>
    <execution>
      <goals>
        <goal>setup</goal>
      </goals>
    </execution>
  </executions>
</plugin>
```

Tests are then generated with `mvn evosuite:generate`. The project also needs `org.evosuite:evosuite-standalone-runtime` in the same version as a test dependency.

Generated tests are written to `src/test/evosuite`, which the plugin adds to the build, and later runs only
regenerate tests for classes that changed. See [plugins/maven/README.md](plugins/maven/README.md) for setup
and options.

# Getting EvoSuite

The current release of EvoSuite (main EvoSuite jar file and plugins) is available for download at <https://www.evosuite.org/downloads/>.

To access the source code, use the github repository:

```git clone https://github.com/EvoSuite/evosuite.git```


# Building EvoSuite

EvoSuite uses [Maven](https://maven.apache.org/).

To build EvoSuite on the command line, install Maven (3.6.3 or later) and a JDK 17, and call

```mvn package -DskipTests```

This creates the executable jar with all dependencies at `master/target/evosuite-master-<version>.jar`. Run `mvn verify` to also run the tests. The build version is set by the `revision` property in the root `pom.xml`; a release build overrides it with `-Drevision=<version>`. See [IMPORTANT_README_FOR_DEVELOPERS.txt](IMPORTANT_README_FOR_DEVELOPERS.txt) for more build notes.

To build EvoSuite in Eclipse, make sure you have the [M2Eclipse](https://eclipse.dev/m2e/) plugin installed, and import EvoSuite as Maven project. This will ensure that Eclipse uses Maven to build the project.


# LLM-based operators

EvoSuite can use a large language model inside the search, as semantic mutation and crossover operators. They are off by default. Key properties, with their descriptions from `Properties.java` (group `LLM`, which lists the remaining `llm_*` properties):

- `llm_operator_mode`: How LLM semantic operators take part in MOSA breeding: OFF uses the standard operators only; SYNC replaces standard mutation/crossover with a blocking LLM call on offspring whose parent was chosen by the operator target selector (subject to `llm_mutation_probability` / `llm_crossover_probability`); ASYNC submits selector targets to a background executor each generation and its results enter the next generation as external candidates.
- `llm_operator_targeting`: How LLM operator targets are chosen: DIRECTED ranks (parent, goal) pairs by closeness to an uncovered goal; RANDOM pairs random individuals with random current goals as a control.

# More Information

Usage documentation can be found at <https://www.evosuite.org/documentation/>

The developers' mailing list is hosted at <https://groups.google.com/g/evosuite>

EvoSuite has resulted in a number of publications, all of which are available at <https://www.evosuite.org/publications/>



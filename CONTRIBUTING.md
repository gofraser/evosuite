# Contributing to EvoSuite

Use the [issue tracker](https://github.com/EvoSuite/evosuite/issues) to report a problem. For a
generation bug, include a small class that reproduces it, the command or plugin settings, the
JDK and EvoSuite versions, and the random seed if you have it.

## Building and testing

Build EvoSuite with JDK 11 or newer and Maven 3.6.3 or later. The shared IDE module builds
with JDK 17 or newer.

```sh
mvn clean package -DskipTests
mvn verify
mvn -pl client -am verify
mvn license:check
```

Use `verify` for the test run. It packages the JARs needed by the integration tests and runs
Checkstyle. With `-pl`, add `-am` so Maven builds the other EvoSuite modules from the checkout.
Run `clean` when switching JDKs or switching to or from the `java8-cli` profile.

The main source roots target Java 8, and the core runtime must continue to run on Java 8. See
the [Java 8 compatibility CLI](README.md#java-8-compatibility-cli) for the matching generator
and standalone runtime. The root `pom.xml` sets the reactor version in `revision`. Use
`-Drevision=X.Y.Z` to override it for one build.

Add `-PSystemTests` to `verify` to run the system tests. To run one integration or system test,
use `-Dit.test=ClassName -Dfailsafe.failIfNoSpecifiedTests=false` with the relevant module and
`-am`. Use `-Dtest=ClassName -Dsurefire.failIfNoSpecifiedTests=false` for a unit test.

The [Maven plugin integration tests](.github/workflows/maven-plugin-its.yml) run separately.
Install the reactor with `mvn install -DskipTests`, then run the smoke subset with
`mvn verify -Pmaven-plugin-its -pl plugins/maven-test -Dits.groups=smoke`. Omit the group to run
the full suite. The build-support fixtures bootstrap their own Maven repository under
`plugins/build-support-test/target/fixture-m2`. Use a separate Maven local repository when
installing a development build alongside another build of the same version.

The [Eclipse plugin](plugins/eclipse/README.md#building) has its own Tycho build and uses the
JARs produced by the main reactor.

## Writing code

- Follow the [EvoSuite Checkstyle rules](checkstyle-evosuite.xml) and
  [suppressions](checkstyle-suppressions.xml). Use four spaces per block and a 120-column limit
  with the exceptions in the rules. Put project and third-party imports first, followed by
  `javax`, `java`, and static imports. Sort each group alphabetically.
- Import the EvoSuite [Eclipse formatter](eclipse-java-google-style.xml) under *Java > Code
  Style > Formatter* and the [import order](eclipse-java.importorder) under *Java > Code Style >
  Organize Imports*. In IntelliJ, import the [EvoSuite scheme](intellij-java-google-style.xml)
  under *Editor > Code Style*. Format the lines you change.
- Add the [licence header](src/etc/header.txt) to new source files, POMs and scripts. Leave the
  excluded test fixtures as they are, since some tests depend on their line numbers. Review the
  diff after `mvn license:format`, especially where code starts on the same line as an old header.
- Use an SLF4J `logger` with placeholders, e.g. `logger.debug("Loaded class {}", className)`.
  Use `LoggingUtils.getEvoLogger()` for messages meant for the CLI. The client sends log
  messages to the master, so direct writes to `System.out` or `System.err` bypass that path.
- Preserve determinism. Use EvoSuite's seeded randomness and keep iteration order stable where
  it affects the search. For example, use a `LinkedHashMap` or `LinkedHashSet` when iteration
  order matters. `BaseDeterminismSystemTest` checks this for the generator.
- Keep mutable state local to a run where possible. If shared state is needed, give it a clear
  owner and reset it between runs. `org.evosuite.Properties` holds global configuration.
- Throw an exception and let the entry point handle the exit. Calling `System.exit` from engine
  code cuts off cleanup and makes the code harder to test.
- Check public preconditions with exceptions such as `IllegalArgumentException` or
  `IllegalStateException`. Use assertions for internal preconditions and postconditions. If a
  private method checks a public argument on the caller's behalf, keep the explicit exception.
- Put fields first, then constructors, then methods. Split long methods where it makes the code
  easier to follow. Use Javadoc to explain a class's purpose and any side effects a caller needs
  to know about, such as writing a file or registering a shutdown hook.

Put unit tests under `src/test/java` in the same package as the code they test. For example,
tests for `Foo` go in `FooTest`. Put classes that EvoSuite should generate tests for under
`com.examples.with.different.packagename` unless the test needs a particular package.

## Making a release

Use Maven 3.9.2 or later and JDK 17 or newer so the release includes the `RandomGenerator`
mocks. Set `revision` in the root POM to the release version and run the
[Release workflow](.github/workflows/release.yml) from that branch or tag.
Leave `dry-run` selected to build the source and Javadoc JARs without signing or uploading.
Check that the shaded Javadoc JAR contains the generated API pages.

Clear `dry-run` to sign and upload the artifacts. The workflow reads the repository secrets
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY` and `GPG_PASSPHRASE`.
The upload waits for validation. Publish the validated bundle in the Central Portal.

For a local build, pass the release version on the command line. Replace `X.Y.Z` with that version.

```sh
mvn -Prelease -Drevision=X.Y.Z clean verify -DskipTests -Dgpg.skip
mvn -Prelease -Drevision=X.Y.Z clean deploy -DskipTests
```

The second command signs and uploads. It needs a `central` server in `~/.m2/settings.xml`
with a Central Portal user token and a local GPG key. After publishing, set `revision` to the
next snapshot version and commit it.

# Working on EvoSuite

Read [CONTRIBUTING.md](CONTRIBUTING.md) for the build, test, and style conventions.

- `master` contains the CLI and launches the client. `client` contains the search, instrumentation
  and test generation. `runtime` contains the code used by generated tests. `standalone_runtime`
  packages it for users, and `shaded` packages the relocated generator.
- `plugins/maven` is part of the main Maven reactor. `plugins/eclipse` has a separate Tycho build
  and needs engine JARs from the main reactor.
- Use `mvn -pl <module> -am verify` for a focused build. Some tests need packaged JARs, so use
  `verify` for validation. See [CONTRIBUTING.md](CONTRIBUTING.md#building-and-testing) for test
  selectors and the separate Maven plugin integration tests.
- Keep the core runtime compatible with Java 8 and the main build compatible with JDK 11.
  Run `clean` when changing the JDK or the Java 8 CLI profile.
- Use a separate Maven local repository for experimental installs. Build-support fixtures write
  their repository under `plugins/build-support-test/target/fixture-m2`.
- Preserve seeded behaviour and keep changes to class loading, instrumentation and generated
  tests covered by the relevant tests.

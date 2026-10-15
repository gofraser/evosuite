# EvoSuite Maven plugin

The plugin generates JUnit tests for a Maven project with EvoSuite, and wires the generated tests into
the build so that `mvn test` compiles and runs them like any other test. Maven downloads EvoSuite
itself, so nothing needs to be installed.

Requires Maven 3.9 or later.

## Setup

Add the plugin with its `setup` goal, and the EvoSuite runtime as a test dependency:

```xml
<build>
  <plugins>
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
  </plugins>
</build>

<dependencies>
  <dependency>
    <groupId>org.evosuite</groupId>
    <artifactId>evosuite-standalone-runtime</artifactId>
    <version>${evosuite.version}</version>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.14.3</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

`setup` runs in the `generate-test-sources` phase and:

- adds `src/test/evosuite` as a test source folder;
- adds the EvoSuite runtime as a `-javaagent` to the `argLine` property, which Surefire and Failsafe pass to
  the test JVM. If your Surefire configuration sets `<argLine>` explicitly, include `@{argLine}` in it, as for
  JaCoCo;
- fails the build, naming the dependency to add, if the generated tests cannot compile because the runtime
  or their JUnit version is missing.

## Generating tests

```
mvn evosuite:generate
```

compiles the project and generates tests for every class, writing them to `src/test/evosuite`. Commit
that folder: the tests are ordinary source, reviewed and versioned like the rest. Hand-written tests stay in
`src/test/java` and are never touched.

Runs are incremental. A class gets new tests only if it has none yet, or if its bytecode, the bytecode of
one of its superclasses, or the EvoSuite version changed since its tests were generated. A run on an
unchanged project finishes after the analysis. Tests whose class no longer exists are deleted from
`src/test/evosuite`. Classes named explicitly with `evosuite.cuts` are always tested.

EvoSuite keeps its own state in `.evosuite/` (the best tests so far, run logs, and the fingerprints used for
incremental runs). Add it to `.gitignore`, or commit it to share incremental state between machines.

### Parameters of `generate`

| Property | Default | Meaning |
|---|---|---|
| `evosuite.timeInMinutesPerClass` | 2 | Search budget per class |
| `evosuite.timeInMinutesPerProject` | 0 | Total budget per module; if set, distributed over the classes |
| `evosuite.cores` | 1 | Classes tested in parallel |
| `evosuite.memoryInMB` | 800 | Memory for all parallel jobs together, at least 500 per core |
| `evosuite.cuts` | | Comma separated classes to test (always tested, even if unchanged) |
| `evosuite.cutsFile` | | File with such a list, for long lists |
| `evosuite.criterion` | `LINE:BRANCH:EXCEPTION:WEAKMUTATION:OUTPUT:METHOD:METHODNOEXCEPTION:CBRANCH` | Coverage criteria |
| `evosuite.schedule` | `BUDGET` | How the budget is spread over classes, see below |
| `evosuite.testFolder` | `src/test/evosuite` | Where the tests are written |
| `evosuite.deleteStaleTests` | true | Delete tests of classes that no longer exist |
| `evosuite.extraArgs` | | Further EvoSuite properties on the command line, eg `-Dctg_incremental=false`; no spaces in values |
| `evosuite.llmApiKeyServerId` | | `settings.xml` server holding the LLM API key, see below |
| `evosuite.skip` | false | Skip the goal |

`classIncludes` and `classExcludes` (POM configuration only) select classes by patterns of their class files,
eg `com/example/service/**` or `**/generated/**`. Unchanged classes among them are still skipped.

`evosuiteProperties` (POM configuration only) passes any further EvoSuite property to test generation. Values
may contain spaces, and names are checked before generation starts, so a typo fails the build:

```xml
<configuration>
  <evosuiteProperties>
    <search_budget>30</search_budget>
    <assertion_strategy>MUTATION</assertion_strategy>
  </evosuiteProperties>
</configuration>
```

### LLM support

EvoSuite can use a large language model, eg to seed the search or to improve the generated tests. Its `llm_*`
properties go into `evosuiteProperties`:

```xml
<configuration>
  <evosuiteProperties>
    <llm_provider>ANTHROPIC</llm_provider>          <!-- or OPENAI, OLLAMA -->
    <llm_model>claude-sonnet-5-5</llm_model>
    <llm_seed_initial_population>true</llm_seed_initial_population>
  </evosuiteProperties>
  <llmApiKeyServerId>evosuite-llm</llmApiKeyServerId>
</configuration>
```

The API key does not belong in the pom or on the command line, where every process listing shows it. Put it in
`~/.m2/settings.xml` as the password of a server, encrypted with `mvn --encrypt-password` if you like, and name
that server in `llmApiKeyServerId`:

```xml
<servers>
  <server>
    <id>evosuite-llm</id>
    <password>{encrypted key}</password>
  </server>
</servers>
```

EvoSuite then receives the key through its environment. Without `llmApiKeyServerId`, it reads the
`EVOSUITE_LLM_API_KEY` environment variable. For a local Ollama, set `llm_base_url` instead
(`http://localhost:11434`); no key is needed.

LLM calls are made per class, so incremental runs also save cost. With `llm_trace_enabled`, the prompts and
responses, which include code of the project, are written to `.evosuite/llm-traces` rather than into the module.

### Schedules

- `SIMPLE`: the same budget for every class.
- `BUDGET`: every class gets a minimum, and the rest is shared out by number of branches; the biggest jobs start
  first.
- `SEEDING`: classes that others take as input, or inherit from, are tested first, and their object pools
  are offered to the classes that use them; tests kept from the previous run seed the search.
- `BUDGET_AND_SEEDING`: both of the above.
- `HISTORY`: changed classes first, with twice the budget; unchanged classes are tested again only while
  their coverage still improved over the last three runs. A list of changed source files (eg from version
  control) can be given with `-Dctg_history_file=<file>`, one `A` or `M`, a tab, and a path per line.

## Other goals

- `export`: copy the tests from `.evosuite/` to `src/test/evosuite` again, eg after deleting that folder.
- `clean`: delete `.evosuite/`.
- `info`: summarize the generated tests.
- `coverage`: measure the coverage of the existing tests with EvoSuite's criteria.
- `prepare`: only for tests generated in the legacy JUnit 4 scaffolding format, together with
  `org.evosuite.runtime.InitializingListener` as a Surefire listener.

## JDK

EvoSuite runs on the JDK Maven runs on, unless a toolchain is selected with maven-toolchains-plugin, in which
case it runs on the toolchain's JDK.

## Upgrading from earlier versions

- Properties now have an `evosuite.` prefix (`-Devosuite.cores=2`). The old names still work for now, with a
  warning. POM `<configuration>` element names are unchanged.
- Tests go to `src/test/evosuite` instead of `src/test/java` (`export`), and `generate` writes them there
  itself. Set `evosuite.testFolder` to keep the old location.
- Tests are generated as JUnit 5 tests with the `EvoSuiteExtension` by default, which need neither the
  `prepare` goal, nor the `InitializingListener`, nor build-helper-maven-plugin. Replace them with `setup`.
- Incremental generation is on by default; `-Devosuite.extraArgs=-Dctg_incremental=false` regenerates every
  class.

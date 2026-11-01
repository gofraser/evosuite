NOTES FOR DEVELOPERS

These notes are meant for developers working on EvoSuite.
There are several rules of thumb regarding how to write "good code",
but often rules are either too generic and not tailored for a given particular
piece of software (e.g., different kinds of architectures).

The rules of thumb described here in this document are not meant to be either exhaustive nor absolute.
Rigid rules are not substitute for common sense, as they are rather guidelines that can
be ignored in some special cases.
Furthermore, the guidelines need to be _realistic_ and easy to use: there would be no point
to ask for detailed comments on each single method/field and 100% coverage test suites...



---------------------------------------------
AVOID System.out AND System.err

EvoSuite uses a logging framework, in which client processes do communicate with Master through TCP.
For debugging  and logging errors in a class Foo, create a logger in the following way:

private static Logger logger = LoggerFactory.getLogger(Foo.class);

It is important to keep the same name 'logger' to make things consistent among classes.
If the logging should be part the actual output for the console user, then rather use: 

LoggingUtils.getEvoLogger()


---------------------------------------------
AVOID String concatenation in Loggers

writing something like:

logger.debug("this is not "+ foo + " very " + bar +" efficient");

is not efficient, as most of the time debug logs are deactivated, and concatenating strings is
expensive. Recall String is immutable, and each "+" create a new String object.
The above logging can be rewritten into:

logger.debug("this is not {} very {} efficient", foo, bar);

Note: not a big deal for "warn"/"error", as those are/should be rare... but it can become
quite an overhead for trace/debug/info



--------------------------------------------
EvoSuite should be DETERMINISTIC

EvoSuite uses randomized algorithms. However, given the same random seed, the behavior should be
fully deterministic. This is essential for debugging EvoSuite. Unfortunately, there are few libraries/APIs
that are non-deterministic, like for example HashMap and HashSet. Rather use equivalent classes that
are deterministic, ie LinkedHashMap and LinkedHashSet.
Note: we have system tests to check if EvoSuite remains deterministic, eg see BaseDeterminismSystemTest.




---------------------------------------------
DO NOT USE System.exit

Better to throw an exception, as the entry point of EvoSuite does some logging when ends.
Furthermore, System.exit becomes problematic when unit testing EvoSuite.



---------------------------------------------
STATIC VARIABLES ARE YOUR ENEMY

Static variables should be either constant or representing transient data (eg cache information whose presence/missing
has only effect on performance, not on functionality).
Having "classes with states" is usually a poor OO design (an exception to this rule is org.evosuite.Properties).
If those are really needed, then you should rather use a singleton pattern. 
This is not just to be pedantic, but, really, non-constant static variables make unit testing far much harder
and lead to code that is more difficult to understand and maintain. 



---------------------------------------------
HOW TO WRITE JUNIT TEST CASES

Until EvoSuite will not be applicable to itself, there is the need to write manual test cases.
They should be put in the "src/test/java" folder, following the same package structure as EvoSuite code.
A unit test suite for SUT org.evosuite.somepackage.Foo should be called org.evosuite.somepackage.FooTest.
This is useful for several reasons:
- Need to know what class the test case is supposed to unit test by just looking at its name
- Should be easy to identify if a class has a test suite for it
- If in same package, then the test suite can access package/protected fields/methods
- Having "Test" as postfix (instead of a prefix) is useful for when searching for classes by name (eg CTRL-SHIFT-t in Eclipse)

If for testing there is the need to create additional, support classes used as data input for EvoSuite,
then those will need to be put in the com.examples.with.different.packagename package.

---------------------------------------------
AVOID TOO LONG METHODS

Too long methods (eg more than 100 lines) should be split, as difficult to understand.
For this task, in Eclipse, you can right-click on a code snippet and "Refactor -> Extract Method..." 



---------------------------------------------
WRITE COMMENTS

In the ideal world, each class/method/field would have nice, detailed, appropriate code comments.
But even in such a beautiful world, everything would go to hell at the first code change, as that might
require manually changing most of the code comments.

Cannot really quantify how much comments one should write, but at least it would be good to have:
- brief (1-2 sentences) description of what the class is useful for (just before the class declaration) 
- for fields that are data structures (collections,arrays) some comments would be useful, as long and detailed 
  variable names are not practical

When writing a comment for a class/method/field, use JavaDoc style:
/**
*/
In this way, tools like Eclipse will show the comments when you hover with the mouse over them.
  

---------------------------------------------
IF CANNOT AVOID EXTERNAL SIDE-EFFECTS, DO DOCUMENT IT!!!

If a call on a object has side-effects outside the class itself (eg writing to disk, add a system hook thread),
then this needs to be documented (see point on how to write comments).  


---------------------------------------------
PRE and POST CONDITIONS

- Pre-conditions of 'public' methods should throw exceptions explicitly (eg, IllegalArgumentException and IllegalStateException).
  Whenever possible, it is worth to write pre-conditions to public methods.
  If exceptions are thrown with 'throw new ...', then recall to add "throws ..." to the method signature.
- Pre-conditions of 'private' methods and post-conditions (both public and private methods) should use the keyword 'assert'.
  (An exception is when the validation of inputs of a public method is delegated/moved to a private method: in this case use 'throw'.)
  Post-conditions are good, but often are difficult to write.
  Note: a post-condition does not to be complete to be useful (ie find bugs). For example, if we have 'A && B', but the writing
  of 'B' is too difficult (or time consuming), still having just 'A' as post-condition can help  
  
  
---------------------------------------------
FIELDS/CONSTRUCTORS/METHODS ORDER IN A CLASS 

when writing a new class (or re-factoring a current one), fields should come first, followed by class constructors and then the other methods.



---------------------------------------------
CODE STYLE

Formatting follows the Google Java style. Formatter settings for Eclipse and
IntelliJ are in eclipse-java-google-style.xml and intellij-java-google-style.xml
in the repository root, and checkstyle-evosuite.xml (with
checkstyle-suppressions.xml) holds the checkstyle rules.



---------------------------------------------
BUILD COMMANDS

Build with JDK 17 (21 also works; JDK 25 additionally compiles
runtime/src/main/java25, JDK 27 does not work). Maven 3.6.3 or later is
required. All modules target Java 8 (java.release 8), and the runtime module
must stay Java 8 compatible.

  mvn package -DskipTests          # fast build, no tests
  mvn verify                       # build and run the tests

Traps:

- Use 'verify', not 'clean test': EvoSelectorInstrumentedTest needs the
  packaged runtime jar, which exists only after the package phase.
- When building a single module with -pl X, add -am, otherwise Maven resolves
  the other EvoSuite modules from ~/.m2 (possibly a stale version).
- A full 'mvn install' rewrites tracked files under
  plugins/build-support-test/projects/*/.m2. Restore them with
  'git checkout plugins/build-support-test'.
- The Maven plugin integration tests (plugins/maven-test) only run with
  -Pmaven-plugin-its; '-Dits.groups=smoke' selects a smoke subset. The poms pass
  -DevosuiteVersion to them automatically.

The version of all modules is the 'revision' property in the root pom.xml
(e.g. 1.2.1-SNAPSHOT); the modules use ${revision} as their version, and
flatten-maven-plugin writes the resolved poms. Override it on the command
line with -Drevision=X.Y.Z.



---------------------------------------------
HOW TO MAKE A RELEASE

To use the Maven plugin, and to link the "runtime" jar (plus dependencies)
to a project, EvoSuite needs to be released and deployed on Maven Central.

The version is the 'revision' property (see above); no other pom needs to
change. Choose a version X.Y.Z (no SNAPSHOT).

Preferred: the GitHub workflow .github/workflows/release.yml (Actions ->
Release -> Run workflow). It is a workflow_dispatch workflow with one input,
dry-run, which defaults to true. It builds whatever ref it is run from, so run
it from a branch or tag on which the 'revision' property in the root pom.xml
is X.Y.Z. With dry-run, it only builds the release artifacts (sources,
javadoc) without signing or uploading. Untick dry-run to sign and deploy. It
needs the repository secrets MAVEN_CENTRAL_USERNAME, MAVEN_CENTRAL_PASSWORD,
GPG_PRIVATE_KEY and GPG_PASSPHRASE.

Locally, without changing the pom:

  mvn -Prelease -Drevision=X.Y.Z deploy

The 'release' profile attaches sources and javadoc jars, signs with GPG and
uploads through central-publishing-maven-plugin. This requires a server with
id 'central' in ~/.m2/settings.xml (a Central Portal user token) and a GPG
key configured on your machine. The uploaded bundle is validated but not
published automatically; publish it from the Central Portal.

After the release, set the 'revision' property in the root pom.xml to the
next SNAPSHOT version and commit it.

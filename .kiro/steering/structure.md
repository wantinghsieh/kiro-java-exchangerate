# Project Structure and Conventions

## Layout (standard Maven)
```
pom.xml
src/main/java/<base.package>/...
src/main/resources/
src/test/java/<base.package>/...
src/test/resources/
```
- Organize packages by feature/domain, not by technical layer where practical.
- Keep one top-level public type per file.
- Do not commit `target/`.

## Code Style
- Use modern Java features where they improve clarity:
  - `record` for immutable data carriers.
  - `sealed` interfaces with pattern-matching `switch` for closed hierarchies.
  - `var` for local variables when the type is obvious from the right-hand side.
  - Text blocks, enhanced `instanceof`, record patterns, switch expressions.
  - Unnamed variables `_` where a binding is unused.
- Use `Optional` for return values only, never for fields or parameters.
- Use `BigDecimal` (with explicit `RoundingMode` and scale) for monetary values and exchange rates, never `double`/`float`.
- Use `java.time` for dates and times. Inject a `Clock` for testability.
- Use `Objects.requireNonNull` for constructor arguments; validate in record compact constructors.
- Throw specific exceptions with useful messages. Do not swallow exceptions.
- Use `java.util.logging` or SLF4J consistently; never `System.out` in production code.
- Favor constructor injection and small, single-purpose classes.
- Public APIs get Javadoc, including thread-safety guarantees (e.g. "thread-safe", "immutable", "not thread-safe").

## Workflow
- After any code change, run `mvn test` and fix failures before finishing.
- Add or update JUnit tests alongside behavior changes.

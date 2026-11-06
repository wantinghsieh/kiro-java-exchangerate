# Tech Stack

## Language and Build
- Java 25 (LTS). Set `<maven.compiler.release>25</maven.compiler.release>` in the POM.
- Maven is the only build tool. Use the Maven Wrapper (`./mvnw`, `mvnw.cmd` on Windows) when present.
- Pin all dependency and plugin versions exactly. No version ranges, no `LATEST`/`RELEASE`.
- Manage versions in `<properties>` and `<dependencyManagement>`.
- Use `maven-compiler-plugin` with `<release>`, not `<source>`/`<target>`.

## Common Commands
- Compile: `mvn -q compile`
- Test: `mvn test`
- Full verify: `mvn verify`
- Single test: `mvn test -Dtest=ClassNameTest#methodName`

## Testing
- JUnit 5 (Jupiter) via `junit-bom` in `dependencyManagement`; scope `test`.
- Use `maven-surefire-plugin` (recent version) for unit tests, `maven-failsafe-plugin` for `*IT` integration tests.
- Use AssertJ-free core assertions (`org.junit.jupiter.api.Assertions`) unless the project adds another library explicitly.
- Prefer `@ParameterizedTest` for input variations, `@Nested` for grouping.
- Test class naming: `<ClassName>Test`. Mirror the main package structure under `src/test/java`.
- Concurrency tests: use `ExecutorService`, `CountDownLatch`/`CyclicBarrier`, and timeouts (`@Timeout`). Never rely on `Thread.sleep` for synchronization.

## Concurrency and Collections
- Use standard `java.util.concurrent` types for shared mutable state:
  - `ConcurrentHashMap` for shared maps (use `compute`, `merge`, `computeIfAbsent` for atomic updates).
  - `CopyOnWriteArrayList` for read-heavy, rarely modified lists.
  - `ConcurrentLinkedQueue`/`LinkedBlockingQueue`/`ArrayBlockingQueue` for queues.
  - `AtomicInteger`/`AtomicLong`/`AtomicReference`/`LongAdder` for counters and flags.
- Do not wrap with `Collections.synchronizedXxx` or use `Hashtable`/`Vector`.
- Never do check-then-act on a concurrent map (`containsKey` then `put`). Use atomic methods.
- Prefer immutability: records, `List.of`, `Map.copyOf`, `Set.copyOf`, and `final` fields. Share immutable snapshots across threads.
- Use `ReentrantLock` over `synchronized` when code may block on I/O, because it avoids pinning virtual threads.
- Prefer virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) for blocking I/O tasks. Always close executors (try-with-resources).
- Use structured concurrency and scoped values only after confirming their status in the JDK in use; they may still be preview APIs.
- Do not use `ThreadLocal` for new code where `ScopedValue` is available and non-preview. Otherwise keep `ThreadLocal` usage minimal.

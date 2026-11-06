# Implementation Plan: Currency Exchange Rate Service

## Overview

Implement the Java 25 library in package `com.example.exchangerate` using Maven, JUnit 5 and jqwik. Work proceeds from build setup, to immutable domain types, to the service (register, query, convert), then concurrency and convention tests. Each property test goes in its own class under `src/test/java/com/example/exchangerate/` so tests can run in parallel waves. The design uses Java, so no language question is needed.

## Tasks

- [x] 1. Set up project build and test support
  - [x] 1.1 Create `pom.xml`
    - `maven.compiler.release` 25, `maven-compiler-plugin` with `<release>`, `maven-surefire-plugin` (recent, exact version)
    - `junit-bom` in `dependencyManagement`; jqwik dependency; all versions pinned in `<properties>`
    - Test scope for JUnit and jqwik; add `.gitignore` entry for `target/`
    - _Requirements: 7.8_

  - [x] 1.2 Create `support/MutableClock` test helper
    - Controllable `java.time.Clock` in `src/test/java/com/example/exchangerate/support/MutableClock.java`
    - Thread-safe (AtomicReference<Instant>), with `set` and `advance`
    - _Requirements: 7.2, 6.6_

- [x] 2. Implement domain types
  - [x] 2.1 Implement `CurrencyCodes`, `CurrencyPair`, `ExchangeRate`, `RateNotFoundException`
    - `CurrencyCodes.requireValid` (package-private): non-null, exactly three chars A to Z; IAE message contains the code or "null"
    - `CurrencyPair` record: validates codes, rejects base equals target (message contains the code), `inverse()`
    - `ExchangeRate` record: requireNonNull on fields, rate > 0, scale <= 10
    - `RateNotFoundException` extends RuntimeException with message containing both codes
    - Javadoc with thread-safety statement on each public type
    - _Requirements: 1.5, 1.6, 5.5, 6.4_

  - [x] 2.2 Implement `ConversionResult` record
    - Fields: sourceAmount, convertedAmount, from, to, appliedRate, sourceRate; `exchangeRateUsed()` returns Optional
    - Javadoc with thread-safety statement
    - _Requirements: 4.1, 4.3, 5.5, 6.3, 6.4_

  - [x] 2.3 Write unit tests for domain types
    - Parameterized invalid codes (null, empty, blank, wrong length, lowercase, non-alphabetic; at least 6)
    - Pair equal-code rejection, inverse, record invariants and immutability
    - _Requirements: 1.5, 1.6, 5.5, 7.3_

- [x] 3. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 4. Implement registration in `ExchangeRateService`
  - [x] 4.1 Create `ExchangeRateService` with constructor and `register` overloads
    - Constructor: `Objects.requireNonNull(clock, "clock")`; state is `ConcurrentHashMap<CurrencyPair, List<ExchangeRate>>`
    - Validate codes, pair, explicit instant (IAE "effective Instant must not be null"), rate (IAE "rate must be positive"), round to scale 10 HALF_EVEN, reject zero after rounding (IAE "rate rounds to zero at the Rate_Scale")
    - Default instant: single `clock.instant()` read
    - `compute` builds a new immutable ascending list, replacing any entry at the same instant
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 5.2, 5.4, 6.1, 6.2, 6.3, 6.5_

  - [x] 4.2 Write unit tests for registration
    - Nominal, default instant, replace, rounding, inverse unchanged, null instant, null clock
    - Parameterized non-positive rates (zero, negative, smallest negative; at least 3) and rounding boundaries (below, at, above midpoint; at least 3)
    - Verify state unchanged after each rejection
    - File: `ExchangeRateServiceRegisterTest.java`
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8, 1.9, 1.10, 6.2, 7.1, 7.3_

  - [x] 4.3 Write property test for registration rounding
    - **Property 1: Registration stores the rounded rate**
    - **Validates: Requirements 1.1, 1.4, 1.9**

  - [x] 4.4 Write property test for default instant from Clock
    - **Property 2: Default effective instant comes from the Clock**
    - **Validates: Requirements 1.2**

  - [x] 4.5 Write property test for replacement
    - **Property 3: Same pair and instant replaces**
    - **Validates: Requirements 1.3**

  - [x] 4.6 Write property test for invalid input rejection
    - **Property 4: Invalid input is rejected without side effects**
    - **Validates: Requirements 1.5, 1.6, 1.7, 1.8, 2.5, 2.6, 4.8, 4.9**

- [x] 5. Implement queries
  - [x] 5.1 Add `currentRate`, `rateAsOf`, `history` (both overloads) to `ExchangeRateService`
    - `currentRate(String, String)`: validate codes and pair, one clock read, as-of lookup
    - `rateAsOf`: requireNonNull on pair and instant; binary search for greatest effective instant <= asOf
    - `history(pair)`: return stored immutable snapshot or `List.of()`
    - `history(pair, start, end)`: NPE on nulls, IAE if start > end, empty if equal, half-open `[start, end)` via `List.copyOf`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 5.3_

  - [x] 5.2 Write unit tests for queries
    - Current rate cases (none, future only, newer registered), invalid and duplicated codes, as-of boundaries, history ordering and range, start > end, start == end, null arguments, unmodifiable lists, snapshot after later registration
    - File: `ExchangeRateServiceQueryTest.java`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6, 3.1, 3.2, 3.3, 3.4, 3.5, 3.6, 3.7, 3.8, 3.9, 3.10, 7.1_

  - [x] 5.3 Write property test for as-of lookup
    - **Property 5: As-of lookup matches a reference model**
    - **Validates: Requirements 2.1, 2.2, 2.3, 2.4, 3.1, 3.2, 3.3**

  - [x] 5.4 Write property test for history ordering
    - **Property 6: History is ordered, unique, and unmodifiable**
    - **Validates: Requirements 3.4, 3.6**

  - [x] 5.5 Write property test for range history
    - **Property 7: Range history is the half-open filter**
    - **Validates: Requirements 3.5, 3.7, 3.8**

  - [x] 5.6 Write property test for history snapshots
    - **Property 8: Returned histories are snapshots**
    - **Validates: Requirements 3.10**

- [x] 6. Implement conversion
  - [x] 6.1 Add `convert` overloads to `ExchangeRateService`
    - Validate codes then amount (IAE "amount must be non-null and non-negative")
    - Order: same currency (rate 1, scale 2); direct rate; inverse reciprocal (`ONE.divide(rate, 10, HALF_EVEN)`); else `RateNotFoundException` naming both codes
    - Round to scale 2 HALF_EVEN; read the clock once
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 6.1_

  - [x] 6.2 Write unit tests for conversion
    - Direct, as-of, identity without rates, inverse, direct preferred, zero amount, missing rate, null or negative amount, invalid codes, rounding of HALF_EVEN midpoints
    - File: `ExchangeRateServiceConvertTest.java`
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 7.1_

  - [x] 6.3 Write property test for direct conversion
    - **Property 9: Direct conversion arithmetic**
    - **Validates: Requirements 4.1, 4.2, 4.6**

  - [x] 6.4 Write property test for same-currency identity
    - **Property 10: Same-currency conversion is the identity**
    - **Validates: Requirements 4.3**

  - [x] 6.5 Write property test for inverse conversion
    - **Property 11: Inverse conversion uses the reciprocal**
    - **Validates: Requirements 4.4, 4.5**

  - [x] 6.6 Write property test for missing rate
    - **Property 12: Missing rate raises RateNotFoundException**
    - **Validates: Requirements 4.7**

  - [x] 6.7 Write property test for determinism
    - **Property 13: Deterministic results under a fixed Clock**
    - **Validates: Requirements 6.6**

- [x] 7. Checkpoint - Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. Concurrency and convention tests
  - [x] 8.1 Write concurrent registration test (32+ threads)
    - Release threads together with CountDownLatch/CyclicBarrier; verify all rates present, none missing, duplicated or altered
    - `@Timeout(30)`, executor in try-with-resources, no `Thread.sleep`
    - File: `ExchangeRateServiceConcurrencyTest.java`
    - _Requirements: 5.1, 5.2, 7.4, 7.5, 7.6, 7.9_

  - [x] 8.2 Write same-key contention test
    - Many threads register the same pair and instant; exactly one entry remains and equals one registered value
    - File: `ExchangeRateServiceSameKeyConcurrencyTest.java`
    - _Requirements: 5.4, 7.4, 7.5_

  - [x] 8.3 Write readers-and-writers test
    - Readers query and convert while writers register; every observed value derives from a registered rate; no null or partial value
    - File: `ExchangeRateServiceReadWriteConcurrencyTest.java`
    - _Requirements: 5.1, 5.3, 7.4, 7.5, 7.7_

  - [x] 8.4 Write interrupt-status test
    - Operations on an interrupted thread preserve its interrupted status and leave data consistent
    - File: `ExchangeRateServiceInterruptTest.java`
    - _Requirements: 5.6_

  - [x] 8.5 Write `ConventionsTest`
    - Reflection: no `double`/`float` in public API
    - Source scan: no `HashMap`/`ArrayList` shared state, no `Instant.now()`, `System.currentTimeMillis`, Clock-less `LocalDateTime.now()`
    - Javadoc thread-safety statement present on each public type
    - _Requirements: 6.1, 6.3, 6.4, 6.5_

- [x] 9. Final checkpoint - Ensure all tests pass
  - Run `mvn test`; expect 0 failures, 0 errors, 0 skipped. Ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties (jqwik, at least 100 tries, tagged `Feature: currency-exchange-rate-service, Property N`)
- Unit tests validate specific examples and edge cases

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["2.2", "2.3"] },
    { "id": 3, "tasks": ["4.1"] },
    { "id": 4, "tasks": ["4.2", "4.3", "4.4", "4.5", "4.6", "5.1"] },
    { "id": 5, "tasks": ["5.2", "5.3", "5.4", "5.5", "5.6", "6.1"] },
    { "id": 6, "tasks": ["6.2", "6.3", "6.4", "6.5", "6.6", "6.7", "8.1", "8.2", "8.3", "8.4", "8.5"] }
  ]
}
```

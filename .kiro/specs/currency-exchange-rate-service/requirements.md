# Requirements Document

## Introduction

The Currency Exchange Rate Service is a Java 25 library that stores exchange rates between a base currency and a target currency, converts monetary amounts using those rates, and answers queries for the current rate and for historical rates. The service is shared by many threads, so every operation must be safe under concurrent access. Rates and amounts use BigDecimal, and time is supplied by an injected Clock. The feature includes a comprehensive JUnit 5 test suite, including concurrency tests.

## Glossary

- **Rate_Service**: The Currency Exchange Rate Service, the public entry point for registering, querying and converting.
- **Currency_Code**: An ISO 4217 three-letter uppercase alphabetic code, such as USD or EUR.
- **Currency_Pair**: An ordered pair of a base Currency_Code and a target Currency_Code.
- **Exchange_Rate**: An immutable record of a Currency_Pair, a positive BigDecimal rate (units of target currency per one unit of base currency), and an effective Instant.
- **Rate_History**: The chronologically ordered collection of all Exchange_Rate entries registered for one Currency_Pair.
- **Current_Rate**: The Exchange_Rate in a Rate_History with the latest effective Instant that is not after the Clock's current instant.
- **Clock**: A java.time.Clock injected into the Rate_Service to supply the current instant.
- **Conversion_Result**: An immutable record containing the source amount, the converted amount, the Currency_Pair, and the Exchange_Rate used.
- **Rate_Scale**: The maximum number of fractional digits of a stored rate, which is 10.
- **Amount_Scale**: The number of fractional digits of a converted amount, which is 2.

## Requirements

### Requirement 1: Register Exchange Rates

**User Story:** As a developer, I want to register exchange rates between a base and a target currency, so that the service has rates to query and convert with.

#### Acceptance Criteria

1. WHEN a valid base Currency_Code, a valid target Currency_Code, a positive rate and an effective Instant are provided, THE Rate_Service SHALL store an Exchange_Rate containing the Currency_Pair, the rate rounded per criterion 4, and the effective Instant, and SHALL include it in the Rate_History of that Currency_Pair.
2. WHEN a rate is registered without an explicit effective Instant, THE Rate_Service SHALL use the Clock's current instant, read once during that registration, as the effective Instant.
3. WHEN a rate is registered for a Currency_Pair and effective Instant that already has an Exchange_Rate, THE Rate_Service SHALL replace the earlier Exchange_Rate with the new one, so that the Rate_History contains exactly one Exchange_Rate for that effective Instant.
4. WHEN a rate has more than 10 fractional digits (the Rate_Scale), THE Rate_Service SHALL round the rate to 10 fractional digits using RoundingMode.HALF_EVEN before storing it.
5. IF a Currency_Code is null, or not exactly three uppercase letters A to Z, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message contains the invalid code (or the text "null" for a null code) and SHALL leave all Rate_History data unchanged.
6. IF the base Currency_Code equals the target Currency_Code, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message contains the code, and SHALL leave all Rate_History data unchanged.
7. IF the rate is null, zero or negative, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message states the rate must be positive, and SHALL leave all Rate_History data unchanged.
8. IF the rate is positive but rounds to zero at the Rate_Scale (for example 0.00000000004), THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message states the rate rounds to zero at the Rate_Scale, and SHALL leave all Rate_History data unchanged.
9. WHEN a rate is registered for a Currency_Pair, THE Rate_Service SHALL leave the Rate_History of the inverse Currency_Pair unchanged.
10. IF the effective Instant is explicitly provided as null, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message states the effective Instant must not be null, and SHALL leave all Rate_History data unchanged.

### Requirement 2: Query Current Rate

**User Story:** As a developer, I want to query the current rate for a currency pair, so that I can display or use the latest known rate.

#### Acceptance Criteria

1. WHEN the current rate is requested for a Currency_Pair with at least one Exchange_Rate whose effective Instant is at or before the Clock's current instant, THE Rate_Service SHALL return an Optional containing the Exchange_Rate with the latest effective Instant that is at or before the Clock's current instant as the Current_Rate.
2. WHEN the current rate is requested for a Currency_Pair that has no Rate_History, THE Rate_Service SHALL return an empty Optional and SHALL leave the Rate_History unchanged.
3. WHEN every Exchange_Rate of a Currency_Pair has an effective Instant after the Clock's current instant, THE Rate_Service SHALL return an empty Optional for the current rate.
4. WHEN a newer Exchange_Rate is registered for a Currency_Pair with an effective Instant that is after the effective Instant of the existing Current_Rate and not after the Clock's current instant, THE Rate_Service SHALL return the newer Exchange_Rate as the Current_Rate on the next current rate request.
5. IF a Currency_Code supplied to a query is null, blank, or not a valid Currency_Code, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message contains the invalid code value, and SHALL NOT modify the Rate_History.
6. IF the Currency_Pair supplied to a query has a base Currency_Code equal to its target Currency_Code, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message identifies the duplicated code.

### Requirement 3: Query Historical Rates

**User Story:** As a developer, I want to query historical rates, so that I can audit past rates and convert using a rate that applied at a given time.

#### Acceptance Criteria

1. WHEN a rate is requested for a Currency_Pair as of a given Instant, THE Rate_Service SHALL return an Optional containing the Exchange_Rate with the latest effective Instant that is at or before the given Instant.
2. WHEN a rate is requested for a Currency_Pair as of an Instant earlier than every effective Instant in the Rate_History of that Currency_Pair, THE Rate_Service SHALL return an empty Optional.
3. WHEN a rate is requested for a Currency_Pair that has no Rate_History, THE Rate_Service SHALL return an empty Optional.
4. WHEN the history is requested for a Currency_Pair, THE Rate_Service SHALL return an unmodifiable List of all Exchange_Rate entries for that Currency_Pair ordered by ascending effective Instant.
5. WHEN the history is requested for a Currency_Pair with a start Instant and an end Instant, THE Rate_Service SHALL return an unmodifiable List containing only the Exchange_Rate entries whose effective Instant is greater than or equal to the start Instant and strictly less than the end Instant, ordered by ascending effective Instant.
6. WHEN the history is requested for a Currency_Pair that has no Rate_History, THE Rate_Service SHALL return an empty unmodifiable List.
7. IF the start Instant is after the end Instant, THEN THE Rate_Service SHALL throw an IllegalArgumentException and SHALL leave the Rate_History unchanged.
8. IF the start Instant is equal to the end Instant, THEN THE Rate_Service SHALL return an empty unmodifiable List.
9. IF the Currency_Pair, the as-of Instant, the start Instant, or the end Instant supplied to a query is null, THEN THE Rate_Service SHALL throw a NullPointerException and SHALL leave the Rate_History unchanged.
10. WHEN a history List has been returned and a new rate is registered afterwards, THE Rate_Service SHALL leave the previously returned List unchanged in size and content.

### Requirement 4: Convert Amounts

**User Story:** As a developer, I want to convert an amount from one currency to another, so that I can compute monetary values in the target currency.

#### Acceptance Criteria

1. WHEN a conversion is requested for a non-null, non-negative amount and a Currency_Pair that has a Current_Rate, THE Rate_Service SHALL return a Conversion_Result whose converted amount equals the amount multiplied by the Current_Rate, rounded to the Amount_Scale using RoundingMode.HALF_EVEN.
2. WHEN a conversion is requested with an as-of Instant, THE Rate_Service SHALL use the Exchange_Rate in effect at that Instant as defined in Requirement 3.
3. WHEN the source Currency_Code equals the target Currency_Code, THE Rate_Service SHALL return a Conversion_Result whose converted amount equals the source amount rounded to the Amount_Scale using RoundingMode.HALF_EVEN and whose rate is 1, without requiring an Exchange_Rate to exist for the Currency_Pair.
4. WHEN no direct Exchange_Rate exists for the Currency_Pair and an Exchange_Rate exists for the inverse Currency_Pair, THE Rate_Service SHALL convert using the reciprocal of the inverse rate, calculated with a scale of 10 and RoundingMode.HALF_EVEN, and SHALL then round the converted amount to the Amount_Scale using RoundingMode.HALF_EVEN.
5. WHEN both a direct and an inverse Exchange_Rate exist for the Currency_Pair, THE Rate_Service SHALL convert using the direct Exchange_Rate.
6. WHEN the amount is zero, THE Rate_Service SHALL return a Conversion_Result with a converted amount of zero at the Amount_Scale.
7. IF no direct or inverse Exchange_Rate applies for the Currency_Pair, THEN THE Rate_Service SHALL throw a RateNotFoundException whose message contains both Currency_Codes of the Currency_Pair, and SHALL leave stored Exchange_Rates unchanged.
8. IF the amount is null or negative, THEN THE Rate_Service SHALL throw an IllegalArgumentException indicating that the amount must be non-null and non-negative, and SHALL leave stored Exchange_Rates unchanged.
9. IF a Currency_Code supplied to a conversion is invalid, THEN THE Rate_Service SHALL throw an IllegalArgumentException whose message contains the invalid code, and SHALL leave stored Exchange_Rates unchanged.

### Requirement 5: Thread Safety

**User Story:** As a developer, I want the service to be safe under concurrent use, so that many threads can register, query and convert without corrupt or inconsistent results.

#### Acceptance Criteria

1. THE Rate_Service SHALL allow register, query and convert operations to be invoked concurrently by multiple threads without external synchronization and without throwing exceptions caused by concurrent access.
2. WHILE multiple threads register rates concurrently, THE Rate_Service SHALL retain every registered Exchange_Rate that has a distinct Currency_Pair and effective Instant combination, such that after all registrations complete, the number of retained Exchange_Rates equals the number of distinct combinations registered.
3. WHILE one thread registers rates, THE Rate_Service SHALL return to each concurrent reader either the complete Rate_History from before the registration or the complete Rate_History from after that registration, and SHALL NOT return a partially updated Rate_History.
4. WHILE concurrent registrations for the same Currency_Pair and effective Instant occur, THE Rate_Service SHALL retain exactly one Exchange_Rate for that combination, being one of the registered values.
5. THE Exchange_Rate and Conversion_Result types SHALL be immutable, such that no field value of an instance can be changed after construction and a Rate_History returned to a caller cannot be used to modify the Rate_Service's stored data.
6. WHEN a thread blocked in the Rate_Service is interrupted, THE Rate_Service SHALL leave all stored Rate_History data identical to its state before the interrupted operation began or after that operation completed in full, and SHALL preserve the thread's interrupted status.

### Requirement 6: Testability and Conventions

**User Story:** As a developer, I want the service to be testable and consistent with project conventions, so that behavior is deterministic and maintainable.

#### Acceptance Criteria

1. THE Rate_Service SHALL accept its Clock as a constructor parameter and SHALL obtain every timestamp it uses from that Clock, with zero calls to the system clock (System.currentTimeMillis, Instant.now(), or LocalDateTime.now() without a Clock argument).
2. IF the Clock passed to the constructor is null, THEN THE Rate_Service SHALL throw a NullPointerException whose message names the Clock parameter, and SHALL NOT create a Rate_Service instance.
3. THE Rate_Service SHALL represent every exchange rate and monetary amount in its public API (parameters, return values, and fields) as BigDecimal, with zero occurrences of double, Double, float, or Float.
4. THE Rate_Service SHALL include, in the Javadoc of each of its public types (class, interface, enum, record), a statement that declares the type as either thread-safe or not thread-safe, and describes the concurrent-access guarantee in at least one sentence.
5. THE Rate_Service SHALL store all mutable state that is accessible from more than one thread in java.util.concurrent collections, with zero uses of non-concurrent collections (such as HashMap or ArrayList) for that state.
6. WHEN a test supplies a fixed or controllable Clock, THE Rate_Service SHALL produce identical results for identical inputs across 100 consecutive invocations.

### Requirement 7: Unit Test Suite

**User Story:** As a developer, I want a comprehensive unit test suite, so that I can trust the correctness and thread safety of the service.

#### Acceptance Criteria

1. THE test suite SHALL include at least one JUnit 5 test for each acceptance criterion in Requirements 1 to 4, covering both the nominal path and each error path defined in those criteria.
2. THE test suite SHALL use a fixed or controllable Clock for every test that depends on time, so that no test result depends on wall-clock time.
3. THE test suite SHALL include parameterized tests for each of the following inputs: (a) invalid Currency_Code values, including null, empty, blank, wrong length, lowercase, and non-alphabetic characters, with at least 6 distinct values; (b) non-positive rates, including zero, negative values, and the smallest negative value, with at least 3 distinct values; (c) rounding boundaries, including values just below, exactly at, and just above the rounding midpoint, with at least 3 distinct values.
4. THE test suite SHALL include concurrency tests that use ExecutorService with CountDownLatch or CyclicBarrier for thread coordination and an @Timeout annotation of at most 30 seconds on each concurrency test.
5. THE test suite SHALL NOT use Thread.sleep for synchronization in any test.
6. WHEN at least 32 threads are released simultaneously to each register a distinct rate, THE test suite SHALL verify that all registered rates are present after every thread has completed, with no missing, duplicated, or altered rates.
7. WHEN reader threads query and convert while writer threads register rates, THE test suite SHALL verify that every observed query and conversion result equals a value derived from a rate that was registered, and that no reader observes a partial, null, or corrupted value.
8. WHEN the command `mvn test` is run, THE test suite SHALL complete with 0 failures, 0 errors, and 0 skipped tests.
9. IF any concurrency test exceeds its @Timeout limit, THEN THE test suite SHALL report that test as failed and SHALL NOT hang the `mvn test` run.

this is a sample for requirements.md,design.md, tasks.md then generate code

1.
mkdir kiro-java-exchangerate

2. create steering like tech.md
//in kiro prompt
Set up steering for a modern Java 25 project using Maven, JUnit for testing, and standard thread-safe collections.

3. generate  requirements.md then design.md. tasks.md
//
In the Kiro panel, click + to start a new Spec and input your feature prompt:

User Prompt:"Build a thread-safe Currency Exchange Rate Service in Java. It should allow registering exchange rates between base and target currencies, converting amounts, and querying historical or current rates with thread safety. Include comprehensive unit tests."

4. run all tasks (double click tasks.md to show...)
generate all Java code

5. mvn clean package to verify all unit tests are passed

6. hook
in kiro prompt
add hook "when java code changed then run all unit tests"
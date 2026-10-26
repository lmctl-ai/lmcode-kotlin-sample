# lmcode Kotlin shop sample

**Agent entry point:** this is a concise, idiomatic Kotlin host for the lmcode VM. It uses the checked-in Java SDK in [vendor/lmcode-java](vendor/lmcode-java) and the portable canonical corpus in [sample-suite](sample-suite); no sibling checkout is required to build or test it.

```sh
java -version
mvn -version
npm install --global @lmctl-ai/lmctl@0.2.420   # Node 24+
mvn package -DskipTests
./test.sh
./bin/lmcode-kotlin-sample --version
```

## Write a host in Kotlin in ten steps

1. **Review the portable contract:** Read [CONTRACT.md](sample-suite/CONTRACT.md) to understand execution phases, exit codes (0 for success, 2 for CLI usage, 3 for VM/load, 4 for application failure), and domain lifetimes.
2. **Inspect the schema:** Check [host.schema.json](sample-suite/host.schema.json) for declared contexts (`shop: Shop`, `ledger: Ledger`), types, operator mappings, overloads, and enum definitions (`Currency`).
3. **Configure the mixed build:** Set up [pom.xml](pom.xml) with `kotlin-maven-plugin` and `build-helper-maven-plugin` to compile Kotlin alongside vendored Java SDK sources without external network dependencies.
4. **Define domain models:** Model domain state with standard Kotlin classes ([Shop.kt](src/main/kotlin/io/lmctl/lmcode/sample/Shop.kt#L10-L13))—`Money`, `Items`, `Shop`, and `Ledger`—keeping mutation within the host process arena.
5. **Declare contexts & testing catalog:** Call `registry.context("shop", "Shop").context("ledger", "Ledger")` and opt in to testing routines via `Stdlib.registerTesting(registry, null)`.
6. **Register callbacks with idiomatic lambdas:** Bind functions and methods with `registry.function(...)` and `registry.method(...)`. Escape the Kotlin keyword when constructing object values: `Value.`object`(type, instance)`.
7. **Handle parameter overloads explicitly:** Distinguish functions by parameter signatures (e.g. `money(int)` accepts integer cents, while `money(float)` accepts units and rounds half away from zero to cents).
8. **Preserve raw byte strings:** Treat `VmString` as immutable byte sequences. In [Protocol.kt](src/main/kotlin/io/lmctl/lmcode/sample/Protocol.kt), stream raw byte values directly to avoid corrupting arbitrary byte payloads or invalid UTF-8.
9. **Load and execute bytecode:** Read the exact embedded schema bytes, call `Program.load(...)`, bind context variables with `context.setObject(...)`, run the program, and map errors to standard JSON.
10. **Verify against the canonical corpus:** Run [test.sh](test.sh) to validate sync locks, compiler reproducibility, protocol conformity, and all 50 corpus cases.

## Feature coverage

| Feature | Corpus programs |
|---|---|
| Literals, arithmetic, conditions, loops, functions, switches | 001–008, 036, 039, 041–045 |
| Overloads, objects, enums, operators, conversions, fluent and void methods | 009–014, 034–035, 040 |
| Every standard collection, math, string and file signature | 015–024, 037–038, 048 |
| Located VM/host errors, numeric assertions, application failure | 025–033 |
| Raw/dedented heredocs and all outer splice types | 046–047 |
| Malformed bytecode and schema binding rejection | 090–091 |

The [canonical matrix](sample-suite/README.md) details all 50 programs. Every one of the 72 standard library and 27 host domain signatures is fully verified. [Protocol.kt](src/main/kotlin/io/lmctl/lmcode/sample/Protocol.kt) preserves raw note bytes and LF line endings across all platforms.

## Conversions

| lmcode type | Kotlin type | Rule |
|---|---|---|
| `string` | `VmString` | Treat as immutable byte sequences; decode to UTF-8 only when explicitly required. |
| `int` | `Long` | Signed 64-bit two's-complement arithmetic wrapping modulo 2^64. |
| `float` | `Double` | Preserve signed zero and IEEE-754 non-finites; format using C `%.15g` semantics. |
| `bool` | `Boolean` | Convert via `Value.asBool()`. |
| object handle | `Any` + `Value.object` | The host owns instances; the VM holds typed opaque references. |
| list/map | `StdCollections` | Handled per run via the standard library collection arena. |
| void | `Value.none()` | Return `Value.none()`; never return a null object for void methods. |
| host/VM error | `VmException` | Maintain error code and source locations (`line`, `column`) across boundaries. |

## How to run

Build the executable shaded JAR and run test suites:

```sh
# Clean build and package
mvn package -DskipTests

# Execute the entire suite
./test.sh

# Run directly via launcher or JAR
./bin/lmcode-kotlin-sample --version
./bin/lmcode-kotlin-sample --dsl sample-suite/bytecode/001-literals.dslb
java -jar target/lmcode-kotlin-sample.jar --dsl sample-suite/bytecode/014-ledger-methods.dslb
```

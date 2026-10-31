# lmcode Kotlin shop sample

**Agent entry point:** this is a concise, idiomatic Kotlin host for the lmcode VM. It uses the checked-in Java SDK in [vendor/lmcode-java](vendor/lmcode-java) and the portable canonical corpus in [sample-suite](sample-suite); no sibling checkout is required to build or test it.

```sh
java -version
mvn -version
mvn package -DskipTests
LMSCRIPT_BIN=/path/to/v6-entries-capable/lmscript ./test.sh
./bin/lmcode-kotlin-sample --version
```

The corpus is DSLB v6 (R49 rule entries), first published as `@lmctl-ai/lmctl` 0.2.444, which
reproduces every pinned `.dslb` byte-for-byte; 0.2.442/0.2.443 accept the rule syntax but still write v5.
With any older or unreleased compiler, set `LMSCRIPT_BIN` to a v6-entries-capable `lmscript` build
(see [sample-suite/README.md](sample-suite/README.md)). The vendored v6 SDK runs the pinned bytes directly
and refuses v5 files at load (093).

## Write a host in Kotlin in ten steps

1. **Review the portable contract:** Read [CONTRACT.md](sample-suite/CONTRACT.md) to understand execution phases, exit codes (0 for success, 2 for CLI usage, 3 for VM/load, 4 for application failure), and domain lifetimes.
2. **Inspect the schema:** Check [host.schema.json](sample-suite/host.schema.json) for declared contexts (`shop: Shop`, `ledger: Ledger`), types, operator mappings, overloads, enum definitions (`Currency`), and two SAM interfaces (`Adjuster`, `(Money) -> void`; `Score`, `(Money) -> int`) under `interfaces`.
3. **Configure the mixed build:** Set up [pom.xml](pom.xml) with `kotlin-maven-plugin` and `build-helper-maven-plugin` to compile Kotlin alongside vendored Java SDK sources without external network dependencies.
4. **Define domain models:** Model domain state with standard Kotlin classes ([Shop.kt](src/main/kotlin/io/lmctl/lmcode/sample/Shop.kt#L14-L17))—`Money`, `Items`, `Shop`, and `Ledger`—keeping mutation within the host process arena.
5. **Declare contexts & testing catalog:** Call `registry.context("shop", "Shop").context("ledger", "Ledger")` and opt in to testing routines via `Stdlib.registerTesting(registry, System.out)`.
6. **Register callbacks with idiomatic lambdas:** Bind functions and methods with `registry.function(...)` and `registry.method(...)`. Escape the Kotlin keyword when constructing object values: `Value.`object`(type, instance)`.
7. **Handle parameter overloads explicitly:** Distinguish functions by parameter signatures (e.g. `money(int)` accepts integer cents, while `money(float)` accepts units and rounds half away from zero to cents).
8. **Preserve raw byte strings:** Treat `VmString` as immutable byte sequences. In [Main.kt](src/main/kotlin/io/lmctl/lmcode/sample/Main.kt) and [Protocol.kt](src/main/kotlin/io/lmctl/lmcode/sample/Protocol.kt), stream raw byte values directly via `Reports.escapeBytes` to avoid corrupting arbitrary byte payloads or invalid UTF-8.
9. **Load and execute bytecode:** Read the exact embedded schema bytes, call `Program.load(...)`, bind context variables with `context.setObject(...)`, bind one invocation `ResultSink`, run the program, and map errors to standard JSON. After the body runs, call the program's rule entries (`Program.entries()` / `Program.call(...)`): each `@item` entry once per shop item in order, each `@total` entry once with the shop total, others never; non-void results print `entry NAME = VALUE` lines and the result object reports the call count as `"entries_called"`.
10. **Verify against the canonical corpus:** Run [test.sh](test.sh) to validate sync locks, compiler reproducibility, protocol conformity, and all 123 corpus cases.

## Invocation return frames

The invocation sink is bound before the body and retained through selected rule
entries. After successful dispatch and ledger validation, the host writes the
existing JSON diagnostic, then the terminal result frame. Load, run, entry, and
application failures emit no frame; no `$return` assignment produces a `null`
payload. The canonical cases 360–368 and
[`durable-memory/return-frames.md`](durable-memory/return-frames.md) define the
contract.

Successful stdout ends with:

```text
LMCODE_RETURN_BEGIN
null
LMCODE_RETURN_END
```

## Feature coverage

| Feature | Corpus programs |
|---|---|
| Literals, arithmetic, conditions, loops, functions, switches | 001–008, 036, 039, 043, 045 |
| Host overloads, objects, operators, conversion, chaining, void methods | 009–014, 034–035, 040 |
| Pure host function `fee(int)` | 002 |
| Every standard collection and container method | 015–018, 037–038, 101–104, 302–303, 312 |
| Every standard math overload + all 18 intrinsic math operations | 019–020, 025, 048 |
| Every standard string function, conversion and byte codec | 021–023, 301, 304–305, 311, 313 |
| Every standard file function | 024 |
| Located VM/host errors, testing failure, and application rule | 026–033 |
| int64 wrap/overflow, actual short-circuiting, host foreach | 041–044 |
| Raw/dedented inner heredocs and all four outer splice types | 046–047 |
| Native boundary conversions, 1 MiB strings, handles, 255 args, deep inline | 049–066 |
| `@NAME` rule method annotation and `implements` SAM interface | 067 |
| Rule entries called by the host after the body (`@item`/`@total`, `entries_called`) | 068–069 |
| Invocation return sink, retained entry writes, JSON frame and no-frame failures | 360–368 |
| Bad magic/schema/import binding rejection, v5 bytecode refused | 090–093 |
| JSON navigation/types and typed map/stack/queue/priority queues | 101–108 |
| Compiler list/map/JSON literals (`%w`, `%i`, `%f`, `%{...}`, `%json{...}`) | 201–205 |
| Strict Base64/hex byte codecs and independent map `keys()`/`values()` snapshots | 301–305, 311–313 |

The [canonical matrix](sample-suite/README.md) details all 123 cases. Every one of the 206 standard library and 45 host domain signatures is fully verified. Raw note bytes and LF line endings are preserved across all platforms.

## Conversions

| lmcode type | Kotlin type | Rule |
|---|---|---|
| `string` | `VmString` | Treat as immutable byte sequences; decode to UTF-8 only when explicitly required; strict Base64/hex codecs via `StdString`. |
| `int` | `Long` | Signed 64-bit two's-complement arithmetic wrapping modulo 2^64. |
| `float` | `Double` | Preserve signed zero and IEEE-754 non-finites; format using C `%.15g` semantics. |
| `bool` | `Boolean` | Convert via `Value.asBool()`. |
| object handle | `Any?` + `Value.object` | Host-owned instances tagged with schema type; `null` handle yields `money: null handle` on method invocation. |
| `StrList`, `IntList`, `FloatList`, `StrMap` | `StdCollections` | Created and invalidated per `Program.run` by the SDK registry; `keys()` and `values()` return independent snapshots. |
| `IntMap`, `FloatMap`, `{Int,Float,Str}{Stack,Queue,PQ}` | `StdContainers` | Insertion-ordered typed maps, LIFO stacks, FIFO queues, and stable min-first priority queues sharing the run budget; maps yield independent key/value snapshots. |
| `Json` (`json_parse`) | `StdJson.Node` | Immutable strict UTF-8 JSON AST (depth $\le 256$, int64, finite binary64, raw spelling in `to_json()`, borrowed child navigation). |
| void | `Value.none()` | Return `Value.none()`; never return a null object for void methods. |
| host/VM error | `VmException` | Host callbacks throw `VmException.host(...)` (`RUN_HOST`, exit 3); VM attaches line and column; application failure returns exit 4 (`APP_FAIL`). |

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

## lmscript tests and automatic native releases

```sh
npm install -g @lmctl-ai/lmctl
lmctl test.lms
# Equivalent: lmscript test.lms
# With GraalVM 21 and native compiler tools on PATH:
RUN_NATIVE=1 lmscript --no-preflight test.lms
```

Both executables run `.lms` files; `lmctl` also accepts URLs ending in `.lms`.
These tests require the checkout and run from its root. Python is used only
for lock validation/synchronization; test orchestration uses the canonical
lmscript drivers. Node supplies the byte-preserving process adapter.

[examples/rule-entries.lms](examples/rule-entries.lms) demonstrates `@item`
and `@total` functions implementing `Adjuster(Money) -> void`, plus an
undispatched `@never` entry. Tests compile it and compare the exact trace.
The canonical program 069 demonstrates an interface returning a value.

Every push to `main` builds with GraalVM Community 21 on Linux x64/arm64,
macOS x64/arm64 and Windows x64. Each job tests the JVM host and all 123
cases against the native executable. All five must pass before an automatic
`v0.4.<workflow run number>` release is published with checksums and notices.
Pull requests run the same checks without publishing. Native executables
require no JVM; Linux targets glibc and Windows requires the MSVC runtime.

```sh
lmscript install github:lmctl-ai/lmcode-kotlin-sample@latest
```

## Text templates

[examples/text-template.lms](examples/text-template.lms) renders ordinary text from a nested context with indexed lists, loops and boolean conditions. The standard `template_render(string, Json) -> string` function performs one pass; inserted values stay literal. Compile it with `lmscript --no-preflight examples/text-template.lms build/text-template.dslb`, then run the sample host with `--dsl build/text-template.dslb`.

The lmscript-driven suite runs 123 cases, including return-frame success/failure and template cases. See the [portable template contract](https://github.com/lmctl-ai/lmcode-cpp/blob/main/std/template.md).

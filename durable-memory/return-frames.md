# Invocation return frames

## Contract

For one `--dsl` invocation, `Main` creates and binds one Java SDK
`ResultSink` before the program body. It stays bound through selected rule
entries. The host does not clear or replace it between `Program.run` and
`Program.call`.

## Output ordering

Existing trace and entry-result lines are preserved. After dispatch and the
ledger rule succeed, the host writes the JSON diagnostic line followed by the
terminal `LMCODE_RETURN_BEGIN` / compact JSON / `LMCODE_RETURN_END` frame. Load,
body, entry, and application failures return before frame emission. No writes
means a `null` payload.

## SDK synchronization and verification

`scripts/sync-sdk.sh` uses an explicit Java-source allowlist. Keep
`ResultJson.java`, `ResultSink.java`, and `StdReturn.java` in that list when
syncing the ResultSink runtime. Canonical programs 360–368 and
`sample-suite/run-suite.lms` verify frame bytes, retained entry writes, and
failure suppression.

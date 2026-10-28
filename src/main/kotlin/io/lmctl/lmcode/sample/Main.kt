package io.lmctl.lmcode.sample

import io.lmctl.lmcode.Context
import io.lmctl.lmcode.Formatting
import io.lmctl.lmcode.Program
import io.lmctl.lmcode.Registry
import io.lmctl.lmcode.Reports
import io.lmctl.lmcode.Value
import io.lmctl.lmcode.VmException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object Main {
    const val VERSION = "0.2.0"

    private fun schemaBytes(): ByteArray {
        val input: InputStream = Main::class.java.getResourceAsStream("/sample.dsl.json")
            ?: throw IllegalStateException("embedded /sample.dsl.json not found")
        return input.use { it.readAllBytes() }
    }

    private fun usage(): Int {
        val json = """{"ok": false, "phase": "cli", "code": "CLI_USAGE", "message": "usage: --version | --dsl-schema [--json] | --dsl-signatures | --dsl FILE"}"""
        Protocol.printLf(json)
        return 2
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val status = run(args)
        if (status != 0) exitProcess(status)
    }

    fun run(args: Array<String>): Int {
        return try {
            val schema = schemaBytes()
            val host = Host()

            if (args.size == 1 && args[0] == "--version") {
                Protocol.printLf("lmcode-kotlin-sample $VERSION")
                return 0
            }
            if ((args.size == 1 || (args.size == 2 && args[1] == "--json")) && args[0] == "--dsl-schema") {
                System.out.write(schema)
                System.out.flush()
                return 0
            }
            if (args.size == 1 && args[0] == "--dsl-signatures") {
                for (sig in host.registry.signatures()) {
                    Protocol.printLf(sig)
                }
                return 0
            }
            if (args.size != 2 || args[0] != "--dsl") {
                return usage()
            }

            val bytecode = try {
                Files.readAllBytes(Path.of(args[1]))
            } catch (e: Exception) {
                return Reports.appFailure("cannot read sample input")
            }

            val program = try {
                Program.load(bytecode, schema, host.registry)
            } catch (e: VmException) {
                val message = when (e.code) {
                    "LOAD_SCHEMA_MISMATCH" -> "sample schema mismatch"
                    "LOAD_UNBOUND" -> "sample import mismatch"
                    else -> "invalid sample bytecode"
                }
                return Reports.error("load", VmException(e.code, message))
            }

            val context = host.context()
            try {
                program.run(context, host.registry)
            } catch (e: VmException) {
                return Reports.error("run", e)
            }

            // Rule entries (DSLB v6): after the body, @item entries run once per shop
            // item in order, @total once with the shop total, other annotations never.
            var entriesCalled = 0
            val programEntries = program.entries()
            try {
                for (i in programEntries.indices) {
                    when (programEntries[i].annotation()) {
                        "item" -> for (item in host.itemHandles()) {
                            callEntry(program, i, context, host.registry, Value.`object`("Money", item))
                            entriesCalled++
                        }
                        "total" -> {
                            callEntry(program, i, context, host.registry, host.totalHandle())
                            entriesCalled++
                        }
                    }
                }
            } catch (e: VmException) {
                return Reports.error("run", e)
            }

            if (host.ledger.balance < 0L) {
                return Reports.appFailure("ledger: negative balance")
            }

            val result = ByteArrayOutputStream(64)
            result.writeBytes(
                ("{\"balance\": ${host.ledger.balance}, \"entries\": ${host.ledger.entries}, \"note\": ")
                    .toByteArray(StandardCharsets.US_ASCII)
            )
            result.writeBytes(Reports.escapeBytes(host.ledger.note))
            result.writeBytes(", \"entries_called\": $entriesCalled}".toByteArray(StandardCharsets.US_ASCII))
            Reports.ok(result.toByteArray())
        } catch (e: Exception) {
            Reports.appFailure("cannot read sample input")
        }
    }

    /** Calls rule entry index with one argument; a non-void result prints `entry NAME = VALUE`. */
    private fun callEntry(program: Program, index: Int, context: Context, registry: Registry, argument: Value) {
        val entry = program.entries()[index]
        val result = program.call(index, context, registry, listOf(argument))
        if (result.kind() != Value.Kind.NONE) {
            val line = ByteArrayOutputStream(64)
            line.writeBytes("entry ".toByteArray(StandardCharsets.US_ASCII))
            line.writeBytes(entry.name().toByteArray(StandardCharsets.UTF_8))
            line.writeBytes(" = ".toByteArray(StandardCharsets.US_ASCII))
            line.writeBytes(valueText(result))
            line.write('\n'.code)
            val bytes = line.toByteArray()
            System.out.write(bytes, 0, bytes.size)
            System.out.flush()
        }
    }

    /** Same text as the language's str(): int decimal, float %.15g semantics, bool true/false. */
    private fun valueText(value: Value): ByteArray = when (value.kind()) {
        Value.Kind.INT -> value.asInt().toString().toByteArray(StandardCharsets.US_ASCII)
        Value.Kind.FLOAT -> Formatting.formatG15(value.asFloat()).toByteArray(StandardCharsets.US_ASCII)
        Value.Kind.BOOL -> (if (value.asBool()) "true" else "false").toByteArray(StandardCharsets.US_ASCII)
        Value.Kind.STR -> value.asString().bytes()
        else -> ByteArray(0)
    }
}

package io.lmctl.lmcode.sample

import io.lmctl.lmcode.Context
import io.lmctl.lmcode.Formatting
import io.lmctl.lmcode.Program
import io.lmctl.lmcode.Registry
import io.lmctl.lmcode.Reports
import io.lmctl.lmcode.ResultSink
import io.lmctl.lmcode.InvocationResults
import io.lmctl.lmcode.ResultContract
import io.lmctl.lmcode.Value
import io.lmctl.lmcode.VmException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

object Main {
    val VERSION = Main::class.java.getResourceAsStream("/sample-version.txt")!!.bufferedReader().use { it.readText().trim() }

    private fun schemaBytes(): ByteArray {
        val input: InputStream = Main::class.java.getResourceAsStream("/sample.dsl.json")
            ?: throw IllegalStateException("embedded /sample.dsl.json not found")
        return input.use { it.readAllBytes() }
    }

    private fun usage(): Int {
        val json = """{"ok": false, "phase": "cli", "code": "CLI_USAGE", "message": "usage: --version | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE"}"""
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
            val host = Host()
            val schema = schemaBytes()
            if (args.size == 1 && args[0] == "--version") { Protocol.printLf("lmcode-kotlin-sample $VERSION"); return 0 }
            if ((args.size == 1 || (args.size == 2 && args[1] == "--json")) && args[0] == "--lmcode-schema") { System.out.write(schema); System.out.flush(); return 0 }
            if (args.size == 1 && args[0] == "--lmcode-signatures") { host.registry.signatures().forEach(Protocol::printLf); return 0 }
            if (args.isEmpty() || args.size % 2 != 0) return usage()
            val files = mutableListOf<String>(); val priorities = mutableListOf<Int>()
            for (i in args.indices step 2) {
                if (args[i] !in listOf("--lmcode", "--first", "--last")) return usage()
                files.add(args[i + 1]); priorities.add(if (args[i] == "--first") 0 else if (args[i] == "--last") 2 else 1)
            }
            val results = InvocationResults.fromCommand(files)
            val order = files.indices.sortedBy { priorities[it] }
            val contract = ResultContract.fromSchema(schema)
            val context = host.context(); var entriesCalled = 0; var failed = false
            for (position in order) {
                val part = results.registrations()[position]; var phase = "load"
                try {
                    val program = try { Program.load(Files.readAllBytes(Path.of(part.file())), schema, host.registry) }
                    catch (e: VmException) { throw VmException(e.code, when(e.code) { "LOAD_SCHEMA_MISMATCH" -> "sample schema mismatch"; "LOAD_UNBOUND" -> "sample import mismatch"; else -> "invalid sample bytecode" }) }
                    results.select(part.index(), host.registry, contract); phase = "run"; program.run(context, host.registry)
                    for (i in program.entries().indices) when (program.entries()[i].annotation()) {
                        "item" -> host.shop.items.values.forEach { item -> callEntry(program, i, context, host.registry, Value.`object`("Money", item)); entriesCalled++ }
                        "total" -> { callEntry(program, i, context, host.registry, host.totalHandle()); entriesCalled++ }
                    }
                    results.complete(part.index())
                } catch (e: VmException) { results.fail(part.index(), phase, e); failed = true; break }
                catch (e: Exception) { results.fail(part.index(), "app", VmException("APP_FAIL", "cannot read sample input")); failed = true; break }
            }
            if (!failed && host.ledger.balance < 0L) { results.fail(null, "app", VmException("APP_FAIL", "ledger: negative balance")); failed = true }
            if (!failed) {
                val summary = ByteArrayOutputStream(64)
                summary.writeBytes("{\"host_summary\":{\"balance\": ${host.ledger.balance}, \"entries\": ${host.ledger.entries}, \"note\": ".toByteArray(StandardCharsets.US_ASCII))
                summary.writeBytes(Reports.escapeBytes(host.ledger.note))
                summary.writeBytes(", \"entries_called\": $entriesCalled}}\n".toByteArray(StandardCharsets.US_ASCII))
                System.out.write(summary.toByteArray())
            }
            results.writeFrame(System.out)
        } catch (e: VmException) { Reports.failureFrame("cli", e) }
        catch (e: Exception) { Reports.failureFrame("app", VmException("APP_FAIL", "cannot read sample input")) }
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

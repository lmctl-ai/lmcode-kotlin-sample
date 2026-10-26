package io.lmctl.lmcode.sample

import io.lmctl.lmcode.Context
import io.lmctl.lmcode.Registry
import io.lmctl.lmcode.Stdlib
import io.lmctl.lmcode.Value
import io.lmctl.lmcode.VmException
import io.lmctl.lmcode.VmString
import java.util.ArrayList
import java.util.Collections
import java.util.HexFormat
import java.util.LinkedHashMap

// Ordinary Kotlin objects represent domain state. The VM sees only
// typed Value handles; Context binds context variables per run.
class Money(var cents: Long)
class Items(val values: MutableList<Money> = mutableListOf())
class Shop(val items: Items = Items())
class Ledger(var balance: Long = 0L, var entries: Long = 0L, var note: VmString = VmString.fromString(""))

class Host {
    // Registry installs string/math/collection/file standard catalog by default.
    val registry = Registry()
    val shop = Shop()
    val ledger = Ledger()

    private fun valueObject(type: String, obj: Any?): Value = Value.`object`(type, obj)
    private fun money(cents: Long): Value = valueObject("Money", Money(cents))
    private fun text(s: String): Value = Value.ofString(VmString.fromString(s))

    private fun units(amount: Double): Value {
        // Round halfway away from zero to cents (matching C++ std::round).
        val scaled = amount * 100.0
        val cents = if (scaled >= 0.0) Math.floor(scaled + 0.5) else Math.ceil(scaled - 0.5)
        if (!cents.isFinite() || cents < -9223372036854775808.0 || cents >= 9223372036854775808.0) {
            throw VmException.host("money: amount out of range")
        }
        return money(cents.toLong())
    }

    private fun asMoney(value: Value): Money {
        val m = value.asObject() as? Money
            ?: throw VmException.host("money: null handle")
        return m
    }

    init {
        // Context variables declared in host.schema.json: "shop: Shop" and "ledger: Ledger"
        registry.context("shop", "Shop").context("ledger", "Ledger")
        registerConversions(registry)
        Stdlib.registerTesting(registry, System.out) // Opt-in: raw print and assert_close testing catalog.

        // Initial inventory
        for (cents in longArrayOf(125L, 250L, 375L)) {
            shop.items.values.add((money(cents).asObject() as Money))
        }

        // Functions: overloads are distinguished by parameter types (int = cents, float = units)
        registry.function("money", listOf("int"), "Money") { _, _, a -> money(a[0].asInt()) }
        registry.function("money", listOf("float"), "Money") { _, _, a -> units(a[0].asFloat()) }
        registry.function("fee", listOf("int"), "int") { _, _, a -> Value.ofInt(a[0].asInt() + 5L) }
        registry.function("sample_tmp", emptyList(), "string") { _, _, _ ->
            val path = System.getenv("SAMPLE_TMP")
            if (path.isNullOrEmpty()) throw VmException.host("SAMPLE_TMP is required")
            text(path)
        }

        // Money methods
        registry.method("Money", "amount", emptyList(), "int") { _, self, _ ->
            Value.ofInt(asMoney(self).cents)
        }
        registry.method("Money", "currency", emptyList(), "Currency") { _, self, _ ->
            asMoney(self)
            text("USD") // String-backed enum
        }
        registry.method("Money", "plus", listOf("Money"), "Money") { _, self, a ->
            money(asMoney(self).cents + asMoney(a[0]).cents)
        }
        registry.method("Money", "minus", listOf("Money"), "Money") { _, self, a ->
            money(asMoney(self).cents - asMoney(a[0]).cents)
        }
        registry.method("Money", "times", listOf("int"), "Money") { _, self, a ->
            money(asMoney(self).cents * a[0].asInt())
        }
        registry.method("Money", "neg", emptyList(), "Money") { _, self, _ ->
            money(-asMoney(self).cents)
        }
        registry.method("Money", "set", listOf("int"), "Money") { _, self, a ->
            asMoney(self).cents = a[0].asInt()
            self // returns:self normalizes to receiver
        }

        // Items methods (collection: len and at(int))
        registry.method("Items", "len", emptyList(), "int") { _, self, _ ->
            val items = self.asObject() as Items
            Value.ofInt(items.values.size.toLong())
        }
        registry.method("Items", "at", listOf("int"), "Money") { _, self, a ->
            val items = (self.asObject() as Items).values
            val idx = a[0].asInt()
            if (idx < 0 || idx >= items.size) throw VmException.host("items: index out of range")
            valueObject("Money", items[idx.toInt()])
        }

        // Shop methods
        registry.method("Shop", "items", emptyList(), "Items") { _, self, _ ->
            valueObject("Items", (self.asObject() as Shop).items)
        }
        registry.method("Shop", "total", emptyList(), "Money") { _, self, _ ->
            val items = (self.asObject() as Shop).items.values
            var total = 0L
            for (item in items) total += item.cents
            money(total)
        }
        registry.method("Shop", "price", listOf("int"), "Money") { _, _, a -> money(a[0].asInt()) }
        registry.method("Shop", "price", listOf("float"), "Money") { _, _, a -> units(a[0].asFloat()) }
        registry.method("Shop", "currency", emptyList(), "Currency") { _, _, _ -> text("USD") }
        registry.method("Shop", "label", emptyList(), "string") { _, _, _ -> text("sample shop") }

        // Ledger methods: fluent mutation, state inspection, and located errors
        registry.method("Ledger", "record", listOf("Money"), "Ledger") { _, self, a ->
            val l = self.asObject() as Ledger
            l.balance += asMoney(a[0]).cents
            l.entries += 1L
            self
        }
        registry.method("Ledger", "balance", emptyList(), "Money") { _, self, _ ->
            money((self.asObject() as Ledger).balance)
        }
        registry.method("Ledger", "count", emptyList(), "int") { _, self, _ ->
            Value.ofInt((self.asObject() as Ledger).entries)
        }
        registry.method("Ledger", "note", listOf("string"), "void") { _, self, a ->
            (self.asObject() as Ledger).note = a[0].asString()
            Value.none()
        }
        registry.method("Ledger", "note", emptyList(), "string") { _, self, _ ->
            Value.ofString((self.asObject() as Ledger).note)
        }
        registry.method("Ledger", "reject", listOf("string"), "void") { _, _, a ->
            throw VmException.host(a[0].asString().toString())
        }
    }

    private fun registerConversions(r: Registry) {
        r.function("host_int", listOf("int"), "int") { _, _, a -> Value.ofInt(a[0].asInt()) }
        r.function("host_float", listOf("float"), "float") { _, _, a -> Value.ofFloat(a[0].asFloat()) }
        r.function("host_bool", listOf("bool"), "bool") { _, _, a -> Value.ofBool(a[0].asBool()) }
        r.function("host_bytes", listOf("string"), "string") { _, _, a -> Value.ofString(a[0].asString()) }
        r.function("host_hex", listOf("string"), "string") { _, _, a ->
            Value.ofString(HexFormat.of().formatHex(a[0].asString().bytes()))
        }
        r.function("host_float_case", listOf("string"), "float") { _, _, a ->
            when (a[0].asString().toString()) {
                "-0" -> Value.ofFloat(-0.0)
                "nan" -> Value.ofFloat(Double.NaN)
                "inf" -> Value.ofFloat(Double.POSITIVE_INFINITY)
                "-inf" -> Value.ofFloat(Double.NEGATIVE_INFINITY)
                "large" -> Value.ofFloat(1e21)
                "subnormal" -> Value.ofFloat(Double.MIN_VALUE)
                else -> throw VmException.host("host_float_case: unknown case")
            }
        }
        r.function("host_echo_money", listOf("Money"), "Money") { _, _, a -> a[0] }
        r.function("host_same", listOf("Money", "Money"), "bool") { _, _, a ->
            Value.ofBool(a[0].asObject() === a[1].asObject())
        }
        r.function("host_null", emptyList(), "Money") { _, _, _ -> valueObject("Money", null) }
        r.function("host_is_null", listOf("Money"), "bool") { _, _, a ->
            Value.ofBool(a[0].asObject() == null)
        }
        r.function("host_wrong", emptyList(), "Money") { _, _, _ -> valueObject("Items", null) }
        r.function("host_void", emptyList(), "void") { _, _, _ -> Value.none() }
        r.function("host_str_list", listOf("StrList"), "StrList") { c, _, a ->
            val items = ArrayList<VmString>()
            for (v in Stdlib.collectionValues(a[0])) items.add(v.asString())
            items.add(VmString.fromString("host"))
            Stdlib.makeStrList(c, items)
        }
        r.function("host_int_list", listOf("IntList"), "IntList") { c, _, a ->
            val items = ArrayList<Long>()
            for (v in Stdlib.collectionValues(a[0])) items.add(v.asInt())
            items.add(9L)
            Stdlib.makeIntList(c, items)
        }
        r.function("host_float_list", listOf("FloatList"), "FloatList") { c, _, a ->
            val items = ArrayList<Double>()
            for (v in Stdlib.collectionValues(a[0])) items.add(v.asFloat())
            items.add(0.5)
            Stdlib.makeFloatList(c, items)
        }
        r.function("host_str_map", listOf("StrMap"), "StrMap") { c, _, a ->
            val entries = LinkedHashMap<VmString, VmString>(Stdlib.mapEntries(a[0]))
            entries[VmString.fromString("host")] = VmString.fromString("yes")
            Stdlib.makeStrMap(c, entries)
        }
        r.function("host_sum", Collections.nCopies(255, "int"), "int") { _, _, a ->
            var sum = 0L
            for (v in a) sum += v.asInt()
            Value.ofInt(sum)
        }
        r.function("host_long_" + "x".repeat(128), listOf("int"), "int") { _, _, a ->
            Value.ofInt(a[0].asInt())
        }
    }

    fun context(): Context {
        val ctx = Context()
        ctx.instructionLimit = Long.MAX_VALUE
        ctx.memoryLimitBytes = Long.MAX_VALUE
        ctx.setObject("shop", "Shop", shop)
        ctx.setObject("ledger", "Ledger", ledger)
        return ctx
    }
}

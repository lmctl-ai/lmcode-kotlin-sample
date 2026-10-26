package io.lmctl.lmcode.sample

import io.lmctl.lmcode.Context
import io.lmctl.lmcode.Registry
import io.lmctl.lmcode.Stdlib
import io.lmctl.lmcode.Value
import io.lmctl.lmcode.VmException
import io.lmctl.lmcode.VmString

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

    // Helper for Value.object (escaped since 'object' is a Kotlin keyword)
    private fun valueObject(type: String, obj: Any): Value = Value.`object`(type, obj)
    private fun money(cents: Long): Value = valueObject("Money", Money(cents))
    private fun text(s: String): Value = Value.ofString(VmString.fromString(s))

    private fun units(amount: Double): Value {
        // Round halfway away from zero to cents (matching C++ std::round).
        val raw = amount * 100.0
        val cents = if (raw >= 0.0) Math.floor(raw + 0.5) else -Math.floor(-raw + 0.5)
        if (!cents.isFinite() || cents < -9223372036854775808.0 || cents >= 9223372036854775808.0) {
            throw VmException.host("money: amount out of range")
        }
        return money(cents.toLong())
    }

    init {
        // Context variables declared in host.schema.json: "shop: Shop" and "ledger: Ledger"
        registry.context("shop", "Shop").context("ledger", "Ledger")
        Stdlib.registerTesting(registry, null) // Opt-in: raw print and assert_close testing catalog.

        // Initial inventory
        for (cents in longArrayOf(125L, 250L, 375L)) {
            shop.items.values.add(Money(cents))
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
            Value.ofInt((self.asObject() as Money).cents)
        }
        registry.method("Money", "currency", emptyList(), "Currency") { _, _, _ ->
            text("USD") // String-backed enum
        }
        registry.method("Money", "plus", listOf("Money"), "Money") { _, self, a ->
            val left = (self.asObject() as Money).cents
            val right = (a[0].asObject() as Money).cents
            money(left + right)
        }
        registry.method("Money", "minus", listOf("Money"), "Money") { _, self, a ->
            val left = (self.asObject() as Money).cents
            val right = (a[0].asObject() as Money).cents
            money(left - right)
        }
        registry.method("Money", "times", listOf("int"), "Money") { _, self, a ->
            val left = (self.asObject() as Money).cents
            val right = a[0].asInt()
            money(left * right)
        }
        registry.method("Money", "neg", emptyList(), "Money") { _, self, _ ->
            val left = (self.asObject() as Money).cents
            money(-left)
        }
        registry.method("Money", "set", listOf("int"), "Money") { _, self, a ->
            val m = self.asObject() as Money
            m.cents = a[0].asInt()
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
            val m = a[0].asObject() as Money
            l.balance += m.cents
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

    fun context(): Context {
        val ctx = Context()
        ctx.setObject("shop", "Shop", shop)
        ctx.setObject("ledger", "Ledger", ledger)
        return ctx
    }
}

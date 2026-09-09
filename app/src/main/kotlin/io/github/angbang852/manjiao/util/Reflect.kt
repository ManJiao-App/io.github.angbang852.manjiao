package io.github.angbang852.manjiao.util

import java.lang.reflect.Field
import java.lang.reflect.Method

object Reflect {
    // ★ Field 缓存（Toki 式 O(1) 读）：判定路径每秒对列表逐项读几十个字段，无缓存时
    // getDeclaredField 现场查找+NoSuchFieldException 异常创建（栈填充极贵）是 GC 风暴根因。
    // 负缓存同样关键：真不存在的字段此前每次查询都抛异常
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<String, Field>()
    private val negCache = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun findClass(name: String, cl: ClassLoader): Class<*>? = try {
        Class.forName(name, false, cl)
    } catch (_: Throwable) { null }

    fun readAny(obj: Any?, vararg names: String): Any? {
        if (obj == null) return null
        val start = obj.javaClass
        for (n in names) {
            val key = start.name + "#" + n
            val cached = fieldCache[key]
            if (cached != null) return try { cached.get(obj) } catch (_: Throwable) { null }
            if (negCache.contains(key)) continue
            var c: Class<*>? = start
            var found: Field? = null
            while (c != null && c != Any::class.java) {
                val f = try { c!!.getDeclaredField(n) } catch (_: Throwable) { null }
                if (f != null) { f.isAccessible = true; found = f; break }
                c = c.superclass
            }
            if (found != null) {
                fieldCache[key] = found
                return try { found.get(obj) } catch (_: Throwable) { null }
            }
            negCache.add(key)
        }
        return null
    }

    fun readString(obj: Any?, vararg names: String): String? = readAny(obj, *names)?.toString()

    fun readBool(obj: Any?, vararg names: String): Boolean {
        val v = readAny(obj, *names) ?: return false
        return when (v) { is Boolean -> v; is Number -> v.toInt() != 0; else -> false }
    }

    fun readLong(obj: Any?, vararg names: String): Long {
        val v = readAny(obj, *names) ?: return 0L
        return when (v) { is Long -> v; is Number -> v.toLong(); else -> 0L }
    }

    fun readInt(obj: Any?, vararg names: String): Int = readLong(obj, *names).toInt()

    fun writeAny(obj: Any?, name: String, value: Any?): Boolean {
        if (obj == null) return false
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            try {
                val f = c!!.getDeclaredField(name)
                f.isAccessible = true
                f.set(obj, value)
                return true
            } catch (_: Throwable) {}
            c = c.superclass
        }
        return false
    }

    fun allFields(obj: Any?): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        if (obj == null) return out
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive && f.type != Boolean::class.java) continue
                try {
                    f.isAccessible = true
                    val v = f.get(obj) ?: continue
                    if (v is String || v is Number || v is Boolean) out[f.name] = v
                } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return out
    }

    fun findFieldByType(obj: Any?, keyword: String): Any? {
        if (obj == null) return null
        var c: Class<*>? = obj.javaClass
        while (c != null && c != Any::class.java) {
            for (f in c!!.declaredFields) {
                if (f.type.isPrimitive || f.type == String::class.java) continue
                if (!f.type.name.contains(keyword)) continue
                try { f.isAccessible = true; return f.get(obj) } catch (_: Throwable) {}
            }
            c = c.superclass
        }
        return null
    }

    fun findMethod(cls: Class<*>, name: String, argCount: Int): Method? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c!!.declaredMethods) {
                if (m.name == name && m.parameterTypes.size == argCount) { m.isAccessible = true; return m }
            }
            c = c.superclass
        }
        return null
    }

    fun callMethod(obj: Any?, name: String, vararg args: Any?): Any? {
        if (obj == null) return null
        try {
            for (m in obj.javaClass.methods) {
                if (m.name != name) continue
                if (m.parameterTypes.size != args.size) continue
                m.isAccessible = true
                return m.invoke(obj, *args)
            }
        } catch (_: Throwable) {}
        return null
    }

    // 按参数类型精确匹配的方法调用（callMethod 按名字+参数个数宽松匹配，同名同参数个数的
    // 重载会误命中，如 loop-pager adapter 的 a(int) 与 a(String)）
    fun callMethodTyped(obj: Any?, name: String, argTypes: Array<Class<*>>, args: Array<Any?>): Any? {
        if (obj == null) return null
        try {
            var c: Class<*>? = obj.javaClass
            while (c != null && c != Any::class.java) {
                for (m in c!!.declaredMethods) {
                    if (m.name != name) continue
                    if (m.parameterTypes.size != argTypes.size) continue
                    if (!m.parameterTypes.contentEquals(argTypes)) continue
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                }
                c = c.superclass
            }
        } catch (_: Throwable) {}
        return null
    }
}

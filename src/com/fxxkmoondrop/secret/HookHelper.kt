package com.fxxkmoondrop.secret

import android.util.Log

/**
 * Reflection helpers replacing XposedHelpers (legacy API).
 * Used after migrating to libxposed API 102.
 */
object HookHelper {
    private const val TAG = "FxxkMoondrop"

    /** Equivalent of XposedHelpers.findClass(name, classLoader) */
    fun findClass(name: String, classLoader: ClassLoader): Class<*> {
        return Class.forName(name, true, classLoader)
    }

    /** Equivalent of XposedHelpers.callMethod(obj, methodName, args...) */
    fun callMethod(obj: Any?, methodName: String, vararg args: Any?): Any? {
        if (obj == null) return null
        var cls: Class<*> = obj.javaClass
        while (cls != Any::class.java) {
            for (m in cls.declaredMethods) {
                if (m.name != methodName || m.parameterTypes.size != args.size) continue
                try {
                    m.isAccessible = true
                    return m.invoke(obj, *args)
                } catch (_: IllegalArgumentException) {
                    // try next overload
                }
            }
            cls = cls.superclass
        }
        Log.w(TAG, "callMethod: method '$methodName' not found on ${obj.javaClass.name}")
        return null
    }

    /** Equivalent of XposedHelpers.callStaticMethod(cls, methodName, args...) */
    fun callStaticMethod(cls: Class<*>, methodName: String, vararg args: Any?): Any? {
        var c: Class<*>? = cls
        while (c != null && c != Any::class.java) {
            for (m in c.declaredMethods) {
                if (!java.lang.reflect.Modifier.isStatic(m.modifiers)) continue
                if (m.name != methodName || m.parameterTypes.size != args.size) continue
                try {
                    m.isAccessible = true
                    return m.invoke(null, *args)
                } catch (_: IllegalArgumentException) {
                    // try next overload
                }
            }
            c = c.superclass
        }
        Log.w(TAG, "callStaticMethod: method '$methodName' not found on ${cls.name}")
        return null
    }

    /** Equivalent of XposedHelpers.getObjectField(obj, fieldName) */
    fun getObjectField(obj: Any?, fieldName: String): Any? {
        if (obj == null) return null
        var cls: Class<*> = obj.javaClass
        while (cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                return f.get(obj)
            } catch (_: NoSuchFieldException) {
                // try superclass
            }
            cls = cls.superclass
        }
        Log.w(TAG, "getObjectField: field '$fieldName' not found on ${obj.javaClass.name}")
        return null
    }

    /** Equivalent of XposedHelpers.setObjectField(obj, fieldName, value) */
    fun setObjectField(obj: Any?, fieldName: String, value: Any?) {
        if (obj == null) return
        var cls: Class<*> = obj.javaClass
        while (cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(fieldName)
                f.isAccessible = true
                f.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                // try superclass
            }
            cls = cls.superclass
        }
        Log.w(TAG, "setObjectField: field '$fieldName' not found on ${obj.javaClass.name}")
    }
}

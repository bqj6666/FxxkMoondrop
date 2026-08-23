package com.fxxkmoondrop.secret

import java.util.Locale

class DeviceMatcher {
    companion object {
        @JvmStatic
        fun isMoondrop(name: String?): Boolean {
            if (name == null) return false
            val n = name.lowercase(Locale.ROOT)
            return n.contains("moondrop")
                    || n.contains("golden ages")
                    || n.contains("goldenages")
                    || n.contains("水月雨")
        }
    }
}

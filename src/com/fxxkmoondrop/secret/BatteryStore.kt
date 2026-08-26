package com.fxxkmoondrop.secret

import java.util.concurrent.ConcurrentHashMap

/**
 * 蓝牙设备电量缓存：
 *  - 系统蓝牙广播（ACTION_BATTERY_LEVEL_CHANGED）→ 单值
 *  - GAIA 直读（GaiaBleClient）→ 左右耳分离（LEFT=1, RIGHT=2）
 */
class BatteryStore {
    companion object {
        private val levels = ConcurrentHashMap<String, Int>()
        private val gaiaLeft = ConcurrentHashMap<String, Int>()
        private val gaiaRight = ConcurrentHashMap<String, Int>()
        private val gaiaCase = ConcurrentHashMap<String, Int>()

        /** 系统广播电量 */
        @JvmStatic
        fun set(address: String?, level: Int) {
            if (address != null && level >= 0) levels[address] = level
        }

        /** 系统广播电量读取 */
        @JvmStatic
        fun get(address: String?): Int {
            if (address == null) return -1
            return levels[address] ?: -1
        }

        /** GAIA 左右耳电量写入；batteryId: 1=左耳 2=右耳 3=充电盒 */
        @JvmStatic
        fun setGaiaLevel(address: String?, batteryId: Int, level: Int) {
            if (address == null || level < 0) return
            when (batteryId) {
                1 -> gaiaLeft[address] = level
                2 -> gaiaRight[address] = level
                3 -> gaiaCase[address] = level // alpha2.31: 充电盒独立缓存
            }
        }

        /** GAIA 左耳电量（纯 GAIA 表，不回退系统值；用于弹窗左右耳优先显示） */
        @JvmStatic
        fun getGaiaLeft(address: String?): Int {
            if (address == null) return -1
            return gaiaLeft[address] ?: -1
        }

        /** GAIA 右耳电量（纯 GAIA 表，不回退系统值） */
        @JvmStatic
        fun getGaiaRight(address: String?): Int {
            if (address == null) return -1
            return gaiaRight[address] ?: -1
        }

        /** GAIA 充电盒电量 */
        @JvmStatic
        fun getGaiaCase(address: String?): Int {
            if (address == null) return -1
            return gaiaCase[address] ?: -1
        }

        /** GAIA 左耳电量，无则回退系统值 */
        @JvmStatic
        fun getLeft(address: String?): Int {
            if (address == null) return -1
            return gaiaLeft[address] ?: get(address)
        }

        /** GAIA 右耳电量，无则回退系统值 */
        @JvmStatic
        fun getRight(address: String?): Int {
            if (address == null) return -1
            return gaiaRight[address] ?: get(address)
        }

        @JvmStatic
        fun clearGaia(address: String?) {
            if (address == null) return
            gaiaLeft.remove(address)
            gaiaRight.remove(address)
            gaiaCase.remove(address)
        }
    }
}

package com.fxxkmoondrop.secret

import java.util.UUID

/**
 * GAIA V3 协议常量定义（alpha2.27 从 GaiaBleClient companion 提取）。
 * 所有常量原先散落在 GaiaBleClient.companion 内，现集中管理便于维护。
 */
object GaiaConstants {
    const val TAG = "GaiaBleClient"
    const val PKG_GMS = "com.google.android.gms"
    const val GATT_PENDING_TIMEOUT_MS = 12000L
    const val SCAN_DURATION_MS = 8000L

    // GATT Service / Characteristic UUIDs
    val UUID_SERVICE = UUID.fromString("00001100-d102-11e1-9b23-00025b00a5a5")
    val UUID_COMMAND = UUID.fromString("00001101-d102-11e1-9b23-00025b00a5a5")
    val UUID_RESPONSE = UUID.fromString("00001102-d102-11e1-9b23-00025b00a5a5")
    val UUID_DATA = UUID.fromString("00001103-d102-11e1-9b23-00025b00a5a5")
    val UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // GAIA vendor & feature IDs
    const val GAIA_VENDOR = 0x1D
    const val FEATURE_BASIC = 0x00
    const val FEATURE_BATTERY = 0x0D
    const val FEATURE_ANC_V2 = 0x20
    const val FEATURE_ANC_V1 = 0x02
    const val F_AUDIO_CURATION = 0x08

    // Command IDs
    const val CMD_AC_GET_MODE = 0x03
    const val CMD_AC_SET_MODE = 0x04
    const val CMD_AC_GET_SWITCH_CONF = 0x29
    const val CMD_AC_SET_SWITCH_CONF = 0x2A
    const val TYPE_COMMAND = 0
    const val TYPE_NOTIFICATION = 1
    const val TYPE_RESPONSE = 2
    const val CMD_GET_BATTERY_LEVELS = 0x01
    const val CMD_GET_CURRENT_MODE = 0x03
    const val CMD_SET_CURRENT_MODE = 0x04
    const val CMD_GET_SUPPORTED_FEATURES = 0x01
    const val CMD_REGISTER_NOTIFICATION = 0x07
    const val CMD_GET_VARIANT = 0x04
    const val CMD_GET_APP_VERSION = 0x05
    const val CMD_ANC1_GET_ANC_STATE = 0x01
    const val CMD_ANC1_SET_ANC_STATE = 0x02

    // Battery IDs
    const val BATTERY_LEFT = 1
    const val BATTERY_RIGHT = 2
    const val BATTERY_CASE = 3

    // Broadcast actions
    const val ACTION_LE_ADDR_FOUND = "com.fxxkmoondrop.secret.ACTION_LE_ADDR_FOUND"
    const val ACTION_REQ_LE_SCAN = "com.fxxkmoondrop.secret.ACTION_REQ_LE_SCAN"
    const val EXTRA_LE_ADDR = "addr"
}

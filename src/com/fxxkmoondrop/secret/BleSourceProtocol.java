package com.fxxkmoondrop.secret;

import java.util.Arrays;

/**
 * Moondrop 私有 BleSourceSwitch 协议（9ECA0000）完整协议层。
 * 逆向自官方 App：com.moondroplab.moondrop.moondrop_app.native.ble.source.*
 *
 * 帧格式（LE 小端）：
 *   [0]=0xA5 MAGIC, [1]=0x01 版本, [2]=帧类型(1=CMD/2=RESP/3=NOTIF),
 *   [3]=命令/通知 ID, [4]=序号, [5]=载荷长度(<=14), [6..]=载荷
 *
 * 本类只做编解码与模型，不涉及 GATT，可在任何层复用。
 */
public final class BleSourceProtocol {

    private BleSourceProtocol() {}

    // ---------- 帧常量 ----------
    public static final int MAGIC = 0xA5;
    public static final int PROTOCOL_VERSION = 1;
    public static final int HEADER_SIZE = 6;
    public static final int MAX_PAYLOAD = 14;

    public static final int FRAME_COMMAND = 1;      // 官方 BleSourceFrameType.COMMAND=1
    public static final int FRAME_RESPONSE = 2;     // 官方 RESPONSE=2
    public static final int FRAME_NOTIFICATION = 3; // 官方 NOTIFICATION=3

    // ---------- 命令（BleSourceCommand） ----------
    public static final int CMD_GET_AUDIO_SOURCE = 1;
    public static final int CMD_SET_AUDIO_SOURCE = 2;
    public static final int CMD_GET_CAPABILITY = 3;
    public static final int CMD_GET_FW_VERSION = 4;
    public static final int CMD_GET_DEVICE_VOLUME = 5;
    public static final int CMD_SET_DEVICE_VOLUME = 6;
    public static final int CMD_GET_PRESET_EQ = 7;
    public static final int CMD_SET_PRESET_EQ = 8;
    public static final int CMD_GET_PEQ_CONFIG = 9;
    public static final int CMD_SET_PEQ_PREGAIN = 10;
    public static final int CMD_GET_PEQ_POINT = 11;
    public static final int CMD_SET_PEQ_POINT = 12;
    public static final int CMD_COMMIT_PEQ = 13;
    public static final int CMD_GET_MIC_GAIN = 14;
    public static final int CMD_SET_MIC_GAIN = 15;
    public static final int CMD_GET_EARBUD_COLOR = 18;
    public static final int CMD_GET_EARBUD_LANGUAGE = 19;
    public static final int CMD_GET_EARBUD_SN_LEFT = 20;
    public static final int CMD_GET_EARBUD_SN_RIGHT = 21;
    public static final int CMD_PING = 127;

    // ---------- 通知（BleSourceNotificationId） ----------
    public static final int NOTIF_AUDIO_SOURCE_CHANGED = 129;
    public static final int NOTIF_SWITCH_STATE_CHANGED = 130;
    public static final int NOTIF_CAPABILITY_CHANGED = 131;
    public static final int NOTIF_VOLUME_CHANGED = 133;
    public static final int NOTIF_PRESET_EQ_CHANGED = 134;
    public static final int NOTIF_PEQ_COMMITTED = 135;
    public static final int NOTIF_MIC_GAIN_CHANGED = 136;

    // ---------- 状态码（BleSourceStatusCode） ----------
    public static final int ST_OK = 0;
    public static final int ST_UNSUPPORTED_VERSION = 1;
    public static final int ST_UNSUPPORTED_CMD = 2;
    public static final int ST_INVALID_LENGTH = 3;
    public static final int ST_UNSUPPORTED_SOURCE = 4;
    public static final int ST_BUSY_SWITCHING = 5;
    public static final int ST_AUTH_REQUIRED = 6;
    public static final int ST_DENIED_BY_MODE = 7;
    public static final int ST_TIMEOUT = 8;
    public static final int ST_SAME_SOURCE = 9;
    public static final int ST_NO_SIGNAL = 10;
    public static final int ST_INTERNAL_ERROR = 11;
    public static final int ST_INVALID_VALUE = 12;
    public static final int ST_VOLUME_UNSUPPORTED = 13;
    public static final int ST_READ_ONLY_PRESET = 14;
    public static final int ST_REVISION_CONFLICT = 15;
    public static final int ST_INVALID_OFFSET = 16;
    public static final int ST_STORAGE_ERROR = 17;
    public static final int ST_INFO_UNAVAILABLE = 18;
    public static final int ST_UNKNOWN = 255;

    // ---------- 过渡状态（BleSourceTransitionState） ----------
    public static final int TRANS_STABLE = 0;
    public static final int TRANS_SWITCHING = 1;
    public static final int TRANS_WAITING_SIGNAL = 2;
    public static final int TRANS_FAILED_REVERTED = 3;
    public static final int TRANS_UNKNOWN = 255;

    // ---------- 激活原因（BleSourceActiveReason） ----------
    public static final int REASON_APP = 0;
    public static final int REASON_KEY = 1;
    public static final int REASON_AUTO_FALLBACK = 2;
    public static final int REASON_SIGNAL_LOST = 3;
    public static final int REASON_POWER_ON_DEFAULT = 4;
    public static final int REASON_UNKNOWN = 255;

    // ---------- 音源（SourceId） ----------
    public static final int SRC_BLUETOOTH = 0;
    public static final int SRC_USB_AUDIO = 1;
    public static final int SRC_WIRELESS_2_4G = 2;
    public static final int SRC_AUX_LINE_IN = 3;
    public static final int SRC_OPTICAL_SPDIF = 4;
    public static final int SRC_COAXIAL = 5;
    public static final int SRC_HDMI_ARC = 6;
    public static final int SRC_LOCAL_PLAYER = 7;
    public static final int SRC_AUTO_SELECT = 127;
    public static final int SRC_NO_SIGNAL = 254;
    public static final int SRC_UNKNOWN_INVALID = 255;

    // ---------- 切换选项（SetOption 位掩码） ----------
    public static final int OPT_PERSIST_DEFAULT = 1;
    public static final int OPT_FORCE_NO_SIGNAL = 2;
    public static final int OPT_MUTE_DURING_SWITCH = 4;
    public static final int OPT_NO_BT_AUTO_RESUME = 8;

    // ---------- EQ ----------
    public static final int PEQ_POINT_COUNT = 32;
    public static final int USER_PEQ_PRESET = 7;

    // 滤波器（BlePeqFilter）
    public static final int FILTER_PEAKING = 0;
    public static final int FILTER_LOW_SHELF = 1;
    public static final int FILTER_HIGH_SHELF = 2;
    public static final int FILTER_LOW_PASS = 3;
    public static final int FILTER_HIGH_PASS = 4;
    public static final int FILTER_BAND_PASS = 5;
    public static final int FILTER_NOTCH = 6;
    public static final int FILTER_ALL_PASS = 7;
    public static final int FILTER_UNKNOWN = 255;

    // ---------- 构建类型（BleSourceBuildType） ----------
    public static final int BUILD_RELEASE = 0;
    public static final int BUILD_BETA = 1;
    public static final int BUILD_ENGINEERING = 2;
    public static final int BUILD_FACTORY = 3;
    public static final int BUILD_UNKNOWN = 255;

    // ---------- 字节工具 ----------
    public static int u8(byte b) { return b & 0xFF; }

    public static int u16le(byte[] b, int off) {
        return u8(b[off]) | (u8(b[off + 1]) << 8);
    }

    public static int i16le(byte[] b, int off) {
        return (short) u16le(b, off);
    }

    public static long u32le(byte[] b, int off) {
        return (u8(b[off]) | ((long) u8(b[off + 1]) << 8)
                | ((long) u8(b[off + 2]) << 16) | ((long) u8(b[off + 3]) << 24)) & 0xFFFFFFFFL;
    }

    public static byte[] u16leBytes(int v) {
        if (v < 0 || v > 0xFFFF) throw new IllegalArgumentException("u16 out of range: " + v);
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    public static byte[] i16leBytes(int v) {
        if (v < -32768 || v > 32767) throw new IllegalArgumentException("i16 out of range: " + v);
        return new byte[]{(byte) v, (byte) (v >> 8)};
    }

    private static byte[] concat(byte[]... arrs) {
        int len = 0;
        for (byte[] a : arrs) len += a.length;
        byte[] out = new byte[len];
        int p = 0;
        for (byte[] a : arrs) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        return out;
    }

    // ---------- 帧构造 ----------
    /** 构造协议帧（完整 6 字节头 + payload）。payload 必须 <= 14 字节。 */
    public static byte[] buildFrame(int frameType, int msgId, int seq, byte[] payload) {
        byte[] p = payload == null ? new byte[0] : payload;
        if (p.length > MAX_PAYLOAD) throw new IllegalArgumentException("payload > 14 bytes");
        byte[] out = new byte[HEADER_SIZE + p.length];
        out[0] = (byte) MAGIC;
        out[1] = (byte) PROTOCOL_VERSION;
        out[2] = (byte) frameType;
        out[3] = (byte) msgId;
        out[4] = (byte) seq;
        out[5] = (byte) p.length;
        if (p.length > 0) System.arraycopy(p, 0, out, HEADER_SIZE, p.length);
        return out;
    }

    /** 构造命令帧。 */
    public static byte[] buildCommand(int cmdId, int seq, byte[] payload) {
        return buildFrame(FRAME_COMMAND, cmdId, seq, payload);
    }

    // ---------- 各命令构造（与官方 BleSourceSwitchFrames 各 build* 一致） ----------
    public static byte[] cmdGetAudioSource(int seq) { return buildCommand(CMD_GET_AUDIO_SOURCE, seq, null); }

    /** sourceId + options + fadeSeconds(0..60)，官方默认 options=0, fade=5 */
    public static byte[] cmdSetAudioSource(int seq, int sourceId, int options, int fadeSeconds) {
        int fade = Math.max(0, Math.min(60, fadeSeconds));
        return buildCommand(CMD_SET_AUDIO_SOURCE, seq, new byte[]{(byte) sourceId, (byte) options, (byte) fade});
    }

    public static byte[] cmdSetAudioSource(int seq, int sourceId) { return cmdSetAudioSource(seq, sourceId, 0, 5); }

    /** page 0..15 */
    public static byte[] cmdGetCapability(int seq, int page) {
        return buildCommand(CMD_GET_CAPABILITY, seq, new byte[]{(byte) Math.max(0, Math.min(15, page))});
    }

    public static byte[] cmdGetFirmwareVersion(int seq) { return buildCommand(CMD_GET_FW_VERSION, seq, null); }
    public static byte[] cmdGetDeviceVolume(int seq) { return buildCommand(CMD_GET_DEVICE_VOLUME, seq, null); }

    /** volumeMode / volumeValue / optionFlags，各 0..255（官方 setDeviceVolume 三参数） */
    public static byte[] cmdSetDeviceVolume(int seq, int volumeMode, int volumeValue, int optionFlags) {
        return buildCommand(CMD_SET_DEVICE_VOLUME, seq,
                new byte[]{(byte) volumeMode, (byte) volumeValue, (byte) optionFlags});
    }

    public static byte[] cmdGetPresetEq(int seq) { return buildCommand(CMD_GET_PRESET_EQ, seq, null); }

    /** presetIndex 0..7 */
    public static byte[] cmdSetPresetEq(int seq, int presetIndex) {
        return buildCommand(CMD_SET_PRESET_EQ, seq, new byte[]{(byte) presetIndex});
    }

    public static byte[] cmdGetPeqConfig(int seq) { return buildCommand(CMD_GET_PEQ_CONFIG, seq, null); }

    /** preGain 单位 0.01 dB，范围 -12800..12799；预置 USER_PEQ_PRESET=7 */
    public static byte[] cmdSetPeqPregain(int seq, int preGain) {
        if (preGain < -12800 || preGain >= 12800) throw new IllegalArgumentException("preGain range -12800..12799");
        return buildCommand(CMD_SET_PEQ_PREGAIN, seq, concat(new byte[]{(byte) USER_PEQ_PRESET}, i16leBytes(preGain)));
    }

    /** pointIndex 0..31 */
    public static byte[] cmdGetPeqPoint(int seq, int pointIndex) {
        if (pointIndex < 0 || pointIndex >= PEQ_POINT_COUNT) throw new IllegalArgumentException("pointIndex 0..31");
        return buildCommand(CMD_GET_PEQ_POINT, seq, new byte[]{(byte) pointIndex});
    }

    /** 官方 setPoint：index(0..31) freqHz(20..20000) gainRaw(i16) qRaw(1..65535) filter(0..7) */
    public static byte[] cmdSetPeqPoint(int seq, int index, int freqHz, int gainRaw, int qRaw, int filterId) {
        if (index < 0 || index >= PEQ_POINT_COUNT) throw new IllegalArgumentException("pointIndex 0..31");
        if (freqHz < 20 || freqHz > 20000) throw new IllegalArgumentException("freq 20..20000");
        if (qRaw < 1 || qRaw > 0xFFFF) throw new IllegalArgumentException("q 1..65535");
        if (filterId < 0 || filterId > 7) throw new IllegalArgumentException("filter 0..7");
        return buildCommand(CMD_SET_PEQ_POINT, seq, concat(
                new byte[]{(byte) index}, u16leBytes(freqHz), i16leBytes(gainRaw),
                u16leBytes(qRaw), new byte[]{(byte) filterId}));
    }

    /** action 0..2（如 0=保存？），revision 0..65535 */
    public static byte[] cmdCommitPeq(int seq, int action, int revision) {
        if (action < 0 || action > 2) throw new IllegalArgumentException("action 0..2");
        if (revision < 0 || revision > 0xFFFF) throw new IllegalArgumentException("revision 0..65535");
        return buildCommand(CMD_COMMIT_PEQ, seq, concat(new byte[]{(byte) action}, u16leBytes(revision)));
    }

    public static byte[] cmdGetMicGain(int seq) { return buildCommand(CMD_GET_MIC_GAIN, seq, null); }

    /** micGainRaw 单位 0.1 dB，范围 -1280..1280（官方 i16） */
    public static byte[] cmdSetMicGain(int seq, int micGainRaw) {
        if (micGainRaw < -1280 || micGainRaw > 1280) throw new IllegalArgumentException("micGain -1280..1280");
        return buildCommand(CMD_SET_MIC_GAIN, seq, i16leBytes(micGainRaw));
    }

    public static byte[] cmdGetEarbudColor(int seq) { return buildCommand(CMD_GET_EARBUD_COLOR, seq, null); }
    public static byte[] cmdGetEarbudLanguage(int seq) { return buildCommand(CMD_GET_EARBUD_LANGUAGE, seq, null); }

    /** SN 分块：offset 0 或 10 */
    public static byte[] cmdGetEarbudSn(int seq, int cmdId, int offset) {
        return buildCommand(cmdId, seq, new byte[]{(byte) offset});
    }

    public static byte[] cmdGetEarbudSnLeft(int seq, int offset) { return cmdGetEarbudSn(seq, CMD_GET_EARBUD_SN_LEFT, offset); }
    public static byte[] cmdGetEarbudSnRight(int seq, int offset) { return cmdGetEarbudSn(seq, CMD_GET_EARBUD_SN_RIGHT, offset); }

    public static byte[] cmdPing(int seq) { return buildCommand(CMD_PING, seq, null); }

    // ---------- 模型 ----------
    public static final class SrcStatus {
        public final int statusCode, currentSource, targetSource, transitionState, activeReason;
        public SrcStatus(int statusCode, int currentSource, int targetSource, int transitionState, int activeReason) {
            this.statusCode = statusCode; this.currentSource = currentSource;
            this.targetSource = targetSource; this.transitionState = transitionState; this.activeReason = activeReason;
        }
        public boolean ok() { return statusCode == ST_OK; }
        public boolean stableSuccess() { return statusCode == ST_OK && transitionState == TRANS_STABLE; }
        @Override public String toString() {
            return "SrcStatus{st=" + statusCode + ",cur=" + currentSource + ",tgt=" + targetSource
                    + ",trans=" + transitionState + ",reason=" + activeReason + "}";
        }
    }

    public static final class SrcFwInfo {
        public final int protocolMajor, protocolMinor, featureFlags, major, minor, patch, buildType;
        public final long buildId;
        public SrcFwInfo(int pm, int pmi, int flags, int maj, int min, int pat, int bt, long bid) {
            protocolMajor = pm; protocolMinor = pmi; featureFlags = flags;
            major = maj; minor = min; patch = pat; buildType = bt; buildId = bid;
        }
        public boolean supportsPresetEq()     { return (featureFlags & 0x01) != 0; }
        public boolean supportsPeq32()        { return (featureFlags & 0x02) != 0; }
        public boolean supportsAudioSource()  { return (featureFlags & 0x04) != 0; }
        public boolean supportsDeviceVolume() { return (featureFlags & 0x08) != 0; }
        public boolean supportsMicGain()      { return (featureFlags & 0x10) != 0; }
        public boolean supportsDeviceVolumeMute() { return (featureFlags & 0x20) != 0; }
        public boolean supportsDeviceVolumeStep() { return (featureFlags & 0x40) != 0; }
        @Override public String toString() {
            return "SrcFwInfo{proto=" + protocolMajor + "." + protocolMinor + ",fw=" + major + "." + minor + "." + patch
                    + ",build=" + buildType + ",id=" + buildId + ",flags=0x" + Integer.toHexString(featureFlags) + "}";
        }
    }

    public static final class SrcCapabilityPage {
        public final int statusCode, pageIndex, totalPages;
        public final int[][] entries; // [i][0]=sourceId, [i][1]=flags
        public static final int CAP_AUTH_REQUIRED = 2;
        public static final int CAP_PERSIST_DEFAULT = 4;
        public static final int CAP_AUTO_SELECT = 8;
        public SrcCapabilityPage(int st, int page, int total, int[][] entries) {
            statusCode = st; pageIndex = page; totalPages = total; this.entries = entries;
        }
    }

    public static final class SrcDeviceVolume {
        public final int statusCode, currentVolume, minVolume, maxVolume, step, muteState;
        public SrcDeviceVolume(int st, int cur, int min, int max, int step, int mute) {
            statusCode = st; currentVolume = cur; minVolume = min; maxVolume = max; this.step = step; muteState = mute;
        }
    }

    public static final class SrcPresetEqInfo {
        public final int statusCode, currentPreset, presetCount, editablePreset;
        public SrcPresetEqInfo(int st, int current, int count, int editable) {
            statusCode = st; currentPreset = current; presetCount = count; editablePreset = editable;
        }
    }

    public static final class SrcPresetEqChange {
        public final int statusCode, currentPreset, previousPreset, editablePreset;
        public SrcPresetEqChange(int st, int current, int previous, int editable) {
            statusCode = st; currentPreset = current; previousPreset = previous; editablePreset = editable;
        }
    }

    public static final class SrcPeqConfig {
        public final int statusCode, preset, pointCount, revision, preGainRaw;
        public final boolean dirty;
        public SrcPeqConfig(int st, int preset, int count, int rev, int preGain, boolean dirty) {
            statusCode = st; this.preset = preset; pointCount = count; revision = rev; preGainRaw = preGain; this.dirty = dirty;
        }
    }

    public static final class SrcPeqPreGain {
        public final int statusCode, preset, preGainRaw;
        public final boolean dirty;
        public SrcPeqPreGain(int st, int preset, int preGain, boolean dirty) {
            statusCode = st; this.preset = preset; preGainRaw = preGain; this.dirty = dirty;
        }
    }

    public static final class SrcPeqPoint {
        public final int statusCode, pointIndex, frequencyHz, gainRaw, qRaw, filter;
        public SrcPeqPoint(int st, int idx, int freq, int gain, int q, int filter) {
            statusCode = st; pointIndex = idx; frequencyHz = freq; gainRaw = gain; qRaw = q; this.filter = filter;
        }
    }

    public static final class SrcPeqCommitResult {
        public final int statusCode, revision, activePreset;
        public final boolean dirty;
        public SrcPeqCommitResult(int st, int rev, int active, boolean dirty) {
            statusCode = st; revision = rev; activePreset = active; this.dirty = dirty;
        }
    }

    public static final class SrcMicGain {
        public final int statusCode, currentRaw, minRaw, maxRaw, stepRaw;
        public SrcMicGain(int st, int current, int min, int max, int step) {
            statusCode = st; currentRaw = current; minRaw = min; maxRaw = max; stepRaw = step;
        }
    }

    public static final class SrcEarbudInfo {
        public final int statusCode, value;
        public SrcEarbudInfo(int st, int value) { statusCode = st; this.value = value; }
    }

    public static final class SrcEarbudSnChunk {
        public final int statusCode, totalLength, offset;
        public final byte[] bytes;
        public SrcEarbudSnChunk(int st, int total, int offset, byte[] bytes) {
            statusCode = st; totalLength = total; this.offset = offset; this.bytes = bytes;
        }
    }

    // ---------- 统一响应 ----------
    public static final int R_STATUS = 1, R_FW = 2, R_CAPABILITY = 3, R_VOLUME = 4,
            R_PRESET_INFO = 5, R_PRESET_CHANGE = 6, R_PEQ_CONFIG = 7, R_PEQ_PREGAIN = 8,
            R_PEQ_POINT = 9, R_PEQ_COMMIT = 10, R_MIC_GAIN = 11, R_EARBUD_INFO = 12,
            R_SN_CHUNK = 13, R_RAW = 14;

    public static final class SrcResponse {
        public final int commandId, seq, kind;
        public final SrcStatus status;             // R_STATUS
        public final int fwStatus; public final SrcFwInfo fw; // R_FW
        public final SrcCapabilityPage capability; // R_CAPABILITY
        public final SrcDeviceVolume volume;       // R_VOLUME
        public final SrcPresetEqInfo presetInfo;   // R_PRESET_INFO
        public final SrcPresetEqChange presetChange; // R_PRESET_CHANGE
        public final SrcPeqConfig peqConfig;       // R_PEQ_CONFIG
        public final SrcPeqPreGain peqPreGain;     // R_PEQ_PREGAIN
        public final SrcPeqPoint peqPoint;         // R_PEQ_POINT
        public final SrcPeqCommitResult peqCommit; // R_PEQ_COMMIT
        public final SrcMicGain micGain;           // R_MIC_GAIN
        public final SrcEarbudInfo earbudInfo;     // R_EARBUD_INFO
        public final SrcEarbudSnChunk snChunk;     // R_SN_CHUNK
        public final byte[] raw;                   // R_RAW
        SrcResponse(int cmdId, int seq, int kind, SrcStatus status, int fwStatus, SrcFwInfo fw,
                    SrcCapabilityPage capability, SrcDeviceVolume volume, SrcPresetEqInfo presetInfo,
                    SrcPresetEqChange presetChange, SrcPeqConfig peqConfig, SrcPeqPreGain peqPreGain,
                    SrcPeqPoint peqPoint, SrcPeqCommitResult peqCommit, SrcMicGain micGain,
                    SrcEarbudInfo earbudInfo, SrcEarbudSnChunk snChunk, byte[] raw) {
            this.commandId = cmdId; this.seq = seq; this.kind = kind;
            this.status = status; this.fwStatus = fwStatus; this.fw = fw;
            this.capability = capability; this.volume = volume; this.presetInfo = presetInfo;
            this.presetChange = presetChange; this.peqConfig = peqConfig; this.peqPreGain = peqPreGain;
            this.peqPoint = peqPoint; this.peqCommit = peqCommit; this.micGain = micGain;
            this.earbudInfo = earbudInfo; this.snChunk = snChunk; this.raw = raw;
        }
    }

    /** 解析响应帧（frameType 必须为 RESPONSE）。帧头不合法返回 null。 */
    public static SrcResponse parseResponse(byte[] frame) {
        if (frame == null || frame.length < HEADER_SIZE) return null;
        int msgId = u8(frame[3]);
        int seq = u8(frame[4]);
        int len = u8(frame[5]);
        byte[] payload = frame.length >= HEADER_SIZE + len
                ? Arrays.copyOfRange(frame, HEADER_SIZE, HEADER_SIZE + len) : new byte[0];
        return parseResponsePayload(msgId, seq, payload);
    }

    private static SrcResponse parseResponsePayload(int cmdId, int seq, byte[] p) {
        switch (cmdId) {
            case CMD_GET_AUDIO_SOURCE:
            case CMD_SET_AUDIO_SOURCE:
                return new SrcResponse(cmdId, seq, R_STATUS, parseStatus(p), 0, null, null, null, null, null, null, null, null, null, null, null, null, null);
            case CMD_GET_FW_VERSION: {
                SrcStatus st = p.length >= 1 ? parseStatus(new byte[]{p[0]}) : null;
                SrcFwInfo fw = null;
                if (p.length >= 13) {
                    byte[] f = Arrays.copyOfRange(p, 1, 13);
                    fw = new SrcFwInfo(u8(f[0]), u8(f[1]), u16le(f, 2), u8(f[4]), u8(f[5]), u8(f[6]), u8(f[7]), u32le(f, 8));
                }
                return new SrcResponse(cmdId, seq, R_FW, st, st == null ? 0 : st.statusCode, fw, null, null, null, null, null, null, null, null, null, null, null, null);
            }
            case CMD_GET_CAPABILITY:
                return new SrcResponse(cmdId, seq, R_CAPABILITY, null, 0, null, parseCapability(p), null, null, null, null, null, null, null, null, null, null, null);
            case CMD_GET_DEVICE_VOLUME:
            case CMD_SET_DEVICE_VOLUME:
                return new SrcResponse(cmdId, seq, R_VOLUME, null, 0, null, null, parseVolume(p), null, null, null, null, null, null, null, null, null, null);
            case CMD_GET_PRESET_EQ:
                return new SrcResponse(cmdId, seq, R_PRESET_INFO, null, 0, null, null, null, parsePresetInfo(p), null, null, null, null, null, null, null, null, null);
            case CMD_SET_PRESET_EQ:
                return new SrcResponse(cmdId, seq, R_PRESET_CHANGE, null, 0, null, null, null, null, parsePresetChange(p), null, null, null, null, null, null, null, null);
            case CMD_GET_PEQ_CONFIG:
                return new SrcResponse(cmdId, seq, R_PEQ_CONFIG, null, 0, null, null, null, null, null, parsePeqConfig(p), null, null, null, null, null, null, null);
            case CMD_SET_PEQ_PREGAIN:
                return new SrcResponse(cmdId, seq, R_PEQ_PREGAIN, null, 0, null, null, null, null, null, null, parsePeqPreGain(p), null, null, null, null, null, null);
            case CMD_GET_PEQ_POINT:
            case CMD_SET_PEQ_POINT:
                return new SrcResponse(cmdId, seq, R_PEQ_POINT, null, 0, null, null, null, null, null, null, null, parsePeqPoint(p), null, null, null, null, null);
            case CMD_COMMIT_PEQ:
                return new SrcResponse(cmdId, seq, R_PEQ_COMMIT, null, 0, null, null, null, null, null, null, null, null, parsePeqCommit(p), null, null, null, null);
            case CMD_GET_MIC_GAIN:
            case CMD_SET_MIC_GAIN:
                return new SrcResponse(cmdId, seq, R_MIC_GAIN, null, 0, null, null, null, null, null, null, null, null, null, parseMicGain(p), null, null, null);
            case CMD_GET_EARBUD_COLOR:
            case CMD_GET_EARBUD_LANGUAGE:
                return new SrcResponse(cmdId, seq, R_EARBUD_INFO, null, 0, null, null, null, null, null, null, null, null, null, null, parseEarbudInfo(p), null, null);
            case CMD_GET_EARBUD_SN_LEFT:
            case CMD_GET_EARBUD_SN_RIGHT:
                return new SrcResponse(cmdId, seq, R_SN_CHUNK, null, 0, null, null, null, null, null, null, null, null, null, null, null, parseSnChunk(p), null);
            default:
                return new SrcResponse(cmdId, seq, R_RAW, null, 0, null, null, null, null, null, null, null, null, null, null, null, null, p);
        }
    }

    private static SrcStatus parseStatus(byte[] p) {
        if (p == null || p.length < 5) return null;
        return new SrcStatus(u8(p[0]), u8(p[1]), u8(p[2]), u8(p[3]), u8(p[4]));
    }

    private static SrcCapabilityPage parseCapability(byte[] p) {
        if (p == null || p.length < 4) return null;
        int count = Math.min(u8(p[3]), 5);
        if (p.length < 4 + count * 2) return null;
        int[][] entries = new int[count][2];
        for (int i = 0; i < count; i++) {
            entries[i][0] = u8(p[4 + i * 2]);
            entries[i][1] = u8(p[5 + i * 2]);
        }
        return new SrcCapabilityPage(u8(p[0]), u8(p[1]), u8(p[2]), entries);
    }

    private static SrcDeviceVolume parseVolume(byte[] p) {
        if (p == null || p.length < 6) return null;
        return new SrcDeviceVolume(u8(p[0]), u8(p[1]), u8(p[2]), u8(p[3]), u8(p[4]), u8(p[5]));
    }

    private static SrcPresetEqInfo parsePresetInfo(byte[] p) {
        if (p == null || p.length < 4) return null;
        return new SrcPresetEqInfo(u8(p[0]), u8(p[1]), u8(p[2]), u8(p[3]));
    }

    private static SrcPresetEqChange parsePresetChange(byte[] p) {
        if (p == null || p.length < 4) return null;
        return new SrcPresetEqChange(u8(p[0]), u8(p[1]), u8(p[2]), u8(p[3]));
    }

    private static SrcPeqConfig parsePeqConfig(byte[] p) {
        if (p == null || p.length < 8) return null;
        return new SrcPeqConfig(u8(p[0]), u8(p[1]), u8(p[2]), u16le(p, 3), i16le(p, 5), u8(p[7]) != 0);
    }

    private static SrcPeqPreGain parsePeqPreGain(byte[] p) {
        if (p == null || p.length < 5) return null;
        return new SrcPeqPreGain(u8(p[0]), u8(p[1]), i16le(p, 2), u8(p[4]) != 0);
    }

    private static SrcPeqPoint parsePeqPoint(byte[] p) {
        if (p == null || p.length < 9) return null;
        return new SrcPeqPoint(u8(p[0]), u8(p[1]), u16le(p, 2), i16le(p, 4), u16le(p, 6), u8(p[8]));
    }

    private static SrcPeqCommitResult parsePeqCommit(byte[] p) {
        if (p == null || p.length < 5) return null;
        return new SrcPeqCommitResult(u8(p[0]), u16le(p, 1), u8(p[3]), u8(p[4]) != 0);
    }

    private static SrcMicGain parseMicGain(byte[] p) {
        if (p == null || p.length < 9) return null;
        return new SrcMicGain(u8(p[0]), i16le(p, 1), i16le(p, 3), i16le(p, 5), i16le(p, 7));
    }

    private static SrcEarbudInfo parseEarbudInfo(byte[] p) {
        if (p == null || p.length < 2) return null;
        return new SrcEarbudInfo(u8(p[0]), u8(p[1]));
    }

    private static SrcEarbudSnChunk parseSnChunk(byte[] p) {
        if (p == null || p.length < 14) return null;
        int total = u8(p[1]);
        int offset = u8(p[2]);
        int chunkLen = u8(p[3]);
        if (total != 20 || (offset != 0 && offset != 10) || chunkLen != 10) return null;
        return new SrcEarbudSnChunk(u8(p[0]), total, offset, Arrays.copyOfRange(p, 4, 14));
    }

    // ---------- 统一通知 ----------
    public static final class SrcNotification {
        public final int notifId, seq, kind;
        public final SrcStatus status;          // AUDIO_SOURCE_CHANGED / SWITCH_STATE_CHANGED / CAPABILITY_CHANGED
        public final SrcDeviceVolume volume;    // VOLUME_CHANGED
        public final int presetCurrent, presetPrevious, presetReason; // PRESET_EQ_CHANGED
        public final int peqRevision, peqActivePreset;  // PEQ_COMMITTED
        public final SrcMicGain micGain;        // MIC_GAIN_CHANGED
        public final byte[] payload;
        SrcNotification(int notifId, int seq, int kind, SrcStatus status, SrcDeviceVolume volume,
                        int presetCurrent, int presetPrevious, int presetReason,
                        int peqRevision, int peqActivePreset, SrcMicGain micGain, byte[] payload) {
            this.notifId = notifId; this.seq = seq; this.kind = kind;
            this.status = status; this.volume = volume;
            this.presetCurrent = presetCurrent; this.presetPrevious = presetPrevious; this.presetReason = presetReason;
            this.peqRevision = peqRevision; this.peqActivePreset = peqActivePreset;
            this.micGain = micGain; this.payload = payload;
        }
    }

    /** 解析通知帧。帧头非法返回 null。 */
    public static SrcNotification parseNotification(byte[] frame) {
        if (frame == null || frame.length < HEADER_SIZE) return null;
        int notifId = u8(frame[3]);
        int seq = u8(frame[4]);
        int len = u8(frame[5]);
        byte[] p = frame.length >= HEADER_SIZE + len
                ? Arrays.copyOfRange(frame, HEADER_SIZE, HEADER_SIZE + len) : new byte[0];
        switch (notifId) {
            case NOTIF_AUDIO_SOURCE_CHANGED:
            case NOTIF_SWITCH_STATE_CHANGED:
            case NOTIF_CAPABILITY_CHANGED:
                return new SrcNotification(notifId, seq, R_STATUS, parseStatus(p), null,
                        0, 0, 0, 0, 0, null, p);
            case NOTIF_VOLUME_CHANGED:
                return new SrcNotification(notifId, seq, R_VOLUME, null, parseVolume(p),
                        0, 0, 0, 0, 0, null, p);
            case NOTIF_PRESET_EQ_CHANGED:
                if (p.length < 3) return null;
                return new SrcNotification(notifId, seq, R_PRESET_CHANGE, null, null,
                        u8(p[0]), u8(p[1]), u8(p[2]), 0, 0, null, p);
            case NOTIF_PEQ_COMMITTED:
                if (p.length < 3) return null;
                return new SrcNotification(notifId, seq, R_PEQ_COMMIT, null, null,
                        0, 0, 0, u16le(p, 0), u8(p[2]), null, p);
            case NOTIF_MIC_GAIN_CHANGED:
                if (p.length < 8) return null;
                return new SrcNotification(notifId, seq, R_MIC_GAIN, null, null,
                        0, 0, 0, 0, 0,
                        new SrcMicGain(ST_OK, i16le(p, 0), i16le(p, 2), i16le(p, 4), i16le(p, 6)), p);
            default:
                return new SrcNotification(notifId, seq, R_RAW, null, null,
                        0, 0, 0, 0, 0, null, p);
        }
    }

    /** 校验帧头（0xA5 / 0x01 / 期望帧类型）。 */
    public static boolean isFrame(byte[] frame, int expectedType) {
        if (frame == null || frame.length < HEADER_SIZE) return false;
        return u8(frame[0]) == MAGIC && u8(frame[1]) == PROTOCOL_VERSION && u8(frame[2]) == expectedType;
    }

    public static String statusName(int st) {
        switch (st) {
            case ST_OK: return "OK";
            case ST_UNSUPPORTED_VERSION: return "UNSUPPORTED_VERSION";
            case ST_UNSUPPORTED_CMD: return "UNSUPPORTED_CMD";
            case ST_INVALID_LENGTH: return "INVALID_LENGTH";
            case ST_UNSUPPORTED_SOURCE: return "UNSUPPORTED_SOURCE";
            case ST_BUSY_SWITCHING: return "BUSY_SWITCHING";
            case ST_AUTH_REQUIRED: return "AUTH_REQUIRED";
            case ST_DENIED_BY_MODE: return "DENIED_BY_MODE";
            case ST_TIMEOUT: return "TIMEOUT";
            case ST_SAME_SOURCE: return "SAME_SOURCE";
            case ST_NO_SIGNAL: return "NO_SIGNAL";
            case ST_INTERNAL_ERROR: return "INTERNAL_ERROR";
            case ST_INVALID_VALUE: return "INVALID_VALUE";
            case ST_VOLUME_UNSUPPORTED: return "VOLUME_UNSUPPORTED";
            case ST_READ_ONLY_PRESET: return "READ_ONLY_PRESET";
            case ST_REVISION_CONFLICT: return "REVISION_CONFLICT";
            case ST_INVALID_OFFSET: return "INVALID_OFFSET";
            case ST_STORAGE_ERROR: return "STORAGE_ERROR";
            case ST_INFO_UNAVAILABLE: return "INFO_UNAVAILABLE";
            default: return "UNKNOWN(" + st + ")";
        }
    }

    public static String sourceName(int id) {
        switch (id) {
            case SRC_BLUETOOTH: return "蓝牙";
            case SRC_USB_AUDIO: return "USB";
            case SRC_WIRELESS_2_4G: return "2.4G";
            case SRC_AUX_LINE_IN: return "AUX";
            case SRC_OPTICAL_SPDIF: return "光纤";
            case SRC_COAXIAL: return "同轴";
            case SRC_HDMI_ARC: return "HDMI ARC";
            case SRC_LOCAL_PLAYER: return "本地";
            case SRC_AUTO_SELECT: return "自动";
            case SRC_NO_SIGNAL: return "无信号";
            default: return "未知(" + id + ")";
        }
    }

    // ---------- 特征直读（9ECA0004 / 9ECA0005，非帧格式） ----------
    public static final class SrcSourceCapability {
        public final int capabilityVersion, currentSource, globalFlags;
        public final int[][] entries; // [i][0]=sourceId, [i][1]=flags
        public final int flagAuthRequired()   { return (globalFlags & 2) != 0 ? 1 : 0; }
        public final int flagPersistDefault() { return (globalFlags & 4) != 0 ? 1 : 0; }
        public final int flagAutoSelect()     { return (globalFlags & 8) != 0 ? 1 : 0; }
        public final int flagNotify()         { return (globalFlags & 1) != 0 ? 1 : 0; }
        public SrcSourceCapability(int ver, int cur, int flags, int[][] entries) {
            capabilityVersion = ver; currentSource = cur; globalFlags = flags; this.entries = entries;
        }
    }

    /** 9ECA0004 直读解析：[version][count<=8][currentSource][globalFlags][(src,flags)*] */
    public static SrcSourceCapability parseSourceCapability(byte[] p) {
        if (p == null || p.length < 4) return null;
        int count = Math.min(u8(p[1]), 8);
        if (p.length < 4 + count * 2) return null;
        int[][] entries = new int[count][2];
        for (int i = 0; i < count; i++) {
            entries[i][0] = u8(p[4 + i * 2]);
            entries[i][1] = u8(p[5 + i * 2]);
        }
        return new SrcSourceCapability(u8(p[0]), u8(p[2]), u8(p[3]), entries);
    }

    /** 9ECA0005 直读解析：12 字节（无状态字节） */
    public static SrcFwInfo parseProtocolFirmwareInfo(byte[] p) {
        if (p == null || p.length < 12) return null;
        return new SrcFwInfo(u8(p[0]), u8(p[1]), u16le(p, 2), u8(p[4]), u8(p[5]), u8(p[6]), u8(p[7]), u32le(p, 8));
    }

    // ---------- 载荷编码辅助（供事务层复用） ----------
    public static byte[] concatPregain(int preGain) {
        return concat(new byte[]{(byte) USER_PEQ_PRESET}, i16leBytes(preGain));
    }

    public static byte[] concatPoint(int index, int freqHz, int gainRaw, int qRaw, int filterId) {
        if (index < 0 || index >= PEQ_POINT_COUNT) throw new IllegalArgumentException("pointIndex 0..31");
        if (freqHz < 20 || freqHz > 20000) throw new IllegalArgumentException("freq 20..20000");
        if (qRaw < 1 || qRaw > 0xFFFF) throw new IllegalArgumentException("q 1..65535");
        if (filterId < 0 || filterId > 7) throw new IllegalArgumentException("filter 0..7");
        return concat(new byte[]{(byte) index}, u16leBytes(freqHz), i16leBytes(gainRaw),
                u16leBytes(qRaw), new byte[]{(byte) filterId});
    }

    public static byte[] concatCommit(int action, int revision) {
        if (action < 0 || action > 2) throw new IllegalArgumentException("action 0..2");
        if (revision < 0 || revision > 0xFFFF) throw new IllegalArgumentException("revision 0..65535");
        return concat(new byte[]{(byte) action}, u16leBytes(revision));
    }

    public static byte[] concatMicGain(int micGainRaw) {
        if (micGainRaw < -1280 || micGainRaw > 1280) throw new IllegalArgumentException("micGain -1280..1280");
        return i16leBytes(micGainRaw);
    }

}

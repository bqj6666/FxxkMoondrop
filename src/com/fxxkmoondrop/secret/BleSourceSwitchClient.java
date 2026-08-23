package com.fxxkmoondrop.secret;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.os.Handler;
import android.util.Log;

import java.util.Arrays;

/**
 * BleSourceSwitch（9ECA0000）事务客户端，复用 GaiaBleClient 的同一 GATT 连接。
 * 参考官方 App：com.moondroplab.moondrop.moondrop_app.native.ble.source.BleSourceSwitchClient
 *  - 命令采用单 pending + 序号匹配（seq 0..255 递增）
 *  - 响应帧校验 commandId + sequence，超时 10s
 *  - 9ECA0004/0005 为可读特征（readCharacteristic 直读，非 notify）
 */
public class BleSourceSwitchClient {

    private static final String TAG = "BleSourceSwitch";

    public interface SrcCallback<T> {
        void onSuccess(T value);
        void onError(String message);
    }

    /** 通知/状态监听（在 GATT 回调线程，注意切主线程） */
    public interface Listener {
        void onSrcNotification(BleSourceProtocol.SrcNotification n);
    }

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic cmdChar;
    private BluetoothGattCharacteristic respChar;
    private BluetoothGattCharacteristic notifyChar;
    private BluetoothGattCharacteristic capChar;
    private BluetoothGattCharacteristic infoChar;
    private final Handler handler;
    private final Listener listener;

    private static final long OP_TIMEOUT_MS = 10000;

    private static final class Pending {
        final int cmdId;
        final int seq;
        final SrcCallback<BleSourceProtocol.SrcResponse> cb;
        Pending(int cmdId, int seq, SrcCallback<BleSourceProtocol.SrcResponse> cb) {
            this.cmdId = cmdId; this.seq = seq; this.cb = cb;
        }
    }

    private Pending pending;
    private SrcCallback<BleSourceProtocol.SrcSourceCapability> pendingCapability;
    private SrcCallback<BleSourceProtocol.SrcFwInfo> pendingInfo;
    private int nextSequence;

    public BleSourceSwitchClient(Handler handler, Listener listener) {
        this.handler = handler;
        this.listener = listener;
    }

    /** 服务发现完成后绑定特征（同一 GATT 连接）。 */
    public void bind(BluetoothGatt gatt,
                     BluetoothGattCharacteristic cmdChar,
                     BluetoothGattCharacteristic respChar,
                     BluetoothGattCharacteristic notifyChar,
                     BluetoothGattCharacteristic capChar,
                     BluetoothGattCharacteristic infoChar) {
        this.gatt = gatt;
        this.cmdChar = cmdChar;
        this.respChar = respChar;
        this.notifyChar = notifyChar;
        this.capChar = capChar;
        this.infoChar = infoChar;
        this.pending = null;
        this.pendingCapability = null;
        this.pendingInfo = null;
    }

    public void clear() {
        pending = null;
        pendingCapability = null;
        pendingInfo = null;
    }

    public boolean isPresent() {
        return gatt != null && cmdChar != null && respChar != null;
    }

    public boolean hasCapabilityChar() { return capChar != null; }
    public boolean hasInfoChar() { return infoChar != null; }

    // ---------- 通用执行 ----------
    public void execute(int cmdId, byte[] payload, SrcCallback<BleSourceProtocol.SrcResponse> cb) {
        if (gatt == null || cmdChar == null) {
            fail(cb, "9ECA 服务未连接");
            return;
        }
        if (pending != null || pendingCapability != null || pendingInfo != null) {
            fail(cb, "另一条 9ECA 操作进行中");
            return;
        }
        int seq = (nextSequence++ ) & 0xFF;
        byte[] frame;
        try {
            frame = BleSourceProtocol.buildCommand(cmdId, seq, payload);
        } catch (Exception e) {
            fail(cb, "帧构造失败: " + e.getMessage());
            return;
        }
        final Pending p = new Pending(cmdId, seq, cb);
        pending = p;
        startTimeout(new Runnable() {
            @Override public void run() {
                if (pending == p) {
                    pending = null;
                    fail(cb, "9ECA 命令超时");
                }
            }
        });
        try {
            if (cmdChar.getWriteType() != BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            }
            cmdChar.setValue(frame);
            boolean ok = gatt.writeCharacteristic(cmdChar);
            if (!ok) {
                // 回退 no-response 重试一次（官方客户端用 WRITE_TYPE_NO_RESPONSE）
                try {
                    cmdChar.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                    cmdChar.setValue(frame);
                    ok = gatt.writeCharacteristic(cmdChar);
                } catch (Exception ignored) { }
            }
            if (!ok) {
                pending = null;
                fail(cb, "9ECA 写入失败");
                return;
            }
            Log.d(TAG, "SRC TX cmd=" + cmdId + " seq=" + seq + " " + Arrays.toString(frame));
            AppLog.d(TAG, "9ECA TX cmd=" + cmdId + " seq=" + seq + " hex=" + AppLog.hex(frame));
        } catch (SecurityException e) {
            pending = null;
            fail(cb, "9ECA 写入权限不足");
        } catch (Exception e) {
            pending = null;
            fail(cb, "9ECA 写入异常: " + e);
        }
    }

    /** 特征直读：9ECA0004 能力表（整表） */
    public void readSourceCapability(SrcCallback<BleSourceProtocol.SrcSourceCapability> cb) {
        if (gatt == null || capChar == null) { fail(cb, "9ECA 能力特征不可用"); return; }
        if (pending != null || pendingCapability != null || pendingInfo != null) {
            fail(cb, "另一条 9ECA 操作进行中"); return;
        }
        pendingCapability = cb;
        startTimeout(new Runnable() {
            @Override public void run() {
                if (pendingCapability != null) {
                    pendingCapability = null;
                    fail(cb, "读取 9ECA 能力超时");
                }
            }
        });
        try {
            if (!gatt.readCharacteristic(capChar)) {
                pendingCapability = null;
                fail(cb, "9ECA 能力读取失败");
            }
        } catch (SecurityException e) {
            pendingCapability = null;
            fail(cb, "9ECA 能力读取权限不足");
        }
    }

    /** 特征直读：9ECA0005 协议与固件信息 */
    public void readProtocolFirmwareInfo(SrcCallback<BleSourceProtocol.SrcFwInfo> cb) {
        if (gatt == null || infoChar == null) { fail(cb, "9ECA 固件信息特征不可用"); return; }
        if (pending != null || pendingCapability != null || pendingInfo != null) {
            fail(cb, "另一条 9ECA 操作进行中"); return;
        }
        pendingInfo = cb;
        startTimeout(new Runnable() {
            @Override public void run() {
                if (pendingInfo != null) {
                    pendingInfo = null;
                    fail(cb, "读取 9ECA 固件信息超时");
                }
            }
        });
        try {
            if (!gatt.readCharacteristic(infoChar)) {
                pendingInfo = null;
                fail(cb, "9ECA 固件信息读取失败");
            }
        } catch (SecurityException e) {
            pendingInfo = null;
            fail(cb, "9ECA 固件信息读取权限不足");
        }
    }

    // ---------- GATT 事件入口（由 GaiaBleClient 转发） ----------
    public void onCharacteristicChanged(BluetoothGattCharacteristic ch, byte[] value) {
        AppLog.d(TAG, "9ECA RX hex=" + AppLog.hex(value));
        if (value == null || value.length < BleSourceProtocol.HEADER_SIZE) return;
        if (BleSourceProtocol.isFrame(value, BleSourceProtocol.FRAME_RESPONSE)) {
            onResponse(value);
        } else if (BleSourceProtocol.isFrame(value, BleSourceProtocol.FRAME_NOTIFICATION)) {
            BleSourceProtocol.SrcNotification n = BleSourceProtocol.parseNotification(value);
            if (n != null) {
                Log.d(TAG, "SRC NOTIF " + n.notifId + " seq=" + n.seq + " " + Arrays.toString(n.payload));
                if (listener != null) listener.onSrcNotification(n);
            }
        }
    }

    public void onCharacteristicRead(BluetoothGattCharacteristic ch, int status, byte[] value) {
        AppLog.d(TAG, "9ECA RD status=" + status + " hex=" + AppLog.hex(value));
        if (ch == null) return;
        if (ch.equals(respChar)) {
            if (status == BluetoothGatt.GATT_SUCCESS) onResponse(value);
            else if (pending != null) {
                SrcCallback<BleSourceProtocol.SrcResponse> cb = pending.cb;
                pending = null;
                fail(cb, "9ECA 响应读取失败 status=" + status);
            }
            return;
        }
        if (ch.equals(capChar)) {
            SrcCallback<BleSourceProtocol.SrcSourceCapability> cb = pendingCapability;
            pendingCapability = null;
            if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                fail(cb, "9ECA 能力读取失败 status=" + status);
                return;
            }
            BleSourceProtocol.SrcSourceCapability cap = BleSourceProtocol.parseSourceCapability(value);
            if (cap != null) cb.onSuccess(cap);
            else fail(cb, "9ECA 能力数据解析失败");
            return;
        }
        if (ch.equals(infoChar)) {
            SrcCallback<BleSourceProtocol.SrcFwInfo> cb = pendingInfo;
            pendingInfo = null;
            if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                fail(cb, "9ECA 固件信息读取失败 status=" + status);
                return;
            }
            BleSourceProtocol.SrcFwInfo info = BleSourceProtocol.parseProtocolFirmwareInfo(value);
            if (info != null) cb.onSuccess(info);
            else fail(cb, "9ECA 固件信息数据解析失败");
        }
    }

    private void onResponse(byte[] frame) {
        BleSourceProtocol.SrcResponse r = BleSourceProtocol.parseResponse(frame);
        if (r == null) return;
        if (pending == null) {
            Log.w(TAG, "SRC 响应无 pending: cmd=" + r.commandId + " seq=" + r.seq);
            return;
        }
        if (r.commandId != pending.cmdId || r.seq != pending.seq) {
            SrcCallback<BleSourceProtocol.SrcResponse> cb = pending.cb;
            pending = null;
            fail(cb, "9ECA 响应不匹配 cmd=" + r.commandId + " seq=" + r.seq);
            return;
        }
        SrcCallback<BleSourceProtocol.SrcResponse> cb = pending.cb;
        pending = null;
        Log.d(TAG, "SRC RX cmd=" + r.commandId + " seq=" + r.seq + " kind=" + r.kind);
        cb.onSuccess(r);
    }

    // ---------- 超时 ----------
    private void startTimeout(Runnable r) {
        final Runnable restore = r;
        handler.postDelayed(r, OP_TIMEOUT_MS);
    }

    private <T> void fail(SrcCallback<T> cb, String msg) {
        Log.w(TAG, "SRC error: " + msg);
        AppLog.e(TAG, "SRC error: " + msg);
        if (cb != null) cb.onError(msg);
    }

    // ---------- 语义化 API ----------
    public void getAudioSource(SrcCallback<BleSourceProtocol.SrcStatus> cb) {
        execute(BleSourceProtocol.CMD_GET_AUDIO_SOURCE, null, wrapStatus(cb));
    }

    /** 切换音源；waitStable=true 时轮询至 STABLE（官方 setAudioSourceAndWait 超时=timeoutSec） */
    public void setAudioSource(int sourceId, int options, int timeoutSec, boolean waitStable,
                               SrcCallback<BleSourceProtocol.SrcStatus> cb) {
        int fade = Math.max(1, timeoutSec);
        if (!waitStable) {
            execute(BleSourceProtocol.CMD_SET_AUDIO_SOURCE,
                    new byte[]{(byte) sourceId, (byte) options, (byte) Math.min(60, fade)},
                    wrapStatus(cb));
            return;
        }
        final long deadline = System.currentTimeMillis() + fade * 1000L;
        execute(BleSourceProtocol.CMD_SET_AUDIO_SOURCE,
                new byte[]{(byte) sourceId, (byte) options, (byte) Math.min(60, fade)},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.status == null) { cb.onError("SET_AUDIO_SOURCE 响应异常"); return; }
                        if (r.status.stableSuccess() || (r.status.statusCode != BleSourceProtocol.ST_OK
                                && r.status.statusCode != BleSourceProtocol.ST_BUSY_SWITCHING)) {
                            cb.onSuccess(r.status);
                            return;
                        }
                        pollStable(sourceId, deadline, cb);
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    private void pollStable(final int sourceId, final long deadline,
                            final SrcCallback<BleSourceProtocol.SrcStatus> cb) {
        if (System.currentTimeMillis() >= deadline) {
            cb.onError("音源切换未稳定（超时）");
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                getAudioSource(new SrcCallback<BleSourceProtocol.SrcStatus>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcStatus s) {
                        boolean done = s.stableSuccess() && s.currentSource == sourceId;
                        boolean cont = s.statusCode == BleSourceProtocol.ST_OK
                                || s.statusCode == BleSourceProtocol.ST_BUSY_SWITCHING;
                        if (done || !cont) { cb.onSuccess(s); return; }
                        pollStable(sourceId, deadline, cb);
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
            }
        }, 200);
    }

    public void getCapabilityPage(int page, SrcCallback<BleSourceProtocol.SrcCapabilityPage> cb) {
        execute(BleSourceProtocol.CMD_GET_CAPABILITY, new byte[]{(byte) Math.max(0, Math.min(15, page))},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.capability != null) cb.onSuccess(r.capability);
                        else cb.onError("GET_CAPABILITY 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getFirmwareVersion(SrcCallback<BleSourceProtocol.SrcResponse> cb) {
        execute(BleSourceProtocol.CMD_GET_FW_VERSION, null, cb);
    }

    public void getDeviceVolume(SrcCallback<BleSourceProtocol.SrcDeviceVolume> cb) {
        execute(BleSourceProtocol.CMD_GET_DEVICE_VOLUME, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.volume != null) cb.onSuccess(r.volume);
                        else cb.onError("GET_DEVICE_VOLUME 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** volumeMode/volumeValue/optionFlags 0..255（与官方 Flutter 层一致） */
    public void setDeviceVolume(int volumeMode, int volumeValue, int optionFlags,
                                SrcCallback<BleSourceProtocol.SrcDeviceVolume> cb) {
        execute(BleSourceProtocol.CMD_SET_DEVICE_VOLUME,
                new byte[]{(byte) volumeMode, (byte) volumeValue, (byte) optionFlags},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.volume != null) cb.onSuccess(r.volume);
                        else cb.onError("SET_DEVICE_VOLUME 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getPresetEq(SrcCallback<BleSourceProtocol.SrcPresetEqInfo> cb) {
        execute(BleSourceProtocol.CMD_GET_PRESET_EQ, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.presetInfo != null) cb.onSuccess(r.presetInfo);
                        else cb.onError("GET_PRESET_EQ 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void setPresetEq(int presetIndex, SrcCallback<BleSourceProtocol.SrcPresetEqChange> cb) {
        execute(BleSourceProtocol.CMD_SET_PRESET_EQ, new byte[]{(byte) presetIndex},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.presetChange != null) cb.onSuccess(r.presetChange);
                        else cb.onError("SET_PRESET_EQ 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getPeqConfig(SrcCallback<BleSourceProtocol.SrcPeqConfig> cb) {
        execute(BleSourceProtocol.CMD_GET_PEQ_CONFIG, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.peqConfig != null) cb.onSuccess(r.peqConfig);
                        else cb.onError("GET_PEQ_CONFIG 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** preGain 单位 0.01 dB（-12800..12799） */
    public void setPeqPreGain(int preGain, SrcCallback<BleSourceProtocol.SrcPeqPreGain> cb) {
        execute(BleSourceProtocol.CMD_SET_PEQ_PREGAIN, BleSourceProtocol.concatPregain(preGain),
                wrapWith(new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.peqPreGain != null) cb.onSuccess(r.peqPreGain);
                        else cb.onError("SET_PEQ_PREGAIN 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                }));
    }

    public void getPeqPoint(int index, SrcCallback<BleSourceProtocol.SrcPeqPoint> cb) {
        execute(BleSourceProtocol.CMD_GET_PEQ_POINT, new byte[]{(byte) index},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.peqPoint != null) cb.onSuccess(r.peqPoint);
                        else cb.onError("GET_PEQ_POINT 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** 官方 setPoint(index, freqHz, gainRaw, qRaw, filterId) */
    public void setPeqPoint(int index, int freqHz, int gainRaw, int qRaw, int filterId,
                            SrcCallback<BleSourceProtocol.SrcPeqPoint> cb) {
        byte[] payload;
        try {
            payload = BleSourceProtocol.concatPoint(index, freqHz, gainRaw, qRaw, filterId);
        } catch (Exception e) {
            cb.onError(e.getMessage());
            return;
        }
        execute(BleSourceProtocol.CMD_SET_PEQ_POINT, payload,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.peqPoint != null) cb.onSuccess(r.peqPoint);
                        else cb.onError("SET_PEQ_POINT 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** action 0..2, revision 0..65535 */
    public void commitPeq(int action, int revision, SrcCallback<BleSourceProtocol.SrcPeqCommitResult> cb) {
        byte[] payload;
        try {
            payload = BleSourceProtocol.concatCommit(action, revision);
        } catch (Exception e) {
            cb.onError(e.getMessage());
            return;
        }
        execute(BleSourceProtocol.CMD_COMMIT_PEQ, payload,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.peqCommit != null) cb.onSuccess(r.peqCommit);
                        else cb.onError("COMMIT_PEQ 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getMicGain(SrcCallback<BleSourceProtocol.SrcMicGain> cb) {
        execute(BleSourceProtocol.CMD_GET_MIC_GAIN, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.micGain != null) cb.onSuccess(r.micGain);
                        else cb.onError("GET_MIC_GAIN 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** micGainRaw 单位 0.1 dB（-1280..1280） */
    public void setMicGain(int micGainRaw, SrcCallback<BleSourceProtocol.SrcMicGain> cb) {
        byte[] payload;
        try {
            payload = BleSourceProtocol.concatMicGain(micGainRaw);
        } catch (Exception e) {
            cb.onError(e.getMessage());
            return;
        }
        execute(BleSourceProtocol.CMD_SET_MIC_GAIN, payload,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.micGain != null) cb.onSuccess(r.micGain);
                        else cb.onError("SET_MIC_GAIN 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getEarbudColor(SrcCallback<BleSourceProtocol.SrcEarbudInfo> cb) {
        execute(BleSourceProtocol.CMD_GET_EARBUD_COLOR, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.earbudInfo != null) cb.onSuccess(r.earbudInfo);
                        else cb.onError("GET_EARBUD_COLOR 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void getEarbudLanguage(SrcCallback<BleSourceProtocol.SrcEarbudInfo> cb) {
        execute(BleSourceProtocol.CMD_GET_EARBUD_LANGUAGE, null,
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.earbudInfo != null) cb.onSuccess(r.earbudInfo);
                        else cb.onError("GET_EARBUD_LANGUAGE 响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    /** SN 分块聚合（20 字节 = 2×10 字节块），官方 getSn 实现 */
    public void getLeftSn(SrcCallback<byte[]> cb) { getSn(BleSourceProtocol.CMD_GET_EARBUD_SN_LEFT, cb); }
    public void getRightSn(SrcCallback<byte[]> cb) { getSn(BleSourceProtocol.CMD_GET_EARBUD_SN_RIGHT, cb); }

    private void getSn(final int cmdId, final SrcCallback<byte[]> cb) {
        getSnChunk(cmdId, 0, new SrcCallback<BleSourceProtocol.SrcEarbudSnChunk>() {
            @Override public void onSuccess(final BleSourceProtocol.SrcEarbudSnChunk first) {
                if (first.offset != 0) { cb.onError("SN 首块偏移异常 " + first.offset); return; }
                if (first.statusCode != BleSourceProtocol.ST_OK
                        || first.totalLength <= first.bytes.length) {
                    cb.onSuccess(first.bytes);
                    return;
                }
                getSnChunk(cmdId, 10, new SrcCallback<BleSourceProtocol.SrcEarbudSnChunk>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcEarbudSnChunk second) {
                        if (second.offset == 10 && second.totalLength == first.totalLength) {
                            byte[] out = new byte[first.bytes.length + second.bytes.length];
                            System.arraycopy(first.bytes, 0, out, 0, first.bytes.length);
                            System.arraycopy(second.bytes, 0, out, first.bytes.length, second.bytes.length);
                            cb.onSuccess(out);
                        } else {
                            cb.onError("SN 分块不一致");
                        }
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
            }
            @Override public void onError(String msg) { cb.onError(msg); }
        });
    }

    private void getSnChunk(int cmdId, int offset, SrcCallback<BleSourceProtocol.SrcEarbudSnChunk> cb) {
        execute(cmdId, new byte[]{(byte) offset},
                new SrcCallback<BleSourceProtocol.SrcResponse>() {
                    @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                        if (r.snChunk != null) cb.onSuccess(r.snChunk);
                        else cb.onError("SN 块响应异常");
                    }
                    @Override public void onError(String msg) { cb.onError(msg); }
                });
    }

    public void ping(SrcCallback<BleSourceProtocol.SrcResponse> cb) {
        execute(BleSourceProtocol.CMD_PING, null, cb);
    }

    // ---------- 内部辅助 ----------
    private SrcCallback<BleSourceProtocol.SrcResponse> wrapStatus(
            final SrcCallback<BleSourceProtocol.SrcStatus> cb) {
        return new SrcCallback<BleSourceProtocol.SrcResponse>() {
            @Override public void onSuccess(BleSourceProtocol.SrcResponse r) {
                if (r.status != null) cb.onSuccess(r.status);
                else cb.onError("音源状态响应异常");
            }
            @Override public void onError(String msg) { cb.onError(msg); }
        };
    }

    private SrcCallback<BleSourceProtocol.SrcResponse> wrapWith(
            SrcCallback<BleSourceProtocol.SrcResponse> inner) {
        return inner;
    }
}

package cn.aimstek.loong.aidiag.qltool.transport;

import cn.aimstek.loong.aidiag.qltool.transport.moka7.Moka7Core;
import cn.aimstek.loong.aidiag.qltool.transport.moka7.S7;
import cn.aimstek.loong.aidiag.qltool.transport.moka7.S7Const;
import lombok.extern.slf4j.Slf4j;

/**
 * 基于 moka7 协议核心的 S7Client 实现。
 *
 * <p>职责：把 {@link Moka7Core} 的"错误码"语义适配成 {@link S7Client} 接口的"异常"语义。
 * <ul>
 *   <li>{@code readArea} 返回码 != 0 → 抛 {@code RuntimeException}（不把全 0 缓冲区当有效数据）</li>
 *   <li>{@code writeArea} 返回码 != 0 → 抛 {@code RuntimeException}</li>
 *   <li>连接失败（{@code connected==false}）→ 抛 {@code RuntimeException}</li>
 * </ul>
 *
 * <p>moka7 核心自带"读写失败自动重连一次再重试"+ 按连接 synchronized 串行，
 * 叠加 {@code DeviceSession} 的串行保护，安全使用。
 *
 * <p>源自 iot 项目经过实跑验证的协议实现，纯 Java、零外部依赖。
 */
@Slf4j
public class Moka7S7Client implements S7Client {

    private final String host;
    private final int port;
    private final int rack;
    private final int slot;
    private final Moka7Core core;

    /**
     * @param host      PLC IP 地址
     * @param port      端口（通常 102）
     * @param rack      机架号
     * @param slot      插槽号
     * @param timeoutMs 读写超时（毫秒），映射到 moka7 的 recvTimeout
     */
    public Moka7S7Client(String host, int port, int rack, int slot, int timeoutMs) {
        this.host = host;
        this.port = port;
        this.rack = rack;
        this.slot = slot;
        this.core = new Moka7Core();
        this.core.recvTimeout = Math.max(timeoutMs, 1000);
    }

    @Override
    public void connect() {
        if (core.connected) {
            return;
        }
        int result = core.connectTo(host, rack, slot, port);
        if (result != S7Const.errNoError || !core.connected) {
            throw new RuntimeException("连接设备失败(" + host + ":" + port
                    + "): " + S7Const.getErrorText(result));
        }
        log.info("[moka7] 已连接 {}:{} (rack={}, slot={})", host, port, rack, slot);
    }

    @Override
    public boolean isConnected() {
        return core.connected;
    }

    @Override
    public byte[] readDataBlock(int db, int offset, int length) {
        byte[] buffer = new byte[length];
        int result = core.readArea(S7.S7_AREA_DB, db, offset, length, buffer);
        if (result != S7Const.errNoError) {
            throw new RuntimeException("读取 DB" + db + " (offset=" + offset + ", len=" + length
                    + ") 失败: " + S7Const.getErrorText(result));
        }
        return buffer;
    }

    @Override
    public void writeDataBlock(int db, int offset, byte[] buffer) {
        int result = core.writeArea(S7.S7_AREA_DB, db, offset, buffer.length, buffer);
        if (result != S7Const.errNoError) {
            throw new RuntimeException("写入 DB" + db + " (offset=" + offset + ", len=" + buffer.length
                    + ") 失败: " + S7Const.getErrorText(result));
        }
    }

    @Override
    public void close() {
        core.disconnect();
        log.info("[moka7] 已断开 {}:{}", host, port);
    }
}

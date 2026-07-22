package cn.aimstek.loong.aidiag.qltool.service;

import cn.aimstek.loong.aidiag.qltool.config.QlToolProperties.DeviceConfig;
import cn.aimstek.loong.aidiag.qltool.transport.Moka7S7Client;
import cn.aimstek.loong.aidiag.qltool.transport.S7Client;

/**
 * 单设备会话：持有一个 S7 连接，串行化访问（单连接不宜并发）。
 *
 * <p>连接懒加载：只有调用 {@link #connect()} 才真正建链；PLC 不可达不会影响应用启动或其它设备。
 * <p>底层通信走 moka7 协议核心（纯 Java Socket、自带失败重连）。
 */
public class DeviceSession {

    private final DeviceConfig config;
    private final S7Client s7;

    public DeviceSession(DeviceConfig config, int timeoutMs) {
        this.config = config;
        this.s7 = new Moka7S7Client(config.getIp(), config.getPort(),
                config.getRack(), config.getSlot(), timeoutMs);
    }

    public DeviceConfig getConfig() {
        return config;
    }

    public S7Client getS7() {
        return s7;
    }

    public synchronized void connect() {
        s7.connect();
    }

    public void disconnect() {
        s7.close();
    }

    public boolean isConnected() {
        return s7.isConnected();
    }
}

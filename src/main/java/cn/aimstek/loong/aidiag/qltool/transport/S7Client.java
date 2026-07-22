package cn.aimstek.loong.aidiag.qltool.transport;

/**
 * S7 传输层统一抽象（对应 LoongAssist 的 Snap7Client / S7.Net Plc 封装）。
 *
 * <p>把"读写 DB 块字节"收敛成接口，上层设备驱动（堆垛机/输送线连接器）只依赖它，
 * 底层可在 s7connector / PLC4X 间自由切换而不影响上层。
 */
public interface S7Client extends AutoCloseable {

    /** 建立连接（失败抛异常） */
    void connect();

    /** 是否已连接 */
    boolean isConnected();

    /**
     * 读取数据块（对应 Snap7Client.ReadDataBlock / S7.Net ReadBytes(DataBlock,...)）。
     *
     * @param db     DB 块号，例如 531
     * @param offset 起始字节偏移
     * @param length 读取字节数
     * @return 原始字节（大端，交由 Codec 解析）
     */
    byte[] readDataBlock(int db, int offset, int length);

    /**
     * 写入数据块（对应 Snap7Client.WriteDataBlock / S7.Net Write(DataBlock,...)）。
     */
    void writeDataBlock(int db, int offset, byte[] buffer);

    @Override
    void close();
}

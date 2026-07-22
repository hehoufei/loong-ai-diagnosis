package cn.aimstek.loong.aidiag.qltool.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * S7 大端字节读取器（对应 LoongAssist 的 Marshal.PtrToStructure + SwapEndianness）。
 *
 * <p>西门子 PLC 数据块为大端序；.NET 侧"先按大端存、机器小端反序列化再 SwapEndianness"
 * 的净效果就是大端读取，Java 直接以 BIG_ENDIAN 读即可。无符号用更大有符号类型 + 掩码承载。
 */
public class S7Reader {

    private final ByteBuffer buf;

    public S7Reader(byte[] data) {
        this(data, 0);
    }

    public S7Reader(byte[] data, int offset) {
        this.buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        this.buf.position(offset);
    }

    /** 读无符号字节 (U1) */
    public int u1() {
        return buf.get() & 0xFF;
    }

    /** 读无符号 16 位 (U2) */
    public int u2() {
        return buf.getShort() & 0xFFFF;
    }

    /** 读无符号 32 位 (U4) */
    public long u4() {
        return buf.getInt() & 0xFFFFFFFFL;
    }

    /** 读 n 个字节 */
    public byte[] bytes(int n) {
        byte[] out = new byte[n];
        buf.get(out);
        return out;
    }

    /** 读 n 字节 ASCII 字符串（对应 char[n]） */
    public String ascii(int n) {
        byte[] b = bytes(n);
        return new String(b, StandardCharsets.US_ASCII);
    }

    /** 跳过 n 字节 */
    public void skip(int n) {
        buf.position(buf.position() + n);
    }

    public int position() {
        return buf.position();
    }
}

package cn.aimstek.loong.aidiag.qltool.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * S7 大端字节写入器（对应 LoongAssist 的 Marshal.StructureToPtr + SwapEndianness）。
 *
 * <p>默认写入大端序（.NET"先 SwapEndianness 再小端序列化"的净效果即大端）。
 * 对个别未做 SwapEndianness、以原始值小端落盘的字段（如堆垛机 Fork2CargoType、ClearTask.TaskNo），
 * 用 {@link #u2Le(int)} / {@link #u4Le(long)} 精确复刻 .NET 的线上字节。
 */
public class S7Writer {

    private final ByteBuffer buf;

    public S7Writer(int size) {
        this.buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
    }

    /** 写无符号字节 (U1) */
    public S7Writer u1(int v) {
        buf.put((byte) (v & 0xFF));
        return this;
    }

    /** 写无符号 16 位 (U2)，大端 */
    public S7Writer u2(int v) {
        buf.putShort((short) (v & 0xFFFF));
        return this;
    }

    /** 写无符号 32 位 (U4)，大端 */
    public S7Writer u4(long v) {
        buf.putInt((int) (v & 0xFFFFFFFFL));
        return this;
    }

    /** 写无符号 16 位，小端（复刻 .NET 未 swap 的原始值落盘） */
    public S7Writer u2Le(int v) {
        buf.put((byte) (v & 0xFF));
        buf.put((byte) ((v >> 8) & 0xFF));
        return this;
    }

    /** 写无符号 32 位，小端（复刻 .NET 未 swap 的原始值落盘） */
    public S7Writer u4Le(long v) {
        buf.put((byte) (v & 0xFF));
        buf.put((byte) ((v >> 8) & 0xFF));
        buf.put((byte) ((v >> 16) & 0xFF));
        buf.put((byte) ((v >> 24) & 0xFF));
        return this;
    }

    /** 写 ASCII 字符串固定 n 字节（不足补 0，超出截断），对应 char[n] */
    public S7Writer ascii(String s, int n) {
        byte[] src = s == null ? new byte[0] : s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[n];
        System.arraycopy(src, 0, out, 0, Math.min(src.length, n));
        buf.put(out);
        return this;
    }

    /** 写原始字节 */
    public S7Writer bytes(byte[] b) {
        buf.put(b);
        return this;
    }

    /** 填充 n 个 0 字节 */
    public S7Writer zeros(int n) {
        buf.put(new byte[n]);
        return this;
    }

    public byte[] toBytes() {
        return buf.array();
    }
}

package cn.aimstek.loong.aidiag.qltool.transport.moka7;

import java.util.HexFormat;

/**
 * 十六进制格式化工具（源自 iot 项目 moka7 实现，仅改包名）。
 */
public class BufferFormatter {
    private static final HexFormat hexFormat = HexFormat.of().withDelimiter(" ");

    /**
     * 格式化字节数组
     */
    public static String format(byte[] buffer) {
        return format(buffer, 0, buffer.length);
    }

    /**
     * 格式化字节数组
     */
    public static String format(byte[] buffer, int length) {
        return format(buffer, 0, length);
    }

    /**
     * 格式化字节数组
     */
    public static String format(byte[] buffer, int offset, int length) {
        if (offset + length > buffer.length) {
            length = buffer.length - offset;
        }
        return hexFormat.formatHex(buffer, offset, offset + length);
    }

    /**
     * 解析十六进制字符串为字节数组
     */
    public static byte[] parse(String data) {
        return hexFormat.parseHex(data);
    }
}

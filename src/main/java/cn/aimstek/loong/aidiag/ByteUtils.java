package cn.aimstek.loong.aidiag;

public class ByteUtils {
    /**
     * 将 byte 数组转换为 16 进制字符串
     *
     * @param bytes 输入的 byte 数组
     * @return 16 进制字符串
     */
    public static String bytesToHex(byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            // 将字节转换为无符号整数
            int unsignedValue = b & 0xFF;
            // 将整数转换为 16 进制字符串
            hexString.append(String.format("%02X", unsignedValue));
        }
        return hexString.toString();
    }

    public static String bytesToHex(Byte[] bytes) {
        StringBuilder hexString = new StringBuilder();
        for (byte b : bytes) {
            // 将字节转换为无符号整数
            int unsignedValue = b & 0xFF;
            // 将整数转换为 16 进制字符串
            hexString.append(String.format("%02X", unsignedValue));
        }
        return hexString.toString();
    }

    /**
     * 追加高位位置为0
     *
     * @param originalArray
     * @return
     */
    public static byte[] add2Zeros(byte[] originalArray) {
        byte[] newArray = new byte[originalArray.length + 2];
        newArray[0] = 0;
        newArray[1] = 0;
        System.arraycopy(originalArray, 0, newArray, 2, originalArray.length);
        return newArray;
    }

    /**
     * 减少高位
     *
     * @param originalArray
     * @return
     */
    public static byte[] del2Zeros(byte[] originalArray) {
        if (originalArray.length <= 2) {
            return originalArray;
        }
        byte[] newArray = new byte[originalArray.length - 2];
        System.arraycopy(originalArray, 2, newArray, 0, originalArray.length - 2);
        return newArray;
    }

    public static int bytesToInt(byte[] bytes) {
        if (bytes.length < 4) {
            throw new IllegalArgumentException("Byte array length must be at least 4");
        }
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }
    public static int bytesToInt(Byte[] bytes) {
        if (bytes.length < 4) {
            throw new IllegalArgumentException("Byte array length must be at least 4");
        }
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }
    public static short bytesToShort(byte[] bytes) {
        if (bytes.length < 2) {
            throw new IllegalArgumentException("字节数组长度不足2");
        }
        // 第一个字节左移8位作为高8位，第二个字节作为低8位
        return (short) ((bytes[0] << 8) | (bytes[1] & 0xFF));
    }

    /**
     * int 转 byte[]
     *
     * @param value
     * @return
     */
    public static byte[] intToByteArray(int value) {
        byte[] result = new byte[4];
        result[0] = (byte) (value >>> 24);
        result[1] = (byte) (value >>> 16);
        result[2] = (byte) (value >>> 8);
        result[3] = (byte) value;
        return result;
    }

    public static Byte[] intToByteBoxArray(int value) {
        Byte[] result = new Byte[4];
        result[0] = (byte) (value >>> 24);
        result[1] = (byte) (value >>> 16);
        result[2] = (byte) (value >>> 8);
        result[3] = (byte) value;
        return result;
    }

    /**
     * short 转 byte[]
     *
     * @param value
     * @return
     */
    public static byte[] shortToByteArray(short value) {
        byte[] result = new byte[2];
        result[0] = (byte) (value >>> 8);
        result[1] = (byte) value;
        return result;
    }

    /**
     * short 转 boolean[]
     * 由低到高排序
     *
     * @param value
     * @return
     */
    public static boolean[] shortToBooleanArray(short value) {
        byte[] byteArray = shortToByteArray(value);
        boolean[] boolArray = new boolean[16];
        int boolArrayIndex = boolArray.length;
        for (byte b : byteArray) {
            for (int i = 0; i < 8; i++) {
                // 检查字节中的每个位
                boolArray[--boolArrayIndex] = ((b >> (7 - i)) & 1) == 1;
            }
        }
        return boolArray;
    }

    /**
     * byte 转 boolean[]
     * 由低到高排序
     *
     * @param value
     * @return
     */
    public static boolean[] byteToBooleanArray(byte value) {
        boolean[] boolArray = new boolean[8];
        int boolArrayIndex = boolArray.length;
        for (int i = 0; i < 8; i++) {
            // 检查字节中的每个位
            boolArray[--boolArrayIndex] = ((value >> (7 - i)) & 1) == 1;
        }
        return boolArray;
    }

    /**
     * int 转 short
     *
     * @param value
     * @return
     */
    public static byte[] intToShortByteArray(int value) {
        byte[] result = new byte[2];
        result[0] = (byte) (value >>> 8);
        result[1] = (byte) value;
        return result;
    }

    public static int bytesShortToInt(byte[] bytes) {
        byte[] stringBytes = ByteUtils.add2Zeros(bytes);
        return ByteUtils.bytesToInt(stringBytes);
    }

    public static int shortToInt(Short value) {
        return value & 0xFFFF;
    }

    public static int bytes2Int(byte[] b, int start, int len) {
        int sum = 0;
        int end = start + len;
        for (int i = start; i < end; i++) {
            int n = ((int) b[i]) & 0xff;
            n <<= (--len) * 8;
            sum = n + sum;
        }
        return sum;
    }

    public static String toAsciiString(byte[] bytes) {
        if (bytes == null) return "";

        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            // 关键步骤：将字节转为无符号整数（0~255）
            int unsignedValue = b & 0xFF;

            // 处理非 ASCII 字符（128~255 替换为 '?'）
            if (unsignedValue > 127) {
                sb.append('?');
            } else {
                sb.append((char) unsignedValue);
            }
        }
        return sb.toString();
    }

    public static Byte[] convertToBoxedArray(byte[] primitiveArray) {
        if (primitiveArray == null) {
            return null;
        }
        Byte[] boxedArray = new Byte[primitiveArray.length];
        for (int i = 0; i < primitiveArray.length; i++) {
            boxedArray[i] = primitiveArray[i]; // 自动装箱
        }
        return boxedArray;
    }

}

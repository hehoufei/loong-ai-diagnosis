package cn.aimstek.loong.aidiag.qltool.transport.moka7;

/**
 * S7 协议常量：错误码、ISO/COTP/S7 电报模板（源自 iot 项目 moka7 实现，仅改包名）。
 */
public class S7Const {
    // Word Length
    private static final byte s7WLByte = 0x02;
    // Error Codes
    public static final int errNoError = 0x00;
    public static final int errTCPConnectionFailed = 0x0001;
    public static final int errTCPDataSend = 0x0002;
    public static final int errTCPDataRecv = 0x0003;
    public static final int errTCPDataRecvTout = 0x0004;
    public static final int errTCPConnectionReset = 0x0005;
    public static final int errISOInvalidPDU = 0x0006;
    public static final int errISOConnectionFailed = 0x0007;
    public static final int errISONegotiatingPDU = 0x0008;
    public static final int errS7InvalidPDU = 0x0009;
    public static final int errS7DataRead = 0x000A;
    public static final int errS7DataWrite = 0x000B;
    public static final int errS7BufferTooSmall = 0x000C;
    public static final int errS7FunctionError = 0x000D;
    public static final int errS7InvalidParams = 0x000E;

    public static String getErrorText(int Error) {
        return switch (Error) {
            case errTCPConnectionFailed -> "TCP Connection failed.";
            case errTCPDataSend -> "TCP Sending error.";
            case errTCPDataRecv -> "TCP Receiving error.";
            case errTCPDataRecvTout -> "Data Receiving timeout.";
            case errTCPConnectionReset -> "Connection reset by the peer.";
            case errISOInvalidPDU -> "Invalid ISO PDU received.";
            case errISOConnectionFailed -> "ISO connection refused by the CPU.";
            case errISONegotiatingPDU -> "ISO error negotiating the PDU length.";
            case errS7InvalidPDU -> "Invalid S7 PDU received.";
            case errS7DataRead -> "S7 Error reading data from the CPU.";
            case errS7DataWrite -> "S7 Error writing data to the CPU.";
            case errS7BufferTooSmall -> "The Buffer supplied to the function is too small.";
            case errS7FunctionError -> "S7 function refused by the CPU.";
            case errS7InvalidParams -> "Invalid parameters supplied to the function.";
            default -> "Unknown error : 0x" + Integer.toHexString(Error);
        };
    }

    // ISO Connection Request telegram (contains also ISO Header and COTP Header)
    private static final byte[] ISO_CR = {
            // TPKT (RFC1006 Header)
            (byte) 0x03, // RFC 1006 ID (3)
            (byte) 0x00, // Reserved, always 0
            (byte) 0x00, // High part of packet length
            (byte) 0x16, // Low part of packet length
            // COTP (ISO 8073 Header)
            (byte) 0x11, // PDU Size Length
            (byte) 0xE0, // CR - Connection Request ID
            (byte) 0x00, // Dst Reference HI
            (byte) 0x00, // Dst Reference LO
            (byte) 0x00, // Src Reference HI
            (byte) 0x01, // Src Reference LO
            (byte) 0x00, // Class + Options Flags
            (byte) 0xC0, // PDU Max Length ID
            (byte) 0x01, // PDU Max Length HI
            (byte) 0x0A, // PDU Max Length LO
            (byte) 0xC1, // Src TSAP Identifier
            (byte) 0x02, // Src TSAP Length (2 bytes)
            (byte) 0x01, // Src TSAP HI (will be overwritten)
            (byte) 0x00, // Src TSAP LO (will be overwritten)
            (byte) 0xC2, // Dst TSAP Identifier
            (byte) 0x02, // Dst TSAP Length (2 bytes)
            (byte) 0x01, // Dst TSAP HI (will be overwritten)
            (byte) 0x02  // Dst TSAP LO (will be overwritten)
    };

    public static byte[] createIsoCR() {
        return ISO_CR.clone();
    }

    // S7 PDU Negotiation Telegram (contains also ISO Header and COTP Header)
    private static final byte[] S7_PN = {
            (byte) 0x03, (byte) 0x00, (byte) 0x00, (byte) 0x19,
            (byte) 0x02, (byte) 0xf0, (byte) 0x80, // TPKT + COTP
            (byte) 0x32, (byte) 0x01, (byte) 0x00, (byte) 0x00,
            (byte) 0x04, (byte) 0x00, (byte) 0x00, (byte) 0x08,
            (byte) 0x00, (byte) 0x00, (byte) 0xf0, (byte) 0x00,
            (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x01,
            (byte) 0x00, (byte) 0x1e // PDU Length Requested = HI-LO 480 bytes
    };

    public static byte[] createS7PN() {
        return S7_PN.clone();
    }

    // S7 Read/Write Request Header (contains also ISO Header and COTP Header)
    private static final byte[] S7_RW = { // 31-35 bytes
            (byte) 0x03, (byte) 0x00,
            (byte) 0x00, (byte) 0x1f,  // Telegram Length (Data Size + 31 or 35)
            (byte) 0x02, (byte) 0xf0, (byte) 0x80, // COTP
            (byte) 0x32,             // S7 Protocol ID
            (byte) 0x01,             // Job Type
            (byte) 0x00, (byte) 0x00,  // Redundancy identification
            (byte) 0x00, (byte) 0x05,  // PDU Reference
            (byte) 0x00, (byte) 0x0e,  // Parameters Length
            (byte) 0x00, (byte) 0x00,  // Data Length = Size(bytes) + 4
            (byte) 0x04,             // Function 4 Read Var, 5 Write Var
            (byte) 0x01,             // Items count
            (byte) 0x12,             // Var spec.
            (byte) 0x0a,             // Length of remaining bytes
            (byte) 0x10,             // Syntax ID
            s7WLByte,                // Transport Size
            (byte) 0x00, (byte) 0x00,  // Num Elements
            (byte) 0x00, (byte) 0x00,  // DB Number (if any, else 0)
            (byte) 0x84,             // Area Type
            (byte) 0x00, (byte) 0x00, (byte) 0x00, // Area Offset
            // WR area
            (byte) 0x00,             // Reserved
            (byte) 0x04,             // Transport size
            (byte) 0x00, (byte) 0x00,  // Data Length * 8 (if not timer or counter)
    };

    public static byte[] createS7RW(int sizeWR, short pduReference) {
        var pduBuffer = new byte[2048];
        System.arraycopy(S7_RW, 0, pduBuffer, 0, sizeWR);
        S7.setWordAt(pduBuffer, 11, pduReference);
        return pduBuffer;
    }
}

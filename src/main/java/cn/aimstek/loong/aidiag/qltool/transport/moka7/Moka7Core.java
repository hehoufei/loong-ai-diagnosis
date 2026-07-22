package cn.aimstek.loong.aidiag.qltool.transport.moka7;

import lombok.extern.slf4j.Slf4j;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;

/**
 * Moka7 协议核心：TCP 连接 → ISO 握手 → PDU 协商 → DB 区读写。
 * <p>源自 iot 项目 {@code cn.aimstek.loong.plugin.iot.aims7.core.S7Client}，
 * 重命名为 {@code Moka7Core} 避免与 {@code transport.S7Client} 接口同名冲突。
 * <p>仅改包名与类名，协议逻辑保持原样。
 */
@Slf4j
public class Moka7Core {
    // Public fields
    public volatile boolean connected = false;
    public int lastError = S7Const.errNoError;
    public int recvTimeout = 5000;
    // const
    private static final byte s7WLCounter = 0x1C;
    private static final byte s7WLTimer = 0x1D;
    // Privates
    private static final int isoTcpPort = 102;
    private static final int minPduSize = 16;
    private static final int defaultPduSizeRequested = 480;
    private static final int isoHSize = 7; // TPKT+COTP Header Size
    private static final int maxPduSize = defaultPduSizeRequested + isoHSize;
    public volatile Socket tcpSocket;
    private volatile DataInputStream tcpInStream = null;
    private volatile DataOutputStream tcpOutStream = null;
    private String tcpAddress;
    private int tcpPort;
    private byte localTSAPHI;
    private byte localTSAPLO;
    private byte remoteTSAPHI;
    private byte remoteTSAPLO;
    private byte lastPDUType;
    private short connType = S7.OP;
    private int pduLength = 0;
    private static final int S7_SIZE_RD = 31;
    private static final int S7_SIZE_WR = 35;
    private int reconnectTimes = 0;
    private volatile boolean closeRequested = false;

    private short pduReference = 10;

    public Moka7Core() {
    }

    /**
     * 连接到指定地址（默认端口 102）
     */
    public int connectTo(String address, int rack, int slot) {
        return connectTo(address, rack, slot, isoTcpPort);
    }

    /**
     * 连接到指定地址和端口
     */
    public int connectTo(String address, int rack, int slot, int port) {
        int remoteTSAP = (connType << 8) + (rack * 0x20) + slot;
        setConnectionParams(address, port, 0x0100, remoteTSAP);
        closeRequested = false;
        return connect();
    }

    /**
     * 建立连接
     */
    public int connect() {
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        if (connected) {
            return S7Const.errNoError;
        }
        lastError = tcpConnect();
        if (lastError != S7Const.errNoError) {
            log.error("TCP连接异常，{}", S7Const.getErrorText(lastError));
            connected = false;
            return lastError;
        }
        if (closeRequested) {
            tcpRelease();
            return S7Const.errTCPConnectionFailed;
        }
        lastError = isoHandShake();
        if (lastError != S7Const.errNoError) {
            log.error("ISO连接异常，{}", S7Const.getErrorText(lastError));
            connected = false;
            tcpRelease();
            return lastError;
        }
        lastError = negotiatePduLength();
        if (lastError != S7Const.errNoError) {
            log.error("PDU negotiation异常，{}", S7Const.getErrorText(lastError));
            connected = false;
            tcpRelease();
            return lastError;
        }
        if (closeRequested) {
            tcpRelease();
            return S7Const.errTCPConnectionFailed;
        }
        connected = true;
        return S7Const.errNoError;
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        closeRequested = true;
        tcpRelease();
        pduLength = 0;
        connected = false;
    }

    /**
     * 重新连接
     */
    public int reconnect() {
        log.warn("{}:{}正在尝试重新连接", tcpAddress, tcpPort);
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        tcpRelease();
        pduLength = 0;
        connected = false;
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        return connect();
    }

    public void setConnectionType(short connectionType) {
        connType = connectionType;
    }

    /**
     * 读取 DB 区域（synchronized 保证单连接串行）
     */
    public synchronized int readArea(int area, int dbNumber, int start, int amount, byte[] data) {
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        if (amount == 0) {
            return S7Const.errNoError;
        }
        lastError = readAreaInternal(S7.S7_AREA_DB, dbNumber, start, amount, data);
        if (lastError != S7Const.errNoError && !closeRequested) {
            log.warn("{}:{}(DB{}.{} Len={})读取数据异常{}，重新建立连接", tcpAddress, tcpPort, dbNumber, start, amount, S7Const.getErrorText(lastError));
            // 重试前清零缓冲区，避免残留部分帧的脏数据
            java.util.Arrays.fill(data, (byte) 0);
            int reconnectError = reconnect();
            if (reconnectError == S7Const.errNoError && !closeRequested) {
                lastError = readAreaInternal(S7.S7_AREA_DB, dbNumber, start, amount, data);
            } else {
                lastError = reconnectError;
            }
        }
        return lastError;
    }

    /**
     * 读取区域（内部实现，按 PDU 自动分帧）
     */
    private int readAreaInternal(int area, int dbNumber, int start, int amount, byte[] data) {
        int address;
        int offset = 0;
        var recvPDU = new byte[2048];
        lastError = S7Const.errNoError;
        // If we are addressing Timers or counters the element size is 2
        var wordSize = (area == S7.S7_AREA_CT) || (area == S7.S7_AREA_TM)
                ? 2
                : 1;
        // Reply telegram header = 18
        var maxElements = (pduLength - 18) / wordSize;
        if (pduLength == 0 || maxElements <= 0) {
            log.warn("PDULength：{}, maxElements: {}", pduLength, maxElements);
            return S7Const.errS7InvalidPDU;
        }
        var totalElements = amount;

        while ((totalElements > 0) && (lastError == S7Const.errNoError)) {
            var numElements = Math.min(totalElements, maxElements);
            var sizeRequested = numElements * wordSize;
            // Setup the telegram
            var sendPDU = S7Const.createS7RW(S7_SIZE_RD, generatePduReference());
            // Set DB Number
            sendPDU[27] = (byte) area;
            // Set Area
            if (area == S7.S7_AREA_DB) {
                S7.setWordAt(sendPDU, 25, dbNumber);
            }
            // Adjusts Start and word length
            if ((area == S7.S7_AREA_CT) || (area == S7.S7_AREA_TM)) {
                address = start;
                sendPDU[22] = area == S7.S7_AREA_CT ? s7WLCounter : s7WLTimer;
            } else {
                address = start << 3;
            }
            // Num elements
            S7.setWordAt(sendPDU, 23, numElements);
            // Address into the PLC (only 3 bytes)
            sendPDU[30] = (byte) (address & 0x0FF);
            address = address >> 8;
            sendPDU[29] = (byte) (address & 0x0FF);
            address = address >> 8;
            sendPDU[28] = (byte) (address & 0x0FF);
            lastError = sendPacket(sendPDU, S7_SIZE_RD);

            if (lastError == S7Const.errNoError) {
                var recvLength = recvIsoPacketPDU(recvPDU);
                if (lastError == S7Const.errNoError) {
                    if (recvLength >= 25) {
                        if ((recvLength - 25 == sizeRequested) && (recvPDU[21] == (byte) 0xFF)) {
                            System.arraycopy(recvPDU, 25, data, offset, sizeRequested);
                            offset += sizeRequested;
                        } else {
                            log.warn("ReadArea from{}:{}(DB{}.{} Len={}) recvIsoPacketSize={}, returnCode=0x{}, send={}, recv={}",
                                    tcpAddress, tcpPort, dbNumber, start, numElements, recvLength,
                                    String.format("%02X", recvPDU[21]),
                                    BufferFormatter.format(sendPDU, S7_SIZE_RD), BufferFormatter.format(recvPDU, recvLength));
                            lastError = S7Const.errS7DataRead;
                        }
                    } else {
                        log.warn("ReadArea from{}:{}(DB{}.{} Len={}) recvIsoPacketSize={}, send={}, recv={}", tcpAddress, tcpPort, dbNumber, start, numElements, recvLength, BufferFormatter.format(sendPDU, S7_SIZE_RD), BufferFormatter.format(recvPDU, recvLength));
                        lastError = S7Const.errS7InvalidPDU;
                    }
                }
            }
            totalElements -= numElements;
            start += numElements * wordSize;
        }
        return lastError;
    }

    /**
     * 写入 DB 区域（synchronized 保证单连接串行）
     */
    public synchronized int writeArea(int area, int dbNumber, int start, int amount, byte[] data) {
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        var lastError = writeAreaInternal(S7.S7_AREA_DB, dbNumber, start, amount, data);
        if (lastError != S7Const.errNoError && !closeRequested) {
            log.warn("{}:{}(DB{}.{} Len={})写入数据异常{}，重新建立连接", tcpAddress, tcpPort, dbNumber, start, amount, S7Const.getErrorText(lastError));
            int reconnectError = reconnect();
            if (reconnectError == S7Const.errNoError && !closeRequested) {
                lastError = writeAreaInternal(S7.S7_AREA_DB, dbNumber, start, amount, data);
            } else {
                lastError = reconnectError;
            }
        }
        return lastError;
    }

    private int writeAreaInternal(int area, int dbNumber, int start, int amount, byte[] data) {
        if (amount == 0) {
            return S7Const.errNoError;
        }
        int address;
        int offset = 0;
        var recvPDU = new byte[2048];
        lastError = S7Const.errNoError;
        var wordSize = (area == S7.S7_AREA_CT) || (area == S7.S7_AREA_TM)
                ? 2
                : 1;
        var maxElements = (pduLength - 35) / wordSize;
        if (pduLength == 0 || maxElements <= 0) {
            log.warn("PLC {}:{} PDULength：{}, maxElements: {}", tcpAddress, tcpPort, pduLength, maxElements);
            return S7Const.errS7InvalidPDU;
        }
        var totalElements = amount;

        while ((totalElements > 0) && (lastError == 0)) {
            var numElements = Math.min(totalElements, maxElements);
            var dataSize = numElements * wordSize;
            var isoSize = S7_SIZE_WR + dataSize;

            // Setup the telegram
            var sendPDU = S7Const.createS7RW(S7_SIZE_WR, generatePduReference());
            // Whole telegram Size
            S7.setWordAt(sendPDU, 2, isoSize);
            // Data Length
            var recvLength = dataSize + 4;
            S7.setWordAt(sendPDU, 15, recvLength);
            // Function
            sendPDU[17] = (byte) 0x05;
            // Set DB Number
            sendPDU[27] = (byte) area;
            if (area == S7.S7_AREA_DB) {
                S7.setWordAt(sendPDU, 25, dbNumber);
            }
            // Adjusts Start and word length
            if ((area == S7.S7_AREA_CT) || (area == S7.S7_AREA_TM)) {
                address = start;
                recvLength = dataSize;
                if (area == S7.S7_AREA_CT)
                    sendPDU[22] = s7WLCounter;
                else
                    sendPDU[22] = s7WLTimer;
            } else {
                address = start << 3;
                recvLength = dataSize << 3;
            }
            // Num elements
            S7.setWordAt(sendPDU, 23, numElements);
            // Address into the PLC
            sendPDU[30] = (byte) (address & 0x0FF);
            address = address >> 8;
            sendPDU[29] = (byte) (address & 0x0FF);
            address = address >> 8;
            sendPDU[28] = (byte) (address & 0x0FF);
            // Length
            S7.setWordAt(sendPDU, 33, recvLength);
            // Copies the Data
            System.arraycopy(data, offset, sendPDU, 35, dataSize);
            sendPacket(sendPDU, isoSize);
            if (lastError == S7Const.errNoError) {
                recvLength = recvIsoPacketPDU(recvPDU);
                if (lastError == S7Const.errNoError) {
                    if (recvLength == 22) {
                        if (recvPDU[21] != (byte) 0xFF) {
                            log.warn("WriteArea from{}:{}(DB{}.{} Len={}) recvIsoPacket={}, returnCode=0x{}, send={}, recv={}",
                                    tcpAddress, tcpPort, dbNumber, start, numElements, recvLength,
                                    String.format("%02X", recvPDU[21]),
                                    BufferFormatter.format(sendPDU, isoSize), BufferFormatter.format(recvPDU, recvLength));
                            lastError = S7Const.errS7DataWrite;
                        }
                    } else {
                        log.warn("WriteArea from{}:{}(DB{}.{} Len={}) recvIsoPacket={}, send={}, recv={}", tcpAddress, tcpPort, dbNumber, start, numElements, recvLength, BufferFormatter.format(sendPDU, isoSize), BufferFormatter.format(recvPDU, recvLength));
                        lastError = S7Const.errS7InvalidPDU;
                    }
                }
            }
            offset += dataSize;
            totalElements -= numElements;
            start += numElements * wordSize;
        }
        return lastError;
    }

    // ===== 内部方法 =====

    private void setConnectionParams(String address, int port, int localTSAP, int remoteTSAP) {
        int locTSAP = localTSAP & 0x0000FFFF;
        int remTSAP = remoteTSAP & 0x0000FFFF;
        tcpAddress = address;
        tcpPort = port;
        localTSAPHI = (byte) (locTSAP >> 8);
        localTSAPLO = (byte) (locTSAP & 0x00FF);
        remoteTSAPHI = (byte) (remTSAP >> 8);
        remoteTSAPLO = (byte) (remTSAP & 0x00FF);
    }

    private int tcpConnect() {
        SocketAddress sockAddr = new InetSocketAddress(tcpAddress, tcpPort);
        lastError = S7Const.errNoError;
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        try {
            Socket socket = new Socket();
            tcpSocket = socket;
            socket.connect(sockAddr, 5000);
            if (closeRequested) {
                socket.close();
                return S7Const.errTCPConnectionFailed;
            }
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            tcpInStream = new DataInputStream(socket.getInputStream());
            tcpOutStream = new DataOutputStream(socket.getOutputStream());
        } catch (IOException e) {
            log.error("TCP{}:{}连接异常，{}", tcpAddress, tcpPort, e.getMessage());
            lastError = S7Const.errTCPConnectionFailed;
        }
        if (lastError == S7Const.errNoError) {
            reconnectTimes = 0;
            return lastError;
        }
        if (closeRequested) {
            return S7Const.errTCPConnectionFailed;
        }
        reconnectTimes = Math.min(reconnectTimes + 1, 10);
        try {
            Thread.sleep(500L * reconnectTimes);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return lastError;
    }

    private void tcpRelease() {
        Socket socket = tcpSocket;
        DataInputStream input = tcpInStream;
        DataOutputStream output = tcpOutStream;
        tcpSocket = null;
        tcpInStream = null;
        tcpOutStream = null;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
        if (output != null) {
            try {
                output.close();
            } catch (IOException ignored) {
            }
        }
    }

    private int isoHandShake() {
        var connectionRequest = S7Const.createIsoCR();
        connectionRequest[16] = localTSAPHI;
        connectionRequest[17] = localTSAPLO;
        connectionRequest[20] = remoteTSAPHI;
        connectionRequest[21] = remoteTSAPLO;

        // Sends the connection request telegram
        sendPacket(connectionRequest);
        if (lastError != S7Const.errNoError) {
            return lastError;
        }
        var recvPDU = new byte[2048];
        var size = recvIsoPacketPDU(recvPDU);
        if (lastError != S7Const.errNoError) {
            return lastError;
        }
        if (size == 22) {
            // 0xD0 = CC Connection confirm
            if (lastPDUType != (byte) 0xD0) {
                lastError = S7Const.errISOConnectionFailed;
            }
        } else {
            lastError = S7Const.errISOInvalidPDU;
        }
        return lastError;
    }

    private int recvIsoPacketPDU(byte[] recvPDU) {
        boolean done = false;
        int size = 0;
        while ((lastError == S7Const.errNoError) && !done) {
            recvPacket(recvPDU, 0, 4);
            if (lastError == S7Const.errNoError && recvPDU[0] == 0x03 && recvPDU[1] == 0x00) {
                size = S7.getWordAt(recvPDU, 2);
                if (size == isoHSize) {
                    recvPacket(recvPDU, 4, 3);
                } else {
                    if ((size > maxPduSize) || (size < minPduSize)) {
                        log.warn("PLC {}:{} ErrISOInvalidPDU = {}", tcpAddress, tcpPort, size);
                        lastError = S7Const.errISOInvalidPDU;
                    } else {
                        done = true;
                    }
                }
            }
        }
        // get last buffer
        if (lastError == S7Const.errNoError) {
            // Skip remaining 3 COTP bytes
            recvPacket(recvPDU, 4, 3);
            // Stores PDU Type, we need it
            lastPDUType = recvPDU[5];
            // Receives the S7 Payload
            recvPacket(recvPDU, 7, size - isoHSize);
        }
        return lastError == S7Const.errNoError ? size : 0;
    }

    private int negotiatePduLength() {
        int length;
        var s7pn = S7Const.createS7PN();
        // Set PDU Size Requested
        S7.setWordAt(s7pn, 23, defaultPduSizeRequested);
        // Sends the connection request telegram
        lastError = sendPacket(s7pn);
        if (lastError != S7Const.errNoError) {
            return lastError;
        }
        var recvPDU = new byte[2048];
        length = recvIsoPacketPDU(recvPDU);
        if (lastError == S7Const.errNoError) {
            if ((length == 27) && (recvPDU[17] == 0) && (recvPDU[18] == 0)) {
                pduLength = S7.getWordAt(recvPDU, 25);
                if (pduLength > 0)
                    return S7Const.errNoError;
                else
                    lastError = S7Const.errISONegotiatingPDU;
            } else
                lastError = S7Const.errISONegotiatingPDU;
        } else {
            log.error("S7 client negotiatePduLength to {}:{} error, Data:{}", tcpAddress, tcpPort, BufferFormatter.format(recvPDU, 0, length));
        }
        return lastError;
    }

    private int recvPacket(byte[] buffer, int start, int size) {
        int bytesRead = 0;
        lastError = waitForData(size, recvTimeout, buffer);
        if (lastError != S7Const.errNoError) {
            return lastError;
        }
        try {
            DataInputStream input = tcpInStream;
            if (input == null) {
                return lastError = S7Const.errTCPConnectionReset;
            }
            bytesRead = input.read(buffer, start, size);
        } catch (IOException ex) {
            lastError = S7Const.errTCPDataRecv;
        }
        if (bytesRead == 0) {
            lastError = S7Const.errTCPConnectionReset;
        }
        return lastError;
    }

    private int sendPacket(byte[] buffer) {
        return sendPacket(buffer, buffer.length);
    }

    private int sendPacket(byte[] buffer, int len) {
        lastError = S7Const.errNoError;
        try {
            DataOutputStream output = tcpOutStream;
            if (output == null) {
                return lastError = S7Const.errTCPConnectionReset;
            }
            output.write(buffer, 0, len);
            output.flush();
        } catch (IOException ex) {
            log.error("S7 client sendPacket to {}:{} error", tcpAddress, tcpPort, ex);
            lastError = S7Const.errTCPDataSend;
        }
        return lastError;
    }

    private int waitForData(int size, int timeout, byte[] recvPDU) {
        int counter = 0;
        lastError = S7Const.errNoError;
        boolean expired = false;
        DataInputStream input = tcpInStream;
        if (input == null || closeRequested) {
            return S7Const.errTCPConnectionReset;
        }
        try {
            var sizeAvail = input.available();
            while ((sizeAvail < size) && (!expired) && (lastError == 0) && !closeRequested) {
                counter++;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException ex) {
                    log.error("WaitForData Interrupted Exception:{}", ex.getMessage());
                    lastError = S7Const.errTCPDataRecvTout;
                }
                sizeAvail = input.available();
                expired = counter > timeout;
                // If timeout we clean the buffer
                if (expired && (sizeAvail > 0) && (lastError == 0)) {
                    input.read(recvPDU, 0, sizeAvail);
                }
            }
        } catch (IOException ex) {
            log.warn("waitForData from {}:{} Exception:{}", tcpAddress, tcpPort, ex.getMessage());
            lastError = S7Const.errTCPConnectionReset;
        }
        if (closeRequested) {
            lastError = S7Const.errTCPConnectionReset;
        } else if (counter >= timeout) {
            log.warn("waitForData from {}:{} timeout: counter{} >= timeout{}", tcpAddress, tcpPort, counter, timeout);
            lastError = S7Const.errTCPDataRecvTout;
        }
        return lastError;
    }

    private short generatePduReference() {
        pduReference++;
        if (pduReference > 0x7FFF)
            pduReference = 10;
        return pduReference;
    }
}

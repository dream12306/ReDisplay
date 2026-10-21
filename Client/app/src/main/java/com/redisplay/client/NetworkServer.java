package com.redisplay.client;

import android.util.Log;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicInteger;

public class NetworkServer implements Runnable {
    private static final String TAG = "ReDisplay_Net";
    public static final int PORT = 27183;
    public static final int MAGIC = 0x52445350; // "RDSP"
    public static final int PACKET_TYPE_PONG = 4;
    public static final int PACKET_TYPE_PEN = 5;
    public static final int PACKET_TYPE_WHEEL_CONFIG = 6;
    public static final int PACKET_TYPE_INPUT_COMMAND = 7;
    public static final int PACKET_TYPE_MACRO_CATALOG = 8;
    public static final int PEN_PAYLOAD_SIZE = 20;
    public static final int INPUT_COMMAND_PAYLOAD_SIZE = 20;

    public static final int INPUT_ACTION_NONE = 0;
    public static final int INPUT_ACTION_KEY_CHORD = 1;
    public static final int INPUT_ACTION_RIGHT_CLICK = 2;
    public static final int INPUT_ACTION_RECORDING_ZONE = 3;
    public static final int INPUT_ACTION_RECORD_START = 4;
    public static final int INPUT_ACTION_RECORD_STOP = 5;
    public static final int INPUT_ACTION_RECORD_CANCEL = 6;
    public static final int INPUT_ACTION_SAVE_LAST_STROKE = 7;
    public static final int INPUT_ACTION_SAVE_LAST_TAP = 8;
    public static final int INPUT_ACTION_REPLAY_LAST_STROKE = 9;
    public static final int INPUT_ACTION_REPLAY_LAST_TAP = 10;
    public static final int INPUT_ACTION_REPLAY_LATEST = 11;
    public static final int INPUT_ACTION_BACK = 12;
    public static final int INPUT_ACTION_LEFT_CLICK = 13;
    public static final int INPUT_ACTION_DOUBLE_CLICK = 14;
    public static final int INPUT_ACTION_MACRO_LIBRARY = 15;
    public static final int INPUT_ACTION_EXECUTE_MACRO = 16;
    public static final int INPUT_ACTION_MACRO_PAGE_PREVIOUS = 17;
    public static final int INPUT_ACTION_MACRO_PAGE_NEXT = 18;
    public static final int INPUT_ACTION_TOGGLE_ERASER_MODE = 19;
    public static final int INPUT_ACTION_ENTER_MOUSE_MODE = 20;
    public static final int INPUT_ACTION_EXIT_MOUSE_MODE = 21;

    public static final int PEN_ACTION_HOVER_ENTER = 1;
    public static final int PEN_ACTION_HOVER_MOVE = 2;
    public static final int PEN_ACTION_HOVER_EXIT = 3;
    public static final int PEN_ACTION_DOWN = 4;
    public static final int PEN_ACTION_MOVE = 5;
    public static final int PEN_ACTION_UP = 6;
    public static final int PEN_ACTION_CANCEL = 7;

    public static final int PEN_TOOL_STYLUS = 1;
    public static final int PEN_TOOL_ERASER = 2;
    public static final int PEN_BUTTON_BARREL = 1;
    public static final int PEN_BUTTON_SECONDARY = 2;

    public interface StatusListener {
        void onConnected(String hostInfo);
        void onDisconnected();
        void onResolutionChanged(int width, int height, int fps);
        void onTouchInputConfig(TouchInputConfig config);
        void onStatsUpdate(float fps, int bitrateKbps);
        void onWheelConfig(WheelConfig config);
        void onMacroCatalog(MacroCatalog catalog);
    }

    public static final class WheelSlot {
        public final int action;
        public final int modifiers;
        public final int keyCode;
        public final long parameter;
        public final String label;

        WheelSlot(int action, int modifiers, int keyCode, long parameter, String label) {
            this.action = action;
            this.modifiers = modifiers;
            this.keyCode = keyCode;
            this.parameter = parameter;
            this.label = label;
        }
    }

    public static final class WheelConfig {
        public final long revision;
        public final List<WheelSlot> slots;

        WheelConfig(long revision, List<WheelSlot> slots) {
            this.revision = revision;
            this.slots = slots;
        }
    }

    public static final class MacroCatalog {
        public final long revision;
        public final List<WheelSlot> items;

        MacroCatalog(long revision, List<WheelSlot> items) {
            this.revision = revision;
            this.items = items;
        }
    }

    private final VideoDecoder decoder;
    private final StatusListener listener;
    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private volatile Socket clientSocket;
    private volatile ClientWriter clientWriter;
    private final AtomicInteger penSequence = new AtomicInteger();
    private final AtomicInteger commandSequence = new AtomicInteger();
    private Thread serverThread;

    public NetworkServer(VideoDecoder decoder, StatusListener listener) {
        this.decoder = decoder;
        this.listener = listener;
    }

    public synchronized void start() {
        if (!running) {
            running = true;
            serverThread = new Thread(this, "ReDisplay-NetServer");
            serverThread.start();
        }
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
        Socket currentClient = clientSocket;
        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (IOException ignored) {}
        }
        if (serverThread != null) {
            serverThread.interrupt();
        }
    }

    @Override
    public void run() {
        Log.i(TAG, "NetworkServer listening on port " + PORT + "...");
        while (running) {
            try {
                serverSocket = new ServerSocket(PORT);
                serverSocket.setReuseAddress(true);

                while (running) {
                    Log.i(TAG, "Waiting for Host connection via ADB tunnel...");
                    if (listener != null) listener.onDisconnected();

                    Socket socket = serverSocket.accept();
                    clientSocket = socket;
                    socket.setTcpNoDelay(true);
                    // Keep only a small amount of stale video in flight. The old
                    // 4 MiB buffer could hide more than half a second at 60 Mbps.
                    socket.setReceiveBufferSize(256 * 1024);
                    socket.setSendBufferSize(64 * 1024);

                    Log.i(TAG, "Host connected from: " + socket.getRemoteSocketAddress());
                    if (listener != null) listener.onConnected(socket.getRemoteSocketAddress().toString());

                    handleClient(socket);
                    clientSocket = null;
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "ServerSocket error, restarting: " + e.getMessage());
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {}
                }
            } finally {
                if (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        serverSocket.close();
                    } catch (IOException ignored) {}
                }
            }
        }
    }

    public boolean sendPenEvent(
            int action,
            int tool,
            int buttons,
            int normalizedX,
            int normalizedY,
            int normalizedPressure,
            int tiltXHundredths,
            int tiltYHundredths,
            long timestampUs) {
        ClientWriter writer = clientWriter;
        if (writer == null) return false;

        PenPacket packet = new PenPacket();
        packet.sequence = penSequence.incrementAndGet();
        packet.action = action;
        packet.tool = tool;
        packet.buttons = buttons;
        packet.x = clampUnsignedShort(normalizedX);
        packet.y = clampUnsignedShort(normalizedY);
        packet.pressure = clampUnsignedShort(normalizedPressure);
        packet.tiltXHundredths = clampSignedShort(tiltXHundredths);
        packet.tiltYHundredths = clampSignedShort(tiltYHundredths);
        packet.timestampUs = timestampUs;
        return writer.enqueuePen(packet);
    }

    public boolean sendInputCommand(WheelSlot slot, int normalizedX, int normalizedY) {
        if (slot == null) return false;
        ClientWriter writer = clientWriter;
        if (writer == null) return false;
        InputCommand command = new InputCommand();
        command.sequence = commandSequence.incrementAndGet();
        command.action = slot.action;
        command.modifiers = slot.modifiers;
        command.keyCode = slot.keyCode;
        command.x = clampUnsignedShort(normalizedX);
        command.y = clampUnsignedShort(normalizedY);
        command.parameter = slot.parameter;
        command.timestampUs = android.os.SystemClock.uptimeMillis() * 1000L;
        return writer.enqueueCommand(command);
    }

    public boolean sendInputCommand(int action, int normalizedX, int normalizedY) {
        return sendInputCommand(new WheelSlot(action, 0, 0, 0, ""), normalizedX, normalizedY);
    }

    private static int clampUnsignedShort(int value) {
        return Math.max(0, Math.min(65535, value));
    }

    private static int clampSignedShort(int value) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    private void handleClient(Socket socket) {
        byte[] buffer = new byte[1024 * 1024 * 4]; // 4MB frame buffer
        long lastStatTime = System.currentTimeMillis();
        long totalBytesInSec = 0;
        long rxVideoFrames = 0;

        ClientWriter writer = null;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 64 * 1024));
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            writer = new ClientWriter(socket, out);
            clientWriter = writer;
            writer.start();

            while (running && !socket.isClosed()) {
                // Read 4-byte Magic
                int magic = in.readInt();
                if (magic != MAGIC) {
                    Log.e(TAG, "Invalid magic: " + Integer.toHexString(magic) + ", resyncing...");
                    break;
                }

                // Header fields
                int packetType = in.readUnsignedByte();
                int flags = in.readUnsignedByte();
                in.readShort(); // reserved
                long timestampUs = in.readLong();
                int payloadSize = in.readInt();

                if (payloadSize < 0 || payloadSize > buffer.length) {
                    Log.e(TAG, "Illegal payload size: " + payloadSize);
                    break;
                }

                // Read exact payload
                in.readFully(buffer, 0, payloadSize);
                totalBytesInSec += payloadSize + 20;

                if (packetType == 1) { // Video Frame
                    rxVideoFrames++;
                    boolean isKeyFrame = (flags & 0x01) != 0;
                    decoder.decode(buffer, 0, payloadSize, timestampUs, isKeyFrame);
                } else if (packetType == 2) { // Config / Resolution Change
                    if (payloadSize >= 12) {
                        int w = ((buffer[0] & 0xFF) << 24) | ((buffer[1] & 0xFF) << 16) | ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
                        int h = ((buffer[4] & 0xFF) << 24) | ((buffer[5] & 0xFF) << 16) | ((buffer[6] & 0xFF) << 8) | (buffer[7] & 0xFF);
                        int fps = ((buffer[8] & 0xFF) << 24) | ((buffer[9] & 0xFF) << 16) | ((buffer[10] & 0xFF) << 8) | (buffer[11] & 0xFF);
                        Log.i(TAG, "Config received: " + w + "x" + h + " @" + fps + "Hz");
                        decoder.reconfigure(w, h, fps);
                        if (listener != null) listener.onResolutionChanged(w, h, fps);
                        if (listener != null) listener.onTouchInputConfig(
                                TouchInputConfig.fromConfigPayload(buffer, payloadSize));
                    }
                } else if (packetType == 3) { // Ping / Pong for latency measurement
                    writer.enqueuePong(timestampUs);
                } else if (packetType == PACKET_TYPE_WHEEL_CONFIG) {
                    WheelConfig config = parseWheelConfig(buffer, payloadSize);
                    if (config != null) {
                        Log.i(TAG, "Wheel config received: revision=" + config.revision
                                + ", slots=" + config.slots.size());
                        if (listener != null) listener.onWheelConfig(config);
                    } else {
                        Log.w(TAG, "Rejected invalid wheel config payload, bytes=" + payloadSize);
                    }
                } else if (packetType == PACKET_TYPE_MACRO_CATALOG) {
                    MacroCatalog catalog = parseMacroCatalog(buffer, payloadSize);
                    if (catalog != null) {
                        Log.i(TAG, "Macro catalog received: revision=" + catalog.revision
                                + ", items=" + catalog.items.size());
                        if (listener != null) listener.onMacroCatalog(catalog);
                    } else {
                        Log.w(TAG, "Rejected invalid macro catalog payload, bytes=" + payloadSize);
                    }
                }

                // Periodic stats update
                long now = System.currentTimeMillis();
                if (now - lastStatTime >= 1000) {
                    double elapsedSec = (now - lastStatTime) / 1000.0;
                    int bitrateKbps = (int) (totalBytesInSec * 8 / 1024 / elapsedSec);
                    float rxFps = (float) (rxVideoFrames / elapsedSec);
                    Log.i(TAG, "[Stage 5 Android TCP] Recv: " + String.format(java.util.Locale.US, "%.1f", rxFps) + " FPS (" + String.format(java.util.Locale.US, "%.1f", bitrateKbps / 1000.0f) + " Mbps)");
                    if (listener != null) listener.onStatsUpdate(decoder.getFps(), bitrateKbps);
                    totalBytesInSec = 0;
                    rxVideoFrames = 0;
                    lastStatTime = now;
                }
            }
        } catch (IOException e) {
            Log.i(TAG, "Client disconnected: " + e.getMessage());
        } finally {
            clientWriter = null;
            if (writer != null) writer.stop();
            try {
                socket.close();
            } catch (IOException ignored) {}
            if (listener != null) listener.onDisconnected();
        }
    }

    private static WheelConfig parseWheelConfig(byte[] buffer, int payloadSize) {
        final int headerSize = 8;
        final int slotSize = 40;
        final int slotCount = 16;
        if (payloadSize != headerSize + slotSize * slotCount) return null;
        int schema = readUnsignedShort(buffer, 0);
        int innerCount = buffer[2] & 0xFF;
        int outerCount = buffer[3] & 0xFF;
        if (schema != 2 || innerCount != 8 || outerCount != 8) return null;
        long revision = readUnsignedInt(buffer, 4);
        ArrayList<WheelSlot> slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            int offset = headerSize + i * slotSize;
            int action = buffer[offset] & 0xFF;
            int modifiers = buffer[offset + 1] & 0xFF;
            int keyCode = readUnsignedShort(buffer, offset + 2);
            long parameter = readUnsignedInt(buffer, offset + 4);
            int labelLength = 0;
            while (labelLength < 32 && buffer[offset + 8 + labelLength] != 0) labelLength++;
            String label = new String(buffer, offset + 8, labelLength, StandardCharsets.UTF_8);
            slots.add(new WheelSlot(action, modifiers, keyCode, parameter, label));
        }
        return new WheelConfig(revision, slots);
    }

    private static MacroCatalog parseMacroCatalog(byte[] buffer, int payloadSize) {
        final int headerSize = 8;
        final int itemSize = 36;
        if (payloadSize < headerSize || readUnsignedShort(buffer, 0) != 1) return null;
        int itemCount = readUnsignedShort(buffer, 2);
        if (itemCount > 256 || payloadSize != headerSize + itemSize * itemCount) return null;
        long revision = readUnsignedInt(buffer, 4);
        ArrayList<WheelSlot> items = new ArrayList<>(itemCount);
        for (int index = 0; index < itemCount; index++) {
            int offset = headerSize + index * itemSize;
            long id = readUnsignedInt(buffer, offset);
            if (id == 0) return null;
            int labelLength = 0;
            while (labelLength < 32 && buffer[offset + 4 + labelLength] != 0) labelLength++;
            String label = new String(buffer, offset + 4, labelLength, StandardCharsets.UTF_8);
            items.add(new WheelSlot(INPUT_ACTION_EXECUTE_MACRO, 0, 0, id, label));
        }
        return new MacroCatalog(revision, items);
    }

    private static int readUnsignedShort(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 8) | (bytes[offset + 1] & 0xFF);
    }

    private static long readUnsignedInt(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF) << 24)
                | ((long) (bytes[offset + 1] & 0xFF) << 16)
                | ((long) (bytes[offset + 2] & 0xFF) << 8)
                | (long) (bytes[offset + 3] & 0xFF);
    }

    private static final class PenPacket {
        int sequence;
        int action;
        int tool;
        int buttons;
        int x;
        int y;
        int pressure;
        int tiltXHundredths;
        int tiltYHundredths;
        long timestampUs;

        boolean isCoalescible() {
            return action == PEN_ACTION_MOVE || action == PEN_ACTION_HOVER_MOVE;
        }
    }

    private static final class InputCommand {
        int sequence;
        int action;
        int modifiers;
        int keyCode;
        int x;
        int y;
        long parameter;
        long timestampUs;
    }

    private static final class OutboundPacket {
        final int type;
        final long timestampUs;
        final PenPacket pen;
        final InputCommand command;

        OutboundPacket(int type, long timestampUs, PenPacket pen, InputCommand command) {
            this.type = type;
            this.timestampUs = timestampUs;
            this.pen = pen;
            this.command = command;
        }

        boolean isCoalesciblePen() {
            return pen != null && pen.isCoalescible();
        }
    }

    private static final class ClientWriter implements Runnable {
        private static final int MAX_QUEUE_DEPTH = 64;

        private final Socket socket;
        private final DataOutputStream out;
        private final ArrayDeque<OutboundPacket> queue = new ArrayDeque<>();
        private volatile boolean active = true;
        private Thread thread;

        ClientWriter(Socket socket, DataOutputStream out) {
            this.socket = socket;
            this.out = out;
        }

        void start() {
            thread = new Thread(this, "ReDisplay-InputWriter");
            thread.setPriority(Thread.MAX_PRIORITY);
            thread.start();
        }

        boolean enqueuePen(PenPacket pen) {
            synchronized (queue) {
                if (!active) return false;

                OutboundPacket tail = queue.peekLast();
                if (pen.isCoalescible() && tail != null && tail.pen != null
                        && tail.pen.action == pen.action) {
                    queue.removeLast();
                    queue.addLast(new OutboundPacket(PACKET_TYPE_PEN, pen.timestampUs, pen, null));
                    queue.notifyAll();
                    return true;
                }

                if (queue.size() >= MAX_QUEUE_DEPTH) {
                    boolean removedMove = false;
                    Iterator<OutboundPacket> iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().isCoalesciblePen()) {
                            iterator.remove();
                            removedMove = true;
                            break;
                        }
                    }
                    if (!removedMove && pen.isCoalescible()) return false;
                }

                queue.addLast(new OutboundPacket(PACKET_TYPE_PEN, pen.timestampUs, pen, null));
                queue.notifyAll();
                return true;
            }
        }

        boolean enqueueCommand(InputCommand command) {
            synchronized (queue) {
                if (!active) return false;
                if (queue.size() >= MAX_QUEUE_DEPTH) {
                    Iterator<OutboundPacket> iterator = queue.iterator();
                    while (iterator.hasNext()) {
                        if (iterator.next().isCoalesciblePen()) {
                            iterator.remove();
                            break;
                        }
                    }
                }
                queue.addLast(new OutboundPacket(
                        PACKET_TYPE_INPUT_COMMAND,
                        command.timestampUs,
                        null,
                        command
                ));
                queue.notifyAll();
                return true;
            }
        }

        void enqueuePong(long timestampUs) {
            synchronized (queue) {
                if (!active) return;
                queue.addLast(new OutboundPacket(PACKET_TYPE_PONG, timestampUs, null, null));
                queue.notifyAll();
            }
        }

        void stop() {
            active = false;
            synchronized (queue) {
                queue.notifyAll();
            }
            if (thread != null) {
                thread.interrupt();
                try {
                    thread.join(500);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        @Override
        public void run() {
            try {
                while (active && !socket.isClosed()) {
                    OutboundPacket packet;
                    synchronized (queue) {
                        while (active && queue.isEmpty()) {
                            queue.wait();
                        }
                        if (!active) break;
                        packet = queue.removeFirst();
                    }
                    writePacket(packet);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                Log.i(TAG, "Input writer disconnected: " + e.getMessage());
                try {
                    socket.close();
                } catch (IOException ignored) {}
            } finally {
                active = false;
            }
        }

        private void writePacket(OutboundPacket packet) throws IOException {
            int payloadSize = packet.pen != null
                    ? PEN_PAYLOAD_SIZE
                    : (packet.command != null ? INPUT_COMMAND_PAYLOAD_SIZE : 0);
            out.writeInt(MAGIC);
            out.writeByte(packet.type);
            out.writeByte(0);
            out.writeShort(0);
            out.writeLong(packet.timestampUs);
            out.writeInt(payloadSize);

            PenPacket pen = packet.pen;
            if (pen != null) {
                out.writeInt(pen.sequence);
                out.writeByte(pen.action);
                out.writeByte(pen.tool);
                out.writeShort(pen.buttons);
                out.writeShort(pen.x);
                out.writeShort(pen.y);
                out.writeShort(pen.pressure);
                out.writeShort(pen.tiltXHundredths);
                out.writeShort(pen.tiltYHundredths);
                out.writeShort(0);
            }
            InputCommand command = packet.command;
            if (command != null) {
                out.writeInt(command.sequence);
                out.writeByte(command.action);
                out.writeByte(command.modifiers);
                out.writeShort(command.keyCode);
                out.writeShort(command.x);
                out.writeShort(command.y);
                out.writeInt((int) command.parameter);
                out.writeInt(0);
            }
            out.flush();
        }
    }
}

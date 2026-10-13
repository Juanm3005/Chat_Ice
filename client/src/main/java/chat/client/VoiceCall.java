package chat.client;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

public class VoiceCall {

    private static final AudioFormat FORMAT = new AudioFormat(16000f, 16, 1, true, false);

    private static final int PACKET_BYTES = 640;

    private final DatagramSocket socket;
    private final InetAddress remoteAddress;
    private final int remotePort;

    private volatile boolean running = false;
    private TargetDataLine microphone;
    private SourceDataLine speakers;

    public VoiceCall(DatagramSocket socket, String remoteIp, int remotePort) throws UnknownHostException {
        this.socket = socket;
        this.remoteAddress = InetAddress.getByName(remoteIp);
        this.remotePort = remotePort;
    }

    public void start() throws LineUnavailableException {
        try {
            microphone = AudioSystem.getTargetDataLine(FORMAT);
            microphone.open(FORMAT);
            microphone.start();

            speakers = AudioSystem.getSourceDataLine(FORMAT);
            speakers.open(FORMAT);
            speakers.start();
        } catch (LineUnavailableException | IllegalArgumentException e) {
            closeLines();
            throw e instanceof LineUnavailableException
                    ? (LineUnavailableException) e
                    : new LineUnavailableException("No hay microfono/parlantes compatibles: " + e.getMessage());
        }

        running = true;

        Thread sender = new Thread(this::sendLoop, "udp-audio-emisor");
        sender.setDaemon(true);
        sender.start();

        Thread receiver = new Thread(this::receiveLoop, "udp-audio-receptor");
        receiver.setDaemon(true);
        receiver.start();
    }

    private void sendLoop() {
        byte[] buffer = new byte[PACKET_BYTES];
        try {
            while (running) {
                int read = microphone.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    if (!microphone.isOpen()) {
                        break;
                    }
                    continue;
                }
                socket.send(new DatagramPacket(buffer, read, remoteAddress, remotePort));
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("\n[AUDIO] Error enviando audio: " + e.getMessage());
            }
        }
    }

    private void receiveLoop() {
        byte[] buffer = new byte[2048];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        try {
            while (running) {
                packet.setLength(buffer.length);
                socket.receive(packet);
                int length = packet.getLength() - (packet.getLength() % 2);
                if (length > 0) {
                    speakers.write(buffer, 0, length);
                }
            }
        } catch (SocketException e) {

            if (running) {
                System.err.println("\n[AUDIO] Socket UDP cerrado: " + e.getMessage());
            }
        } catch (IOException e) {
            if (running) {
                System.err.println("\n[AUDIO] Error recibiendo audio: " + e.getMessage());
            }
        }
    }

    public void stop() {
        running = false;
        socket.close();
        closeLines();
    }

    private void closeLines() {
        if (microphone != null) {
            microphone.stop();
            microphone.close();
        }
        if (speakers != null) {
            speakers.stop();
            speakers.close();
        }
    }
}

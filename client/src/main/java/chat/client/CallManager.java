package chat.client;

import ChatApp.CallException;
import ChatApp.CallServicePrx;

import java.net.DatagramSocket;
import java.util.function.Supplier;

public class CallManager {

    private enum State {IDLE, CALLING, RINGING, IN_CALL}

    private final String myNick;
    private final String myIp;
    private final CallServicePrx service;
    private final Supplier<String> prompt;

    private State state = State.IDLE;
    private long callId = 0;
    private String peer;

    private String incomingIp;
    private int incomingPort;

    private DatagramSocket socket;
    private VoiceCall voice;

    public CallManager(String myNick, String myIp, CallServicePrx service, Supplier<String> prompt) {
        this.myNick = myNick;
        this.myIp = myIp;
        this.service = service;
        this.prompt = prompt;
    }

    public synchronized void call(String target) {
        if (state != State.IDLE) {
            System.out.println(">>> Ya tienes una llamada en curso. Usa /hangup primero.");
            return;
        }
        DatagramSocket udp = null;
        try {

            udp = new DatagramSocket(0);
            long id = service.startCall(myNick, target, myIp, udp.getLocalPort());
            socket = udp;
            callId = id;
            peer = target;
            state = State.CALLING;
            System.out.println(">>> Llamando a " + target + "... esperando que conteste.");
        } catch (CallException e) {
            closeQuietly(udp);
            System.out.println("[LLAMADA] " + e.reason);
        } catch (Exception e) {
            closeQuietly(udp);
            System.out.println("[LLAMADA] No se pudo iniciar la llamada: " + e.getMessage());
        }
    }

    public synchronized void accept() {
        if (state != State.RINGING) {
            System.out.println(">>> No tienes ninguna llamada entrante.");
            return;
        }
        DatagramSocket udp = null;
        VoiceCall newVoice = null;
        try {
            udp = new DatagramSocket(0);

            newVoice = new VoiceCall(udp, incomingIp, incomingPort);
            newVoice.start();
            service.acceptCall(callId, myNick, myIp, udp.getLocalPort());
            socket = udp;
            voice = newVoice;
            state = State.IN_CALL;
            System.out.println(">>> Llamada con " + peer + " establecida. Usa /hangup para colgar.");
        } catch (CallException e) {
            stopQuietly(newVoice, udp);
            System.out.println("[LLAMADA] " + e.reason);
            resetState();
        } catch (Exception e) {
            stopQuietly(newVoice, udp);
            System.out.println("[LLAMADA] No se pudo contestar (revisa microfono/parlantes): " + e.getMessage());
            try {
                service.rejectCall(callId, myNick);
            } catch (Exception ignored) {
            }
            resetState();
        }
    }

    public synchronized void reject() {
        if (state != State.RINGING) {
            System.out.println(">>> No tienes ninguna llamada entrante.");
            return;
        }
        try {
            service.rejectCall(callId, myNick);
            System.out.println(">>> Llamada rechazada.");
        } catch (CallException e) {
            System.out.println("[LLAMADA] " + e.reason);
        } catch (Exception e) {
            System.out.println("[LLAMADA] Error de red: " + e.getMessage());
        }
        resetState();
    }

    public synchronized void hangup() {
        if (state == State.IDLE) {
            System.out.println(">>> No hay ninguna llamada activa.");
            return;
        }
        try {
            service.hangUp(callId, myNick);
        } catch (Exception ignored) {

        }
        System.out.println(">>> Llamada finalizada.");
        resetState();
    }

    public synchronized void shutdown() {
        if (state == State.IDLE) {
            return;
        }
        try {
            if (state == State.RINGING) {
                service.rejectCall(callId, myNick);
            } else {
                service.hangUp(callId, myNick);
            }
        } catch (Exception ignored) {
        }
        resetState();
    }

    public synchronized void onIncomingCall(long id, String caller, String ip, int port) {
        if (state != State.IDLE) {
            return;
        }
        state = State.RINGING;
        callId = id;
        peer = caller;
        incomingIp = ip;
        incomingPort = port;
        System.out.println("\n>>> LLAMADA ENTRANTE de " + caller + ". Escribe /accept o /reject");
        System.out.print(prompt.get());
    }

    public synchronized void onCallAccepted(long id, String callee, String ip, int port) {
        if (state != State.CALLING || id != callId) {
            return;
        }
        try {

            voice = new VoiceCall(socket, ip, port);
            voice.start();
            state = State.IN_CALL;
            System.out.println("\n>>> " + callee + " contesto. Llamada establecida. Usa /hangup para colgar.");
        } catch (Exception e) {
            System.out.println("\n[LLAMADA] No se pudo iniciar el audio (revisa microfono/parlantes): "
                    + e.getMessage());
            try {
                service.hangUp(callId, myNick);
            } catch (Exception ignored) {
            }
            resetState();
        }
        System.out.print(prompt.get());
    }

    public synchronized void onCallRejected(long id, String callee) {
        if (state != State.CALLING || id != callId) {
            return;
        }
        System.out.println("\n>>> " + callee + " rechazo la llamada.");
        System.out.print(prompt.get());
        resetState();
    }

    public synchronized void onCallEnded(long id, String byUser) {
        if (state == State.IDLE || id != callId) {
            return;
        }
        System.out.println("\n>>> La llamada termino (" + byUser + " colgo).");
        System.out.print(prompt.get());
        resetState();
    }

    private void resetState() {
        if (voice != null) {
            voice.stop();
        }
        closeQuietly(socket);
        voice = null;
        socket = null;
        state = State.IDLE;
        callId = 0;
        peer = null;
        incomingIp = null;
        incomingPort = 0;
    }

    private void stopQuietly(VoiceCall v, DatagramSocket s) {
        if (v != null) {
            v.stop();
        }
        closeQuietly(s);
    }

    private void closeQuietly(DatagramSocket s) {
        if (s != null && !s.isClosed()) {
            s.close();
        }
    }
}

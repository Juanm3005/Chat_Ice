package chat.server;

import java.util.concurrent.atomic.AtomicLong;

public class Call {
    public enum State {RINGING, ACTIVE, ENDED}

    private static final AtomicLong ID_GEN = new AtomicLong();

    private final long id = ID_GEN.incrementAndGet();
    private final String roomName;
    private final String caller;
    private final String callee;
    private final String callerIp;
    private final int callerPort;
    private volatile String calleeIp;
    private volatile int calleePort;
    private volatile State state;

    public Call(String caller, String callee, String callerIp, int callerPort) {
        this.roomName = null;
        this.caller = caller;
        this.callee = callee;
        this.callerIp = callerIp;
        this.callerPort = callerPort;
        this.state = State.RINGING;
    }

    public Call(String roomName) {
        this.roomName = roomName;
        this.caller = null;
        this.callee = null;
        this.callerIp = null;
        this.callerPort = 0;
        this.state = State.ACTIVE;
    }

    public void accept(String ip, int port) {
        this.calleeIp = ip;
        this.calleePort = port;
        this.state = State.ACTIVE;
    }

    public void end() {
        this.state = State.ENDED;
    }

    public long getId() { return id; }
    public String getRoomName() { return roomName; }
    public String getCaller() { return caller; }
    public String getCallee() { return callee; }
    public String getCallerIp() { return callerIp; }
    public int getCallerPort() { return callerPort; }
    public String getCalleeIp() { return calleeIp; }
    public int getCalleePort() { return calleePort; }
    public State getState() { return state; }

    public String otherParty(String user) {
        return user.equals(caller) ? callee : caller;
    }

    public boolean isParticipant(String user) {
        return user != null && (user.equals(caller) || user.equals(callee));
    }
}

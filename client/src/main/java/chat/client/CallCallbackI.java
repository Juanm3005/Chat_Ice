package chat.client;

import ChatApp.CallCallback;
import com.zeroc.Ice.Current;

public class CallCallbackI implements CallCallback {

    private final CallManager manager;

    public CallCallbackI(CallManager manager) {
        this.manager = manager;
    }

    @Override
    public void incomingCall(long callId, String caller, String ip, int port, Current current) {
        manager.onIncomingCall(callId, caller, ip, port);
    }

    @Override
    public void callAccepted(long callId, String callee, String ip, int port, Current current) {
        manager.onCallAccepted(callId, callee, ip, port);
    }

    @Override
    public void callRejected(long callId, String callee, Current current) {
        manager.onCallRejected(callId, callee);
    }

    @Override
    public void callEnded(long callId, String byUser, Current current) {
        manager.onCallEnded(callId, byUser);
    }
}

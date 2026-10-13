package chat.server;

import ChatApp.CallCallbackPrx;
import ChatApp.CallException;
import ChatApp.CallService;
import com.zeroc.Ice.Current;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

public class CallServiceI implements CallService {

    private final ChatRoomI chat;

    private final Map<String, CallCallbackPrx> callbacks = new ConcurrentHashMap<>();

    private final Map<Long, Call> calls = new ConcurrentHashMap<>();

    private final Map<String, Long> busyUsers = new ConcurrentHashMap<>();

    public CallServiceI(ChatRoomI chat) {
        this.chat = chat;
    }

    @Override
    public synchronized void registerCallback(String nickname, CallCallbackPrx cb, Current current)
            throws CallException {
        if (cb == null) {
            throw new CallException("El callback no puede ser nulo.");
        }

        if (!Arrays.asList(chat.getOnlineUsers(null)).contains(nickname)) {
            throw new CallException("Primero debes iniciar sesion en el chat.");
        }
        callbacks.put(nickname, current != null ? cb.ice_fixed(current.con) : cb);
        System.out.println("[CALL] Callback registrado: " + nickname);
    }

    @Override
    public synchronized void unregisterCallback(String nickname, Current current) {
        removeUser(nickname);
    }

    @Override
    public synchronized long startCall(String caller, String callee, String ip, int port, Current current)
            throws CallException {
        if (!callbacks.containsKey(caller)) {
            throw new CallException("Debes registrar tu callback antes de llamar.");
        }
        if (caller.equals(callee)) {
            throw new CallException("No puedes llamarte a ti mismo.");
        }
        if (!callbacks.containsKey(callee)) {
            throw new CallException("El usuario '" + callee + "' no esta conectado.");
        }
        if (busyUsers.containsKey(caller)) {
            throw new CallException("Ya estas en una llamada.");
        }
        if (busyUsers.containsKey(callee)) {
            throw new CallException("El usuario '" + callee + "' esta ocupado en otra llamada.");
        }

        Call call = new Call(caller, callee, ip, port);
        long id = call.getId();
        calls.put(id, call);
        busyUsers.put(caller, id);
        busyUsers.put(callee, id);

        System.out.println("[CALL] #" + id + " " + caller + " -> " + callee + " (timbrando)");

        notifyUser(callee, prx -> prx.incomingCallAsync(id, caller, ip, port));
        return id;
    }

    @Override
    public synchronized void acceptCall(long callId, String callee, String ip, int port, Current current)
            throws CallException {
        Call call = findCall(callId);
        if (!callee.equals(call.getCallee())) {
            throw new CallException("Esta llamada no es para ti.");
        }
        if (call.getState() != Call.State.RINGING) {
            throw new CallException("La llamada ya no esta en espera.");
        }
        call.accept(ip, port);
        System.out.println("[CALL] #" + callId + " aceptada por " + callee);

        notifyUser(call.getCaller(), prx -> prx.callAcceptedAsync(callId, callee, ip, port));
    }

    @Override
    public synchronized void rejectCall(long callId, String callee, Current current) throws CallException {
        Call call = findCall(callId);
        if (!callee.equals(call.getCallee())) {
            throw new CallException("Esta llamada no es para ti.");
        }
        if (call.getState() != Call.State.RINGING) {
            throw new CallException("La llamada ya no esta en espera.");
        }
        finish(call);
        System.out.println("[CALL] #" + callId + " rechazada por " + callee);
        notifyUser(call.getCaller(), prx -> prx.callRejectedAsync(callId, callee));
    }

    @Override
    public synchronized void hangUp(long callId, String user, Current current) throws CallException {
        Call call = findCall(callId);
        if (!call.isParticipant(user)) {
            throw new CallException("No participas en esta llamada.");
        }
        finish(call);
        System.out.println("[CALL] #" + callId + " finalizada por " + user);
        notifyUser(call.otherParty(user), prx -> prx.callEndedAsync(callId, user));
    }

    private Call findCall(long callId) throws CallException {
        Call call = calls.get(callId);
        if (call == null) {
            throw new CallException("La llamada no existe o ya termino.");
        }
        return call;
    }

    private void finish(Call call) {
        call.end();
        calls.remove(call.getId());
        busyUsers.remove(call.getCaller());
        busyUsers.remove(call.getCallee());
    }

    private void removeUser(String user) {
        callbacks.remove(user);
        Long callId = busyUsers.get(user);
        if (callId != null) {
            Call call = calls.get(callId);
            if (call != null) {
                finish(call);
                String other = call.otherParty(user);
                notifyUser(other, prx -> prx.callEndedAsync(callId, user));
            }
        }
        System.out.println("[CALL] Callback removido: " + user);
    }

    private void notifyUser(String user, Function<CallCallbackPrx, CompletionStage<Void>> action) {
        CallCallbackPrx prx = callbacks.get(user);
        if (prx == null) {
            return;
        }
        action.apply(prx).whenComplete((result, error) -> {
            if (error != null) {
                System.out.println("[CALL] No se pudo avisar a " + user + ": " + error);
                synchronized (CallServiceI.this) {
                    removeUser(user);
                }
            }
        });
    }
}

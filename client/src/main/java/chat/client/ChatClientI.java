package chat.client;

import ChatApp.ChatMessage;
import com.zeroc.Ice.Current;

import java.util.function.Consumer;

// el servidor invoca estos metodos de forma remota sobre el cliente.
// Cada evento se delega, asi la logica de consola queda en ClientMain.
public class ChatClientI implements ChatApp.ChatClient {
    private final Consumer<ChatMessage> onPrivateMessage;

    public ChatClientI(Consumer<ChatMessage> onPrivateMessage) {
        this.onPrivateMessage = onPrivateMessage;
    }

    @Override
    public void receivePrivateMessage(ChatMessage msg, Current current) {
        onPrivateMessage.accept(msg);
    }
}

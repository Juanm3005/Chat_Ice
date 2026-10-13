package chat.client;

import ChatApp.ChatMessage;
import com.zeroc.Ice.Current;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

// el servidor invoca estos metodos de forma remota sobre el cliente.
// Cada evento se delega, asi la logica de consola queda en ClientMain.
public class ChatClientI implements ChatApp.ChatClient {
    private final Consumer<ChatMessage> onPrivateMessage;
    private final BiConsumer<String, ChatMessage> onRoomMessage;

    public ChatClientI(Consumer<ChatMessage> onPrivateMessage, BiConsumer<String, ChatMessage> onRoomMessage) {
        this.onPrivateMessage = onPrivateMessage;
        this.onRoomMessage = onRoomMessage;
    }

    @Override
    public void receivePrivateMessage(ChatMessage msg, Current current) {
        onPrivateMessage.accept(msg);
    }

    @Override
    public void receiveRoomMessage(String room, ChatMessage msg, Current current) {
        onRoomMessage.accept(room, msg);
    }
}

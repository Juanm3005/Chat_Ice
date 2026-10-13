package chat.server;

import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.Util;

public class ServerMain {
    public static void main(String[] args) {
        int exitCode = 0;
        // Inicialización del Communicator dentro de try-with-resources
        try (Communicator communicator = Util.initialize(args)) {
            // 1. Crear ObjectAdapter vinculado al puerto TCP 10000
            ObjectAdapter adapter = communicator.createObjectAdapterWithEndpoints(
                    "ChatAdapter", "default -p 10000");

            // Registrar ambos servicios en el mismo endpoint Ice.
            ChatRoomI chatService = new ChatRoomI();
            adapter.add(chatService, Util.stringToIdentity("ChatService"));
            adapter.add(new CallServiceI(chatService), Util.stringToIdentity("CallService"));

            // 3. Activar el adaptador para recibir llamadas RPC
            adapter.activate();
            System.out.println("===============================================");
            System.out.println("* SERVIDOR ZEROC ICE INICIADO EXITOSAMENTE *");
            System.out.println("* Puerto TCP: 10000 | Endpoint: default -p 10000 *");
            System.out.println("* Servicios: ChatService y CallService *");
            System.out.println("===============================================");

            // 4. Bloquear el hilo principal hasta orden de apagado (Ctrl+C)
            communicator.waitForShutdown();
        } catch (Exception e) {
            System.err.println("[ERROR SERVIDOR] Fallo crítico: " + e.getMessage());
            e.printStackTrace();
            exitCode = 1;
        }
        System.exit(exitCode);
    }
}

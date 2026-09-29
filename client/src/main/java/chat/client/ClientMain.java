package chat.client;

import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoomPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import java.util.Scanner;

public class ClientMain {
    private static volatile boolean running = true;
    private static long lastReceivedId = 0;

    public static void main(String[] args) {
        // Inicializar el Communicator del cliente
        try (Communicator communicator = Util.initialize(args)) {
            // 1. Obtener proxy base y realizar casting seguro hacia ChatRoomPrx
            String proxyString = "ChatService:default -h 127.0.0.1 -p 10000";
            ObjectPrx base = communicator.stringToProxy(proxyString);
            ChatRoomPrx chatPrx = ChatRoomPrx.checkedCast(base);

            if (chatPrx == null) {
                System.err.println("[ERROR] No se pudo establecer conexion con el servicio Ice remoto.");
                return;
            }

            Scanner scanner = new Scanner(System.in);
            String nickname = "";

            // 2. Flujo de inicio de sesion con manejo de nombres duplicados
            System.out.println("=== BIENVENIDO AL CHAT DISTRIBUIDO ZEROC ICE ===");
            while (true) {
                System.out.print("Ingrese su nickname: ");
                nickname = scanner.nextLine().trim();
                if (nickname.isEmpty()) continue;
                try {
                    chatPrx.login(nickname);
                    System.out.println(">>> Autenticado correctamente como '" + nickname + "'.");
                    System.out.println(">>> Comandos disponibles: /users (lista usuarios), /exit (salir)");
                    System.out.println("-------------------------------------------------");
                    break;
                } catch (ChatException ce) {
                    System.out.println("[RECHAZADO POR SERVIDOR] " + ce.reason + " Intente nuevamente.");
                }
            }

            final String activeUser = nickname;

            // 3. Hilo demonio en segundo plano: Sondeo continuo de mensajes (Polling no bloqueante)
            Thread listenerThread = new Thread(() -> {
                while (running) {
                    try {
                        ChatMessage[] newMessages = chatPrx.getPendingMessages(activeUser, lastReceivedId);
                        for (ChatMessage msg : newMessages) {
                            if (msg.id > lastReceivedId) {
                                lastReceivedId = msg.id;
                            }
                            // Mostrar mensaje solo si no fue enviado por el propio usuario
                            if (!msg.sender.equals(activeUser)) {
                                System.out.println("\n[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
                                System.out.print("> ");
                            }
                        }
                        Thread.sleep(500); // Consulta cada 500 milisegundos
                    } catch (Exception e) {
                        if (running) {
                            System.err.println("\n[AVISO] Se perdio la comunicacion con el servidor Ice.");
                            running = false;
                        }
                        break;
                    }
                }
            });
            listenerThread.setDaemon(true);
            listenerThread.start();

            // 4. Bucle principal de lectura por consola (REPL)
            while (running) {
                System.out.print("> ");
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("/exit")) {
                    running = false;
                    try {
                        chatPrx.logout(activeUser);
                    } catch (Exception ignored) {}
                    System.out.println(">>> Sesion cerrada satisfactoriamente. Hasta pronto!");
                    break;
                } else if (input.equalsIgnoreCase("/users")) {
                    try {
                        String[] users = chatPrx.getOnlineUsers();
                        System.out.println(">>> Usuarios activos (" + users.length + "): " + String.join(", ",
                                users));
                    } catch (Exception e) {
                        System.err.println("[ERROR] No se pudo obtener la lista de usuarios: " + e.getMessage());
                    }
                } else if (!input.isEmpty()) {
                    try {
                        chatPrx.postMessage(activeUser, input);
                    } catch (ChatException ce) {
                        System.err.println("[ERROR ENVIO] " + ce.reason);
                    } catch (Exception e) {
                        System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
                        break;
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR FATAL] " + e.getMessage());
        }
    }
}

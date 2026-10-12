package chat.client;

import ChatApp.ChatClientPrx;
import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoomPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Scanner;

public class ClientMain {
    private static volatile boolean running = true;
    private static long lastReceivedId = 0;
    // Conversacion activa: chat general o ventana privada abierta con /msg
    private static final ConversationContext conversation = new ConversationContext();

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
                if (nickname.isEmpty())
                    continue;
                try {
                    chatPrx.login(nickname);
                    System.out.println(">>> Autenticado correctamente como '" + nickname + "'.");
                    System.out.println(">>> Escribe /help para ver todos los comandos disponibles.");
                    System.out.println("-------------------------------------------------");
                    break;
                } catch (ChatException ce) {
                    System.out.println("[RECHAZADO POR SERVIDOR] " + ce.reason + " Intente nuevamente.");
                }
            }

            final String activeUser = nickname;

            // Registro del callback: el servidor usara este proxy para notificar eventos
            registerCallback(communicator, chatPrx, activeUser);

            // 3. Hilo demonio en segundo plano: Sondeo continuo de mensajes (Polling no
            // bloqueante)
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
                                // Dentro de una ventana privada se marca el origen del mensaje
                                String origin = conversation.getMode() == ConversationContext.Mode.GENERAL
                                        ? "" : "[General] ";
                                printAsync(origin + "[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
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
                System.out.print(conversation.prompt());
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("/exit")) {
                    running = false;
                    try {
                        chatPrx.logout(activeUser);
                    } catch (Exception ignored) {
                    }
                    System.out.println(">>> Sesion cerrada satisfactoriamente. Hasta pronto!");
                    break;
                } else if (input.equals("/msg") || input.startsWith("/msg ")) {
                    openPrivateChat(chatPrx, activeUser, input.substring(4).trim());
                } else if (input.equalsIgnoreCase("/closew")) {
                    if (conversation.getMode() == ConversationContext.Mode.PRIVATE) {
                        System.out.println(">>> Conversacion privada con '" + conversation.getTarget()
                                + "' cerrada. De vuelta al chat general.");
                        conversation.backToGeneral();
                    } else {
                        System.out.println(">>> No tienes ninguna conversacion privada abierta.");
                    }
                } else if (input.equalsIgnoreCase("/help")) {
                    printHelp();
                } else if (input.equalsIgnoreCase("/users")) {
                    try {
                        String[] users = chatPrx.getOnlineUsers();
                        System.out.println(">>> Usuarios activos (" + users.length + "): " + String.join(", ",
                                users));
                    } catch (Exception e) {
                        System.err.println("[ERROR] No se pudo obtener la lista de usuarios: " + e.getMessage());
                    }
                } else if (input.startsWith("/upload")) {
                    String filePath = input.substring(8).trim();
                    if (!filePath.isEmpty()) {
                        uploadFile(chatPrx, activeUser, filePath);
                    } else {
                        System.out.println(" || Debes usar la sigueinte estructura: /upload<ruta_del_archivo>");
                    }
                } else if (input.startsWith("/download")) {
                    String fileName = input.substring(10).trim();
                    if (!fileName.isEmpty()) {
                        downloadFile(chatPrx, activeUser, fileName);
                    } else {
                        System.out.println(" || Debes usar la sigueinte estructura: /download<ruta_del_archivo>");
                    }
                } else if (!input.isEmpty() && conversation.getMode() == ConversationContext.Mode.PRIVATE) {
                    sendPrivate(chatPrx, activeUser, conversation.getTarget(), input);
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

    // Lista de comandos de la CLI. Al agregar un comando nuevo, documentarlo tambien aqui.
    private static void printHelp() {
        System.out.println("===================== COMANDOS =====================");
        System.out.println("  /help                    Muestra esta lista de comandos");
        System.out.println("  /users                   Lista los usuarios conectados");
        System.out.println("  /msg <usuario> [texto]   Abre un chat privado con <usuario> (y envia [texto] si se indica)");
        System.out.println("  /closew                  Cierra el chat privado y vuelve al chat general");
        System.out.println("  /upload <ruta>           Sube un archivo al servidor y lo comparte con la sala");
        System.out.println("  /download <archivo>      Descarga un archivo compartido a la carpeta 'downloads'");
        System.out.println("  /exit                    Cierra la sesion y sale del chat");
        System.out.println("  <texto>                  Envia el texto a la conversacion actual (general o privada)");
        System.out.println("====================================================");
    }

    // Crea un adaptador sin endpoints y lo asocia a la conexion ya abierta con el servidor
    // (conexion bidireccional). Asi el servidor puede invocar al cliente sin que este abra
    // puertos, lo que funciona igual en localhost y entre maquinas de la red.
    private static void registerCallback(Communicator communicator, ChatRoomPrx chatPrx, String nickname)
            throws ChatException {
        ObjectAdapter adapter = communicator.createObjectAdapter("");
        ChatClientI servant = new ChatClientI(ClientMain::onPrivateMessage);
        ChatClientPrx callbackPrx = ChatClientPrx.uncheckedCast(adapter.addWithUUID(servant));
        adapter.activate();
        chatPrx.ice_getConnection().setAdapter(adapter);
        chatPrx.registerCallback(nickname, callbackPrx);
    }

    // Se ejecuta en el hilo de Ice cuando el servidor entrega un mensaje privado
    private static void onPrivateMessage(ChatMessage msg) {
        if (conversation.isPrivateWith(msg.sender)) {
            printAsync("[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
        } else {
            printAsync("[Privado de " + msg.sender + "] [" + msg.timestamp + "]: " + msg.text
                    + "  (responde con /msg " + msg.sender + ")");
        }
    }

    // /msg <usuario> [texto]: abre la ventana privada y opcionalmente envia el primer mensaje
    private static void openPrivateChat(ChatRoomPrx chatPrx, String activeUser, String args) {
        if (args.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /msg <usuario> [texto]");
            return;
        }
        String[] parts = args.split("\\s+", 2);
        String target = parts[0];
        if (target.equals(activeUser)) {
            System.out.println("[ERROR] No puedes abrir una conversacion privada contigo mismo.");
            return;
        }
        try {
            if (!Arrays.asList(chatPrx.getOnlineUsers()).contains(target)) {
                System.out.println("[ERROR] El usuario '" + target + "' no existe o no esta conectado.");
                return;
            }
        } catch (Exception e) {
            System.err.println("[ERROR RED] No se pudo verificar el usuario: " + e.getMessage());
            return;
        }
        conversation.openPrivate(target);
        System.out.println("============ Chat privado con " + target + " ============");
        System.out.println(">>> Escribe tus mensajes. Usa /closew para volver al chat general.");
        if (parts.length > 1) {
            sendPrivate(chatPrx, activeUser, target, parts[1]);
        }
    }

    private static void sendPrivate(ChatRoomPrx chatPrx, String activeUser, String target, String text) {
        try {
            chatPrx.sendPrivateMessage(activeUser, target, text);
            System.out.println("    (entregado al servidor)");
        } catch (ChatException ce) {
            System.err.println("[ERROR PRIVADO] " + ce.reason + " Usa /closew para volver al chat general.");
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
        }
    }

    // Imprime un mensaje que llega de forma asincrona y vuelve a mostrar el prompt actual
    private static synchronized void printAsync(String line) {
        System.out.println("\n" + line);
        System.out.print(conversation.prompt());
    }

    private static void uploadFile(ChatRoomPrx chatPrx, String nickname, String pathStr) {
        File file = new File(pathStr);
        if (!file.exists() || !file.isFile()) {
            System.err.println("[ERROR] El archivo no existe o no es valido: " + pathStr);
            return;
        }

        System.out.println(">>> Subiendo " + file.getName() + " (" + file.length() + " bytes)...");

        try (FileInputStream fis = new FileInputStream(file)) {
            String sessionId = chatPrx.startUpload(file.getName(), nickname);

            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] chunk = (bytesRead == buffer.length) ? buffer : Arrays.copyOf(buffer, bytesRead);
                chatPrx.writeChunk(sessionId, chunk);
            }

            chatPrx.closeUpload(sessionId);
            System.out.println(">>> Archivo '" + file.getName() + "' subido con exito.");

        } catch (

        ChatException ce) {
            System.err.println("[RECHAZADO] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR SUBIDA] " + e.getMessage());
        }
    }

    public static void downloadFile(ChatRoomPrx chatPrx, String nickname, String fileName) {
        try {

            String sessionId = chatPrx.startDownload(fileName, nickname);
            File downloadDir = new File("downloads");
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }

            File localFile = new File(downloadDir, fileName);
            System.out.println(">>> Descargando '" + fileName + "'...");

            try (FileOutputStream fos = new FileOutputStream(localFile)) {
                long offset = 0;
                int chunkSize = 32 * 1024;

                while (true) {
                    byte[] chunk = chatPrx.readChunk(sessionId, offset, chunkSize);
                    if (chunk == null || chunk.length == 0) {
                        break;
                    }
                    fos.write(chunk);
                    offset += chunk.length;
                }
                chatPrx.closeDownload(sessionId);
                System.out.println(">>> Archivo guardado con exito en: " + localFile.getPath());
            }

        } catch (ChatException ce) {
            System.err.println("[ERROR DESCARGA] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR RED] " + e.getMessage());
        }
    }

}

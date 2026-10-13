package chat.client;

import ChatApp.ChatClientPrx;
import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoomPrx;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;
import ChatApp.CallCallbackPrx;
import ChatApp.CallException;
import ChatApp.CallServicePrx;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Scanner;

public class ClientMain {
    private static volatile boolean running = true;
    private static long lastReceivedId = 0;
    private static final ConversationContext conversation = new ConversationContext();

    public static void main(String[] args) {
        try (Communicator communicator = Util.initialize(args)) {
            final String serverHost = args.length > 0 ? args[0] : "127.0.0.1";
            String proxyString = "ChatService:default -h " + serverHost + " -p 10000";
            ObjectPrx base = communicator.stringToProxy(proxyString);
            ChatRoomPrx chatPrx = ChatRoomPrx.checkedCast(base);

            if (chatPrx == null) {
                System.err.println("[ERROR] No se pudo establecer conexion con el servicio Ice remoto.");
                return;
            }

            Scanner scanner = new Scanner(System.in);
            String nickname = "";

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

            ObjectAdapter callbackAdapter = registerCallback(communicator, chatPrx, activeUser);

            CallServicePrx callPrx = CallServicePrx.checkedCast(
                    communicator.stringToProxy("CallService:default -h " + serverHost + " -p 10000"));
            if (callPrx == null) {
                System.err.println("[ERROR] El servidor no ofrece el servicio de llamadas.");
                return;
            }
            CallManager callManager = new CallManager(activeUser, detectLocalIp(serverHost), callPrx,
                    conversation::prompt);
            CallCallbackPrx callbackPrx = CallCallbackPrx.uncheckedCast(
                    callbackAdapter.addWithUUID(new CallCallbackI(callManager)));
            callPrx.ice_getConnection().setAdapter(callbackAdapter);
            try {
                callPrx.registerCallback(activeUser, callbackPrx);
            } catch (CallException ce) {
                System.err.println("[ERROR] No se pudo registrar el callback de llamadas: " + ce.reason);
                return;
            }
            Thread listenerThread = new Thread(() -> {
                while (running) {
                    try {
                        ChatMessage[] newMessages = chatPrx.getPendingMessages(activeUser, lastReceivedId);
                        for (ChatMessage msg : newMessages) {
                            if (msg.id > lastReceivedId) {
                                lastReceivedId = msg.id;
                            }
                            if (!msg.sender.equals(activeUser)) {
                                String origin = conversation.getMode() == ConversationContext.Mode.GENERAL
                                        ? "" : "[General] ";
                                printAsync(origin + "[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
                            }
                        }
                        Thread.sleep(500);
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

            while (running) {
                System.out.print(conversation.prompt());
                String input = scanner.nextLine().trim();

                if (input.equalsIgnoreCase("/exit")) {
                    running = false;
                    callManager.shutdown();
                    try {
                        callPrx.unregisterCallback(activeUser);
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
                } else if (input.equals("/call") || input.startsWith("/call ")) {
                    String target = input.substring(5).trim();
                    if (target.isEmpty()) {
                        System.out.println(" || Debes usar la siguiente estructura: /call <usuario>");
                    } else {
                        callManager.call(target);
                    }
                } else if (input.equalsIgnoreCase("/accept")) {
                    callManager.accept();
                } else if (input.equalsIgnoreCase("/reject")) {
                    callManager.reject();
                } else if (input.equalsIgnoreCase("/hangup")) {
                    callManager.hangup();
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

    private static void printHelp() {
        System.out.println("===================== COMANDOS =====================");
        System.out.println("  /help                    Muestra esta lista de comandos");
        System.out.println("  /users                   Lista los usuarios conectados");
        System.out.println("  /msg <usuario> [texto]   Abre un chat privado con <usuario> (y envia [texto] si se indica)");
        System.out.println("  /closew                  Cierra el chat privado y vuelve al chat general");
        System.out.println("  /upload <ruta>           Sube un archivo al servidor y lo comparte con la sala");
        System.out.println("  /download <archivo>      Descarga un archivo compartido a la carpeta 'downloads'");
        System.out.println("  /call <usuario>          Inicia una llamada de voz con <usuario>");
        System.out.println("  /accept                  Contesta una llamada entrante");
        System.out.println("  /reject                  Rechaza una llamada entrante");
        System.out.println("  /hangup                  Cuelga la llamada (o la cancela mientras suena)");
        System.out.println("  /exit                    Cierra la sesion y sale del chat");
        System.out.println("  <texto>                  Envia el texto a la conversacion actual (general o privada)");
        System.out.println("====================================================");
    }

    private static ObjectAdapter registerCallback(Communicator communicator, ChatRoomPrx chatPrx, String nickname)
            throws ChatException {
        ObjectAdapter adapter = communicator.createObjectAdapter("");
        ChatClientI servant = new ChatClientI(ClientMain::onPrivateMessage);
        ChatClientPrx callbackPrx = ChatClientPrx.uncheckedCast(adapter.addWithUUID(servant));
        adapter.activate();
        chatPrx.ice_getConnection().setAdapter(adapter);
        chatPrx.registerCallback(nickname, callbackPrx);
        return adapter;
    }

    private static String detectLocalIp(String serverHost) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(serverHost), 10000);
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }

    private static void onPrivateMessage(ChatMessage msg) {
        if (conversation.isPrivateWith(msg.sender)) {
            printAsync("[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
        } else {
            printAsync("[Privado de " + msg.sender + "] [" + msg.timestamp + "]: " + msg.text
                    + "  (responde con /msg " + msg.sender + ")");
        }
    }

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
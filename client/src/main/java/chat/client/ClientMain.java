package chat.client;

import ChatApp.CallCallbackPrx;
import ChatApp.CallException;
import ChatApp.CallServicePrx;
import ChatApp.ChatClientPrx;
import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoomPrx;
import ChatApp.RoomInfo;
import com.zeroc.Ice.Communicator;
import com.zeroc.Ice.ObjectAdapter;
import com.zeroc.Ice.ObjectPrx;
import com.zeroc.Ice.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

public class ClientMain {
    private static volatile boolean running = true;
    private static long lastReceivedId = 0;
    // Conversacion activa: chat general o ventana privada abierta con /msg
    private static final ConversationContext conversation = new ConversationContext();
    private static final Set<String> joinedRooms = ConcurrentHashMap.newKeySet();

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

            String callProxyString = "CallService:default -h 127.0.0.1 -p 10000";
            CallServicePrx callPrx = CallServicePrx.checkedCast(communicator.stringToProxy(callProxyString));

            String myIp = detectLocalIp("127.0.0.1");
            CallManager callMgr = null;
            if (callPrx != null) {
                callMgr = new CallManager(activeUser, myIp, callPrx, conversation::prompt);
                registerCallCallback(communicator, callPrx, activeUser, callMgr);
            }

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
                    if (callMgr != null) {
                        callMgr.shutdown();
                    }
                    try {
                        chatPrx.logout(activeUser);
                    } catch (Exception ignored) {
                    }
                    System.out.println(">>> Sesion cerrada satisfactoriamente. Hasta pronto!");
                    break;
                } else if (input.equals("/msg") || input.startsWith("/msg ")) {
                    openPrivateChat(chatPrx, activeUser, input.substring(4).trim());
                                } else if (input.equalsIgnoreCase("/closew") || input.equalsIgnoreCase("/general")) {
                    closeConversation();
                } else if (input.equalsIgnoreCase("/rooms")) {
                    listRooms(chatPrx);
                } else if (input.equals("/create") || input.startsWith("/create ")) {
                    createRoom(chatPrx, activeUser, argument(input));
                } else if (input.equals("/join") || input.startsWith("/join ")) {
                    joinRoom(chatPrx, activeUser, argument(input));
                } else if (input.equals("/leave") || input.startsWith("/leave ")) {
                    leaveRoom(chatPrx, activeUser, argument(input));
                } else if (input.equals("/room") || input.startsWith("/room ")) {
                    switchRoom(argument(input));
                } else if (input.equals("/members") || input.startsWith("/members ")) {
                    showMembers(chatPrx, argument(input));
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
                        // En una ventana privada el archivo solo se comparte con el destinatario
                        ConversationContext.Mode mode = conversation.getMode();
                        String target = mode == ConversationContext.Mode.PRIVATE ? conversation.getTarget() : null;
                        String room = mode == ConversationContext.Mode.ROOM ? conversation.getTarget() : null;
                        uploadFile(chatPrx, activeUser, target, room, filePath);
                    } else {
                        System.out.println(" || Debes usar la sigueinte estructura: /upload<ruta_del_archivo>");
                    }
                } else if (input.startsWith("/download")) {
                    String fileName = input.substring(10).trim();
                    if (!fileName.isEmpty()) {
                        String room = conversation.getMode() == ConversationContext.Mode.ROOM
                                ? conversation.getTarget() : null;
                        downloadFile(chatPrx, activeUser, fileName, room);
                    } else {
                        System.out.println(" || Debes usar la sigueinte estructura: /download<ruta_del_archivo>");
                    }
                } else if (input.equals("/call") || input.startsWith("/call ")) {
                    if (callMgr == null) {
                        System.out.println("[ERROR] Servicio de llamadas no disponible.");
                    } else {
                        String target = input.length() > 5 ? input.substring(6).trim() : "";
                        if (target.isEmpty()) {
                            System.out.println("Uso: /call <usuario>");
                        } else {
                            callMgr.call(target);
                        }
                    }
                } else if (input.equalsIgnoreCase("/accept")) {
                    if (callMgr != null) {
                        callMgr.accept();
                    }
                } else if (input.equalsIgnoreCase("/reject")) {
                    if (callMgr != null) {
                        callMgr.reject();
                    }
                } else if (input.equalsIgnoreCase("/hangup")) {
                    if (callMgr != null) {
                        callMgr.hangup();
                    }
                } else if (!input.isEmpty() && conversation.getMode() == ConversationContext.Mode.PRIVATE) {
                    sendPrivate(chatPrx, activeUser, conversation.getTarget(), input);
                } else if (!input.isEmpty() && conversation.getMode() == ConversationContext.Mode.ROOM) {
                    sendRoom(chatPrx, activeUser, conversation.getTarget(), input);
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
        System.out.println("  /rooms                   Lista las salas disponibles ([*] = ya perteneces)");
        System.out.println("  /create <sala>           Crea una sala y entra en ella");
        System.out.println("  /join <sala>             Se une a una sala y abre su ventana");
        System.out.println("  /room <sala>             Cambia a la ventana de una sala a la que ya perteneces");
        System.out.println("  /members [sala]          Lista los miembros de la sala (por defecto, la actual)");
        System.out.println("  /leave [sala]            Abandona una sala (por defecto, la actual)");
        System.out.println("  /closew  (o /general)    Cierra la ventana actual (privada o sala) y vuelve al chat general");
        System.out.println("  /upload <ruta>           Sube un archivo a la conversacion actual (general, privada o sala)");
        System.out.println("  /download <archivo>      Descarga un archivo de la conversacion actual a la carpeta 'downloads'");
        System.out.println("  /call <usuario>          Inicia una llamada de voz UDP con <usuario>");
        System.out.println("  /accept                  Contesta una llamada entrante");
        System.out.println("  /reject                  Rechaza una llamada entrante");
        System.out.println("  /hangup                  Cuelga la llamada activa o cancela la saliente");
        System.out.println("  /exit                    Cierra la sesion y sale del chat");
        System.out.println("  <texto>                  Envia el texto a la conversacion actual (general, privada o sala)");
        System.out.println("====================================================");
    }

    // Crea un adaptador sin endpoints y lo asocia a la conexion ya abierta con el servidor
    // (conexion bidireccional). Asi el servidor puede invocar al cliente sin que este abra
    // puertos, lo que funciona igual en localhost y entre maquinas de la red.
    private static void registerCallback(Communicator communicator, ChatRoomPrx chatPrx, String nickname)
            throws ChatException {
        ObjectAdapter adapter = communicator.createObjectAdapter("");
        ChatClientI servant = new ChatClientI(ClientMain::onPrivateMessage, ClientMain::onRoomMessage);
        ChatClientPrx callbackPrx = ChatClientPrx.uncheckedCast(adapter.addWithUUID(servant));
        adapter.activate();
        chatPrx.ice_getConnection().setAdapter(adapter);
        chatPrx.registerCallback(nickname, callbackPrx);
    }

    private static void registerCallCallback(Communicator communicator, CallServicePrx callPrx,
                                             String nickname, CallManager mgr) throws CallException {
        ObjectAdapter adapter = communicator.createObjectAdapter("");
        CallCallbackI servant = new CallCallbackI(mgr);
        CallCallbackPrx callbackPrx = CallCallbackPrx.uncheckedCast(adapter.addWithUUID(servant));
        adapter.activate();
        callPrx.ice_getConnection().setAdapter(adapter);
        callPrx.registerCallback(nickname, callbackPrx);
    }

    private static String detectLocalIp(String serverHost) {
        try (DatagramSocket probe = new DatagramSocket()) {
            probe.connect(InetAddress.getByName(serverHost), 10000);
            return probe.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
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

        // Se ejecuta en el hilo de Ice cuando el servidor entrega un mensaje de una sala.
    // Si la sala no es la ventana activa, se marca el origen para que el usuario pueda cambiar a ella
    private static void onRoomMessage(String room, ChatMessage msg) {
        if (conversation.isRoom(room)) {
            printAsync("[" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text);
        } else {
            printAsync("[Sala " + room + "] [" + msg.timestamp + "] <" + msg.sender + ">: " + msg.text
                    + "  (cambia con /room " + room + ")");
        }
    }

    private static String argument(String input) {
        int space = input.indexOf(' ');
        return space < 0 ? "" : input.substring(space + 1).trim();
    }

    // /closew y /general: cierra la ventana privada o de sala. En una sala NO abandona la sala
    // (para eso esta /leave), solo vuelve al chat general
    private static void closeConversation() {
        switch (conversation.getMode()) {
            case PRIVATE:
                System.out.println(">>> Conversacion privada con '" + conversation.getTarget()
                        + "' cerrada. De vuelta al chat general.");
                break;
            case ROOM:
                System.out.println(">>> Ventana de la sala '" + conversation.getTarget()
                        + "' cerrada (sigues siendo miembro; vuelve con /room " + conversation.getTarget()
                        + "). De vuelta al chat general.");
                break;
            default:
                System.out.println(">>> Ya estas en el chat general.");
                return;
        }
        conversation.backToGeneral();
    }

    private static void createRoom(ChatRoomPrx chatPrx, String activeUser, String room) {
        if (room.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /create <sala>");
            return;
        }
        try {
            chatPrx.createRoom(activeUser, room);
            joinedRooms.add(room);
            conversation.openRoom(room);
            System.out.println("============ Sala '" + room + "' creada ============");
            System.out.println(">>> Ya estas dentro. Otros usuarios pueden unirse con /join " + room
                    + ". Usa /closew para volver al chat general.");
        } catch (ChatException ce) {
            System.err.println("[ERROR SALA] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
        }
    }

    private static void listRooms(ChatRoomPrx chatPrx) {
        try {
            RoomInfo[] rooms = chatPrx.listRooms();
            if (rooms.length == 0) {
                System.out.println(">>> No hay salas todavia. Crea una con /create <sala>.");
                return;
            }
            System.out.println(">>> Salas disponibles (" + rooms.length + "):");
            for (RoomInfo r : rooms) {
                System.out.println("  " + (joinedRooms.contains(r.name) ? "[*] " : "[ ] ") + r.name
                        + "  (" + r.members + " miembro(s), creada por " + r.creator + ")");
            }
            System.out.println("    [*] = perteneces a la sala. Unete con /join <sala>.");
        } catch (Exception e) {
            System.err.println("[ERROR RED] No se pudo obtener la lista de salas: " + e.getMessage());
        }
    }

    private static void joinRoom(ChatRoomPrx chatPrx, String activeUser, String room) {
        if (room.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /join <sala>");
            return;
        }
        try {
            chatPrx.joinRoom(activeUser, room);
            joinedRooms.add(room);
            conversation.openRoom(room);
            System.out.println("============ Sala '" + room + "' ============");
            System.out.println(">>> Te uniste a la sala. Usa /members para ver quien esta y /closew para salir de la ventana.");
        } catch (ChatException ce) {
            System.err.println("[ERROR SALA] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
        }
    }

    // /leave [sala]: sin argumento abandona la sala de la ventana actual
    private static void leaveRoom(ChatRoomPrx chatPrx, String activeUser, String room) {
        if (room.isEmpty() && conversation.getMode() == ConversationContext.Mode.ROOM) {
            room = conversation.getTarget();
        }
        if (room.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /leave <sala>");
            return;
        }
        try {
            chatPrx.leaveRoom(activeUser, room);
            joinedRooms.remove(room);
            if (conversation.isRoom(room)) {
                conversation.backToGeneral();
            }
            System.out.println(">>> Abandonaste la sala '" + room + "'.");
        } catch (ChatException ce) {
            System.err.println("[ERROR SALA] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
        }
    }

    // /room <sala>: cambia la ventana activa a una sala de la que ya se es miembro
    private static void switchRoom(String room) {
        if (room.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /room <sala>. Tus salas: "
                    + (joinedRooms.isEmpty() ? "(ninguna)" : String.join(", ", new TreeSet<>(joinedRooms))));
            return;
        }
        if (!joinedRooms.contains(room)) {
            System.out.println("[ERROR SALA] No perteneces a la sala '" + room + "'. Usa /join " + room
                    + " o /rooms para ver las disponibles.");
            return;
        }
        conversation.openRoom(room);
        System.out.println("============ Sala '" + room + "' ============");
    }

    // /members [sala]: sin argumento usa la sala de la ventana actual
    private static void showMembers(ChatRoomPrx chatPrx, String room) {
        if (room.isEmpty() && conversation.getMode() == ConversationContext.Mode.ROOM) {
            room = conversation.getTarget();
        }
        if (room.isEmpty()) {
            System.out.println(" || Debes usar la siguiente estructura: /members <sala>");
            return;
        }
        try {
            String[] members = chatPrx.getRoomMembers(room);
            System.out.println(">>> Miembros de '" + room + "' (" + members.length + "): "
                    + String.join(", ", members));
        } catch (ChatException ce) {
            System.err.println("[ERROR SALA] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
        }
    }

    private static void sendRoom(ChatRoomPrx chatPrx, String activeUser, String room, String text) {
        try {
            chatPrx.sendRoomMessage(activeUser, room, text);
        } catch (ChatException ce) {
            System.err.println("[ERROR SALA] " + ce.reason + " Usa /closew para volver al chat general.");
        } catch (Exception e) {
            System.err.println("[ERROR RED] Comunicacion fallida: " + e.getMessage());
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

    // target null = archivo para toda la sala; si no, archivo privado para ese usuario
    private static void uploadFile(ChatRoomPrx chatPrx, String nickname, String target, String room,
            String pathStr) {        File file = new File(pathStr);
        if (!file.exists() || !file.isFile()) {
            System.err.println("[ERROR] El archivo no existe o no es valido: " + pathStr);
            return;
        }

        System.out.println(">>> Subiendo " + file.getName() + " (" + file.length() + " bytes)...");

        try (FileInputStream fis = new FileInputStream(file)) {
            String sessionId;
            if (room != null) {
                sessionId = chatPrx.startRoomUpload(file.getName(), nickname, room);
            } else if (target != null) {
                sessionId = chatPrx.startPrivateUpload(file.getName(), nickname, target);
            } else {
                sessionId = chatPrx.startUpload(file.getName(), nickname);
            }

            byte[] buffer = new byte[32 * 1024];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                byte[] chunk = (bytesRead == buffer.length) ? buffer : Arrays.copyOf(buffer, bytesRead);
                chatPrx.writeChunk(sessionId, chunk);
            }

            chatPrx.closeUpload(sessionId);
            System.out.println(">>> Archivo '" + file.getName() + "' subido con exito"
                    + (room != null ? " y compartido en la sala " + room + "."
                            : target == null ? "." : " y enviado en privado a " + target + "."));

        } catch (

        ChatException ce) {
            System.err.println("[RECHAZADO] " + ce.reason);
        } catch (Exception e) {
            System.err.println("[ERROR SUBIDA] " + e.getMessage());
        }
    }

    public static void downloadFile(ChatRoomPrx chatPrx, String nickname, String fileName, String room) {
        try {

            String sessionId = room == null
                    ? chatPrx.startDownload(fileName, nickname)
                    : chatPrx.startRoomDownload(fileName, nickname, room);
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

package chat.server;

import ChatApp.ChatClientPrx;
import ChatApp.ChatException;
import ChatApp.ChatMessage;
import ChatApp.ChatRoom;
import com.zeroc.Ice.Current;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import ChatApp.RoomInfo; 
import java.io.IOException;     
import java.util.regex.Pattern;

public class ChatRoomI implements ChatApp.ChatRoom {
    private final Set<String> onlineUsers = ConcurrentHashMap.newKeySet();
    private final List<ChatMessage> messageHistory = new CopyOnWriteArrayList<>();
    private final AtomicLong messageIdCounter = new AtomicLong(0);
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final File storageDir = new File("chat_files");
    // Los archivos privados van en su propia carpeta para no pisar archivos de la sala
    private final File privateStorageDir = new File(storageDir, "private");
    // Archivo privado -> usuarios autorizados a descargarlo (remitente y destinatario)
    private final Map<String, Set<String>> privateFiles = new ConcurrentHashMap<>();
    private final Map<String, UploadSession> activeUploads = new ConcurrentHashMap<>();
    private final Map<String, DownloadSession> activeDownloads = new ConcurrentHashMap<>();

    // Proxies de callback de cada usuario conectado.
    private final Map<String, ChatClientPrx> clientCallbacks = new ConcurrentHashMap<>();
    
    private static final Pattern ROOM_NAME = Pattern.compile("[A-Za-z0-9_-]{1,30}");
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();

    // Los archivos de cada sala viven en chat_files/rooms/<sala>/
    private final File roomStorageDir = new File(storageDir, "rooms");

    @Override
    public synchronized void login(String nickname, Current current) throws ChatException {
        if (nickname == null || nickname.trim().isEmpty()) {
            throw new ChatException("El nickname no puede ser nulo o vacio.");
        }
        String cleanNick = nickname.trim();
        if (onlineUsers.contains(cleanNick)) {
            throw new ChatException("El nickname ’" + cleanNick + "’ ya se encuentra conectado.");
        }
        onlineUsers.add(cleanNick);
        System.out.println("[ICE - SERVER ] Usuario conectado con exito: " + cleanNick);
        // Notificacion interna del sistema
        String now = LocalTime.now().format(timeFormat);
        long id = messageIdCounter.incrementAndGet();
        ChatMessage joinMsg = new ChatMessage(id, " SISTEMA ", cleanNick + " se unio a la sala .", now);
        messageHistory.add(joinMsg);
    }

    @Override
    public void postMessage(String nickname, String message, Current current) throws ChatException {
        if (!onlineUsers.contains(nickname)) {
            throw new ChatException(" Acceso denegado : El usuario debe iniciar sesion primero .");
        }
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        long id = messageIdCounter.incrementAndGet();
        String now = LocalTime.now().format(timeFormat);
        ChatMessage msg = new ChatMessage(id, nickname, message.trim(), now);
        messageHistory.add(msg);
        System.out.println("[" + now + "] <" + nickname + "> " + message.trim());
    }

    @Override
    public ChatMessage[] getPendingMessages(String nickname, long lastMessageId, Current current) {
        List<ChatMessage> pending = new ArrayList<>();
        for (ChatMessage msg : messageHistory) {
            if (msg.id > lastMessageId) {
                pending.add(msg);
            }
        }
        return pending.toArray(new ChatMessage[0]);
    }

    @Override
    public String[] getOnlineUsers(Current current) {
        return onlineUsers.toArray(new String[0]);
    }

    @Override
    public void registerCallback(String nickname, ChatClientPrx callback, Current current) throws ChatException {
        if (!onlineUsers.contains(nickname)) {
            throw new ChatException("Acceso denegado: El usuario debe iniciar sesion primero.");
        }
        if (callback == null) {
            throw new ChatException("El proxy de callback no puede ser nulo.");
        }
        // Conexion bidireccional: el servidor reutiliza la conexion TCP que abrio el cliente
        // no necesita abrir una conexion nueva hacia el cliente
        clientCallbacks.put(nickname, callback.ice_fixed(current.con));
        System.out.println("[ICE - SERVER ] Callback registrado para: " + nickname);
    }

    @Override
    public void sendPrivateMessage(String sender, String target, String message, Current current)
            throws ChatException {
        if (!onlineUsers.contains(sender)) {
            throw new ChatException("Acceso denegado: El usuario debe iniciar sesion primero.");
        }
        if (target == null || target.trim().isEmpty()) {
            throw new ChatException("Debe indicar el nickname del destinatario.");
        }
        String cleanTarget = target.trim();
        if (cleanTarget.equals(sender)) {
            throw new ChatException("No puedes enviarte un mensaje privado a ti mismo.");
        }
        ChatClientPrx targetPrx = clientCallbacks.get(cleanTarget);
        if (!onlineUsers.contains(cleanTarget) || targetPrx == null) {
            throw new ChatException("El usuario '" + cleanTarget + "' no existe o se ha desconectado.");
        }
        if (message == null || message.trim().isEmpty()) {
            return;
        }

        long id = messageIdCounter.incrementAndGet();
        String now = LocalTime.now().format(timeFormat);
        ChatMessage msg = new ChatMessage(id, sender, message.trim(), now);

        // Despacho asincrono: no bloquea al remitente mientras se entrega al destinatario
        targetPrx.receivePrivateMessageAsync(msg).whenComplete((ok, ex) -> {
            if (ex != null) {
                System.err.println("[PRIVADO] No se pudo entregar a " + cleanTarget + ": " + ex);
                removeUnreachableClient(cleanTarget, targetPrx);
            }
        });
        System.out.println("[" + now + "] <" + sender + " -> " + cleanTarget + "> (privado)");
    }

    // Si un callback falla el cliente se cayo de forma abrupta: se limpia su sesion
    private void removeUnreachableClient(String nickname, ChatClientPrx failedPrx) {
        if (clientCallbacks.remove(nickname, failedPrx)) {
            logout(nickname, null);
        }
    }

    @Override
    public synchronized void logout(String nickname, Current current) {
        clientCallbacks.remove(nickname);
        leaveAllRooms(nickname);
        if (onlineUsers.remove(nickname)) {
            System.out.println("[ICE - SERVER ] Usuario desconectado : " + nickname);
            String now = LocalTime.now().format(timeFormat);
            long id = messageIdCounter.incrementAndGet();
            ChatMessage leaveMsg = new ChatMessage(id, " SISTEMA ", nickname + " ha abandonado la sala .", now);
            messageHistory.add(leaveMsg);
        }
    }

    @Override
    public synchronized String startUpload(String fileName, String nickname, Current current) throws ChatException {
        if (!onlineUsers.contains(nickname)) {
            throw new ChatException("Acceso denegado: El usuario debe iniciar sesion primero.");
        }

        return openUpload(fileName, nickname, null, null, storageDir);
    }

    @Override
    public synchronized String startPrivateUpload(String fileName, String sender, String target, Current current)
            throws ChatException {
        if (!onlineUsers.contains(sender)) {
            throw new ChatException("Acceso denegado: El usuario debe iniciar sesion primero.");
        }
        if (target == null || target.trim().isEmpty()) {
            throw new ChatException("Debe indicar el nickname del destinatario.");
        }
        String cleanTarget = target.trim();
        if (cleanTarget.equals(sender)) {
            throw new ChatException("No puedes enviarte un archivo privado a ti mismo.");
        }
        if (!onlineUsers.contains(cleanTarget) || !clientCallbacks.containsKey(cleanTarget)) {
            throw new ChatException("El usuario '" + cleanTarget + "' no existe o se ha desconectado.");
        }
        return openUpload(fileName, sender, cleanTarget, null, privateStorageDir);
    }

    @Override
    public String startRoomUpload(String fileName, String sender, String room, Current current) throws ChatException {
        requireLoggedIn(sender);
        Room r = requireMember(sender, room);
        return openUpload(fileName, sender, null, r.name, new File(roomStorageDir, r.name));
    }

    // Abre el archivo destino y registra la sesion de subida (target null = archivo de la sala)
    private String openUpload(String fileName, String sender, String target, String room, File dir)
        throws ChatException {
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }

            String safeName = new File(fileName).getName();
            File destFile = new File(dir, safeName);
            FileOutputStream fos = new FileOutputStream(destFile);

            String sessionId = UUID.randomUUID().toString();
            activeUploads.put(sessionId, new UploadSession(safeName, sender, fos, target, room));

            String scope = room != null ? " para la sala " + room : target == null ? "" : " para " + target + " (privado)";
            System.out.println(
                    "[UPLOAD] Iniciando subida de: " + safeName + " por " + sender + scope + " (Sesion: " + sessionId
                            + ") (para descargar e archivo usa /download<nombre_del_archivo>)");
            return sessionId;

        } catch (Exception e) {
            throw new ChatException("Error al preparar subida en el servidor: " + e.getMessage());
        }
    }

    @Override
    public void writeChunk(String sessionId, byte[] chunk, Current current) {
        UploadSession session = activeUploads.get(sessionId);
        if (session != null && chunk != null) {
            try {
                session.outputStream.write(chunk);
            } catch (Exception e) {
                System.err.println("[ERROR UPLOAD] Fallo escribiendo bloque: " + e.getMessage());
            }
        }
    }

    @Override
    public void closeUpload(String sessionId, Current current) {
        UploadSession session = activeUploads.remove(sessionId);
        if (session != null) {
            try {
                session.outputStream.close();
                System.out.println("[UPLOAD] Archivo completado: " + session.fileName);

                String now = LocalTime.now().format(timeFormat);
                long id = messageIdCounter.incrementAndGet();
                                if (session.room != null) {
                    Room room = rooms.get(session.room);
                    if (room != null) {
                        broadcastRoom(room, new ChatMessage(id, session.sender,
                                "ha compartido el archivo: " + session.fileName
                                        + " (usa /download " + session.fileName + ")", now),
                                session.sender);
                    }
                    return;
                }
                if (session.target != null) {
                    // Archivo privado: solo remitente y destinatario pueden descargarlo,
                    // y solo el destinatario recibe el aviso (por callback, como un mensaje privado)
                    privateFiles.put(session.fileName, Set.of(session.sender, session.target));
                    notifyPrivateFile(session, new ChatMessage(id, session.sender,
                            "te ha enviado el archivo: " + session.fileName
                                    + " (usa /download " + session.fileName + ")", now));
                    return;
                }

                // Notificar a toda la sala a través del historial de chat
                ChatMessage notification = new ChatMessage(id, "SISTEMA",
                        session.sender + " ha compartido el archivo: " + session.fileName, now);
                messageHistory.add(notification);

            } catch (Exception e) {
                System.err.println("[ERROR UPLOAD] Error cerrando el archivo: " + e.getMessage());
            }
        }
    }

    private void notifyPrivateFile(UploadSession session, ChatMessage msg) {
        ChatClientPrx targetPrx = clientCallbacks.get(session.target);
        if (targetPrx == null) {
            System.err.println("[PRIVADO] " + session.target + " se desconecto antes de recibir "
                    + session.fileName);
            return;
        }
        targetPrx.receivePrivateMessageAsync(msg).whenComplete((ok, ex) -> {
            if (ex != null) {
                System.err.println("[PRIVADO] No se pudo notificar a " + session.target + ": " + ex);
                removeUnreachableClient(session.target, targetPrx);
            }
        });
    }

    @Override
    public String startDownload(String fileName, String nickname, Current current) throws ChatException {

        try {

            if (!onlineUsers.contains(nickname)) {
                throw new ChatException("Acceso denegado");
            }
            String safeName = new File(fileName).getName();
            // Si el archivo fue compartido en privado con este usuario se sirve desde la
            // carpeta privada; si no, desde la carpeta publica de la sala
            Set<String> allowed = privateFiles.get(safeName);
            File dir = allowed != null && allowed.contains(nickname) ? privateStorageDir : storageDir;
            File destFile = new File(dir, safeName);
            FileInputStream fis = new FileInputStream(destFile);

            String sessionId = UUID.randomUUID().toString();
            activeDownloads.put(sessionId, new DownloadSession(safeName, nickname, fis));
            return sessionId;

        } catch (Exception e) {
            throw new ChatException("Error al preparar descarga en el servidor: " + e.getMessage());
        }

    }

    @Override
    public byte[] readChunk(String sessionId, long offset, long size, Current current) {
        DownloadSession session = activeDownloads.get(sessionId);
        if (session != null) {
            try {
                return session.inputStream.readNBytes((int) size);
            } catch (Exception e) {
                System.err.println("[ERROR DOWNLOAD] Fallo leyendo bloque: " + e.getMessage());
            }
        }
        return new byte[0];
    }

    @Override
    public void closeDownload(String sessionId, Current current) {
        DownloadSession session = activeDownloads.remove(sessionId);
        if (session != null) {
            try {
                session.inputStream.close();
                System.out.println("[DOWNLOAD] Archivo completado: " + session.fileName);
            } catch (Exception e) {
                System.err.println("[ERROR DOWNLOAD] Error cerrando el archivo: " + e.getMessage());
            }
        }
    }

    @Override
    public void createRoom(String nickname, String room, Current current) throws ChatException {
        requireLoggedIn(nickname);
        String name = room == null ? "" : room.trim();
        if (!ROOM_NAME.matcher(name).matches()) {
            throw new ChatException("Nombre de sala invalido. Usa de 1 a 30 caracteres: letras, numeros, '_' o '-'.");
        }
        Room newRoom = new Room(name, nickname);
        newRoom.members.add(nickname);
        // putIfAbsent es atomico: si dos usuarios crean el mismo nombre a la vez, solo uno lo logra
        if (rooms.putIfAbsent(name, newRoom) != null) {
            throw new ChatException("La sala '" + name + "' ya existe.");
        }
        System.out.println("[SALA] '" + name + "' creada por " + nickname);
    }

    @Override
    public RoomInfo[] listRooms(Current current) {
        List<RoomInfo> list = new ArrayList<>();
        for (Room r : rooms.values()) {
            list.add(new RoomInfo(r.name, r.creator, r.members.size()));
        }
        list.sort((a, b) -> a.name.compareTo(b.name));
        return list.toArray(new RoomInfo[0]);
    }

    @Override
    public void joinRoom(String nickname, String room, Current current) throws ChatException {
        requireLoggedIn(nickname);
        Room r = requireRoom(room);
        if (!r.members.add(nickname)) {
            throw new ChatException("Ya perteneces a la sala '" + r.name + "'.");
        }
        System.out.println("[SALA] " + nickname + " se unio a '" + r.name + "'");
        broadcastRoom(r, systemMessage(nickname + " se unio a la sala."), nickname);
    }

    @Override
    public void leaveRoom(String nickname, String room, Current current) throws ChatException {
        requireLoggedIn(nickname);
        Room r = requireRoom(room);
        if (!r.members.remove(nickname)) {
            throw new ChatException("No perteneces a la sala '" + r.name + "'.");
        }
        System.out.println("[SALA] " + nickname + " salio de '" + r.name + "'");
        broadcastRoom(r, systemMessage(nickname + " salio de la sala."), nickname);
    }

    @Override
    public String[] getRoomMembers(String room, Current current) throws ChatException {
        Room r = requireRoom(room);
        String[] members = r.members.toArray(new String[0]);
        Arrays.sort(members);
        return members;
    }

    @Override
    public void sendRoomMessage(String sender, String room, String message, Current current)
            throws ChatException {
        requireLoggedIn(sender);
        // Solo los miembros pueden escribir: esto garantiza el aislamiento entre grupos
        Room r = requireMember(sender, room);
        if (message == null || message.trim().isEmpty()) {
            return;
        }
        String now = LocalTime.now().format(timeFormat);
        ChatMessage msg = new ChatMessage(messageIdCounter.incrementAndGet(), sender, message.trim(), now);
        // El mensaje se difunde a todos los miembros menos al emisor (sin duplicado)
        broadcastRoom(r, msg, sender);
        System.out.println("[" + now + "] <" + sender + " -> #" + r.name + "> (sala)");
    }

    @Override
    public String startRoomDownload(String fileName, String nickname, String room, Current current)
            throws ChatException {
        requireLoggedIn(nickname);
        Room r = requireMember(nickname, room);
        // getName() descarta cualquier ruta que venga en el nombre (evita "../")
        String safeName = new File(fileName).getName();
        File file = new File(new File(roomStorageDir, r.name), safeName);
        if (!file.isFile()) {
            throw new ChatException("El archivo '" + safeName + "' no existe en la sala '" + r.name + "'.");
        }
        try {
            FileInputStream fis = new FileInputStream(file);
            String sessionId = UUID.randomUUID().toString();
            activeDownloads.put(sessionId, new DownloadSession(safeName, nickname, fis));
            return sessionId;
        } catch (IOException e) {
            throw new ChatException("Error al preparar descarga en el servidor: " + e.getMessage());
        }
    }

    //utilidades de salas 

    private void requireLoggedIn(String nickname) throws ChatException {
        if (nickname == null || !onlineUsers.contains(nickname)) {
            throw new ChatException("Acceso denegado: El usuario debe iniciar sesion primero.");
        }
    }

    private Room requireRoom(String room) throws ChatException {
        Room r = room == null ? null : rooms.get(room.trim());
        if (r == null) {
            throw new ChatException("La sala '" + room + "' no existe. Usa /rooms para ver las disponibles.");
        }
        return r;
    }

    private Room requireMember(String nickname, String room) throws ChatException {
        Room r = requireRoom(room);
        if (!r.members.contains(nickname)) {
            throw new ChatException("No perteneces a la sala '" + r.name + "'. Usa /join " + r.name + ".");
        }
        return r;
    }

    private ChatMessage systemMessage(String text) {
        return new ChatMessage(messageIdCounter.incrementAndGet(), "SISTEMA", text,
                LocalTime.now().format(timeFormat));
    }

    // Envia el mensaje a cada miembro de la sala por su callback, de forma asincrona para que
    // un cliente lento no bloquee a los demas. 'exclude' (puede ser null) no recibe copia.
    private void broadcastRoom(Room room, ChatMessage msg, String exclude) {
        for (String member : room.members) {
            if (member.equals(exclude)) {
                continue;
            }
            ChatClientPrx prx = clientCallbacks.get(member);
            if (prx == null) {
                continue;
            }
            prx.receiveRoomMessageAsync(room.name, msg).whenComplete((ok, ex) -> {
                if (ex != null) {
                    System.err.println("[SALA] No se pudo entregar a " + member + " en '" + room.name + "': " + ex);
                    removeUnreachableClient(member, prx);
                }
            });
        }
    }

    private void leaveAllRooms(String nickname) {
        for (Room r : rooms.values()) {
            if (r.members.remove(nickname)) {
                broadcastRoom(r, systemMessage(nickname + " se desconecto."), nickname);
            }
        }
    }

}

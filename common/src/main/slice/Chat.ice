module ChatApp {
    // Estructura que modela un mensaje del chat
    struct ChatMessage {
        long id;
        string sender;
        string text;
        string timestamp;
    };

    // Secuencias tipadas (equivalentes a arreglos/listas en Java)
    sequence<ChatMessage> MessageSeq;
    sequence<string> UserSeq;

    // Excepcion de usuario para validaciones de negocio
    exception ChatException {
        string reason;
    };

   // Archivos multimedia
   sequence<byte> ByteSeq;

    // Interfaz de retorno (callback) implementada por el CLIENTE.
    // El servidor invoca estos metodos para notificar eventos asincronos.
    interface ChatClient {
        // RF-02: el servidor entrega un mensaje privado dirigido a este cliente
        void receivePrivateMessage(ChatMessage msg);
    };

    // Interfaz remota provista por el servidor Ice
    interface ChatRoom {
        // Registra el proxy de callback del cliente; se llama justo despues de login
        void registerCallback(string nickname, ChatClient* callback) throws ChatException;

        // RF-02: envia un mensaje privado 1 a 1. Si retorna sin excepcion, el servidor
        // recibio el mensaje (confirmacion); si el destino no existe lanza ChatException
        void sendPrivateMessage(string sender, string target, string message) throws ChatException;

        // Registra un usuario; falla si el nickname ya existe
        void login(string nickname) throws ChatException;

        // Envia un mensaje a la sala compartida
        void postMessage(string nickname, string message) throws ChatException;

        // Consulta de mensajes pendientes; idempotent permite reintentos seguros de red
        idempotent MessageSeq getPendingMessages(string nickname, long lastMessageId);

        // Retorna la lista de usuarios activos actualmente
        idempotent UserSeq getOnlineUsers();

        // Cierra sesion y desvincula al usuario
        void logout(string nickname);

        // Envir archivos
        string startUpload(string fileName, string nickname) throws ChatException;
        void writeChunk(string sessionId, ByteSeq chunk);
        void closeUpload(string sessionId);
        // Download
        string startDownload(string fileName, string nickname) throws ChatException;
        ByteSeq readChunk(string sessionId, long offset, long size);
        void closeDownload(string sessionId);
    };
        exception CallException {
            string reason;
        };

        interface CallCallback {
            void incomingCall(long callId, string caller, string ip, int port);
            void callAccepted(long callId, string callee, string ip, int port);
            void callRejected(long callId, string callee);
            void callEnded(long callId, string byUser);
        };

        interface CallService {
            void registerCallback(string nickname, CallCallback* cb) throws CallException;
            void unregisterCallback(string nickname);
            long startCall(string caller, string callee, string ip, int port) throws CallException;
            void acceptCall(long callId, string callee, string ip, int port) throws CallException;
            void rejectCall(long callId, string callee) throws CallException;
            void hangUp(long callId, string user) throws CallException;
        };
};

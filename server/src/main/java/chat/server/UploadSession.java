package chat.server;

import java.io.FileOutputStream;

// Representa una subida en progreso
public class UploadSession {
    final String fileName;
    final String sender;
    final FileOutputStream outputStream;
    // Destinatario de un archivo privado; null si el archivo se comparte con toda la sala
    final String target;
    final String room;

    UploadSession(String fileName, String sender, FileOutputStream fos) {
        this(fileName, sender, fos, null, null);
    }

    UploadSession(String fileName, String sender, FileOutputStream fos, String target) {
        this(fileName, sender, fos, target, null);
    }

    UploadSession(String fileName, String sender, FileOutputStream fos, String target, String room) {
        this.fileName = fileName;
        this.sender = sender;
        this.outputStream = fos;
        this.target = target;
        this.room = room;
    }
}

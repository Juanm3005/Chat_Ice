package chat.server;

import java.io.File;
import java.io.FileOutputStream;

// Representa una subida en progreso
public class UploadSession {
    final String fileName;
    final String sender;
    final FileOutputStream outputStream;
    final File file;
    // Destinatario de un archivo privado; null si el archivo se comparte con toda la sala
    final String target;
    final String room;

    UploadSession(String fileName, String sender, File file, FileOutputStream fos, String target, String room) {
        this.fileName = fileName;
        this.sender = sender;
        this.file = file;
        this.outputStream = fos;
        this.target = target;
        this.room = room;
    }
}

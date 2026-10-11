package chat.server;

import java.io.FileOutputStream;

// Representa una subida en progreso
public class UploadSession {
    final String fileName;
    final String sender;
    final FileOutputStream outputStream;

    UploadSession(String fileName, String sender, FileOutputStream fos) {
        this.fileName = fileName;
        this.sender = sender;
        this.outputStream = fos;
    }
}

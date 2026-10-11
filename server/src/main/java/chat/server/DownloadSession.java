package chat.server;

import java.io.FileInputStream;

public class DownloadSession {

    String fileName;
    String nickname;
    FileInputStream inputStream;

    DownloadSession(String fileName, String nickname, FileInputStream inputStream) {
        this.fileName = fileName;
        this.nickname = nickname;
        this.inputStream = inputStream;
    }
}

package chat.client;

// Indica en que conversacion esta el usuario: el chat general (inicio) o una ventana privada.
// Lo comparten el hilo de consola y el hilo de callbacks, por eso los campos son volatile.
public class ConversationContext {
    public enum Mode { GENERAL, PRIVATE }

    private volatile Mode mode = Mode.GENERAL;
    private volatile String target = null;

    public synchronized void openPrivate(String nickname) {
        this.target = nickname;
        this.mode = Mode.PRIVATE;
    }

    public synchronized void backToGeneral() {
        this.mode = Mode.GENERAL;
        this.target = null;
    }

    public Mode getMode() {
        return mode;
    }

    public String getTarget() {
        return target;
    }

    public boolean isPrivateWith(String nickname) {
        return mode == Mode.PRIVATE && nickname.equals(target);
    }

    public String prompt() {
        return mode == Mode.PRIVATE ? "[privado con " + target + "]> " : "> ";
    }
}

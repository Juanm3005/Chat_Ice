package chat.server;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// distinto, por eso los miembros se guardan en un conjunto concurrente.
final class Room {
    final String name;
    final String creator;
    final Set<String> members = ConcurrentHashMap.newKeySet();

    Room(String name, String creator) {
        this.name = name;
        this.creator = creator;
    }
}
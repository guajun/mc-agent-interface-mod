package dev.mcagent.interfacemod;

import java.util.function.Consumer;

/**
 * What a connected client can ask for. Implemented twice: once with the client
 * vantage ({@link InterfaceCore}) and once with the server vantage
 * ({@link ServerCore}), both served by the same {@link InterfaceServer}.
 */
public interface LineHandler {
    void handleLine(String line, Consumer<String> reply);
}

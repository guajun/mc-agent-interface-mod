package dev.mcagent.interfacemod;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;

public final class InterfaceServer {
    private final int basePort;
    private final LineHandler handler;
    private final String instance;
    private final String capabilities;
    private final CopyOnWriteArrayList<Client> clients = new CopyOnWriteArrayList<>();
    private volatile boolean running = true;
    private ServerSocket serverSocket;
    private volatile int port;

    public InterfaceServer(int basePort, LineHandler handler, String instance, String capabilities) {
        this.basePort = basePort;
        this.port = basePort;
        this.handler = handler;
        this.instance = instance;
        this.capabilities = capabilities;
    }

    public boolean start() {
        for (int candidate = basePort; candidate < basePort + 20; candidate++) {
            try {
                ServerSocket socket = new ServerSocket();
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), candidate));
                this.serverSocket = socket;
                this.port = candidate;
                Thread thread = new Thread(this::acceptLoop, "mc-agent-interface-server");
                thread.setDaemon(true);
                thread.start();
                System.out.println("[mc-agent-interface] listening on 127.0.0.1:" + candidate);
                return true;
            } catch (IOException exception) {
                System.err.println("[mc-agent-interface] port " + candidate + " unavailable: " + exception.getMessage());
            }
        }
        System.err.println("[mc-agent-interface] no free port in " + basePort + ".." + (basePort + 19));
        return false;
    }

    public int getPort() {
        return port;
    }

    public void broadcast(String line) {
        for (Client client : clients) {
            client.send(line);
        }
    }

    public int clientCount() {
        return clients.size();
    }

    /** Close the listener and hang up; a world can be closed and reopened. */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // closing
        }
        for (Client client : clients) {
            client.close();
        }
        clients.clear();
    }

    private void acceptLoop() {
        try {
            while (running) {
                Socket socket = serverSocket.accept();
                Client client = new Client(socket);
                clients.add(client);
                client.start();
            }
        } catch (IOException exception) {
            if (running) {
                System.err.println("[mc-agent-interface] server failed: " + exception);
            }
        }
    }

    private final class Client {
        private final Socket socket;
        private final BufferedWriter writer;

        private Client(Socket socket) throws IOException {
            this.socket = socket;
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        private void start() {
            Thread thread = new Thread(this::readLoop, "mc-agent-interface-client");
            thread.setDaemon(true);
            thread.start();
            send("{\"type\":\"hello\",\"mod\":\"" + InterfaceConstants.MOD_ID
                    + "\",\"version\":\"" + InterfaceConstants.VERSION
                    + "\",\"protocol\":" + InterfaceConstants.PROTOCOL_VERSION
                    + ",\"minecraft\":\"26.2\",\"port\":" + port
                    + ",\"instance\":\"" + instance + "\""
                    + ",\"capabilities\":" + capabilities + "}");
        }

        private void readLoop() {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    handler.handleLine(line.trim(), this::send);
                }
            } catch (IOException ignored) {
                // client disconnected
            } finally {
                close();
                clients.remove(this);
            }
        }

        private void send(String line) {
            synchronized (writer) {
                try {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                } catch (IOException exception) {
                    close();
                    clients.remove(this);
                }
            }
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }
}


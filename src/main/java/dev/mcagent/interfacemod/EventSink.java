package dev.mcagent.interfacemod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

public final class EventSink {
    public interface Listener {
        void onLine(String jsonLine);
    }

    private static final String SAMPLE_PREFIX = "S|";

    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Path dir;
    private volatile boolean running = true;

    public EventSink(Path dir) {
        this.dir = dir;
    }

    public Gson gson() {
        return gson;
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
    }

    public void start() {
        try {
            Files.createDirectories(dir);
        } catch (IOException exception) {
            System.err.println("[mc-agent-interface] cannot create " + dir + ": " + exception);
        }
        Thread writer = new Thread(this::writeLoop, "mc-agent-interface-sink");
        writer.setDaemon(true);
        writer.start();
    }

    public void emit(JsonObject object) {
        queue.offer(gson.toJson(object));
    }

    public void emitSample(JsonObject object) {
        queue.offer(SAMPLE_PREFIX + gson.toJson(object));
    }

    private void writeLoop() {
        Path eventsPath = dir.resolve("events.jsonl");
        Path samplesPath = dir.resolve("samples.jsonl");
        try (BufferedWriter events = Files.newBufferedWriter(eventsPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
             BufferedWriter samples = Files.newBufferedWriter(samplesPath, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            while (running) {
                String line = queue.take();
                if (line.startsWith(SAMPLE_PREFIX)) {
                    String sample = line.substring(SAMPLE_PREFIX.length());
                    samples.write(sample);
                    samples.newLine();
                    samples.flush();
                    continue;
                }
                events.write(line);
                events.newLine();
                events.flush();
                for (Listener listener : listeners) {
                    try {
                        listener.onLine(line);
                    } catch (Throwable throwable) {
                        System.err.println("[mc-agent-interface] listener failed: " + throwable);
                    }
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            System.err.println("[mc-agent-interface] sink failed: " + exception);
        }
    }
}


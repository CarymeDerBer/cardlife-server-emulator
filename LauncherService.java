package cardlife;

import java.io.IOException;
import java.net.BindException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/** Вся логика лаунчера без привязки к интерфейсу: используется и GUI, и консольным режимом. */
public final class LauncherService implements AutoCloseable {

    public static final int GAME_PORT = 8080;
    private static final long MAX_LOG_FILE = 2L * 1024 * 1024;

    public enum Level { INFO, OK, WARN, ERROR }

    public interface Sink {
        void log(Level level, String message);
    }

    private final Sink sink;
    private final Consumer<MockAuthServer.RequestRecord> requestListener;
    private final Path baseDir;
    private final Path logFile;

    private volatile MockAuthServer server;
    private volatile Process gameProcess;

    public LauncherService(Sink sink, Consumer<MockAuthServer.RequestRecord> requestListener, Path baseDir) {
        this.sink = sink;
        this.requestListener = requestListener;
        this.baseDir = baseDir;
        this.logFile = baseDir.resolve("launcher.log");
    }

    public Path baseDir() {
        return baseDir;
    }

    public void log(Level level, String message) {
        sink.log(level, message);
        appendToFile(level, message);
    }

    private synchronized void appendToFile(Level level, String message) {
        try {
            if (Files.exists(logFile) && Files.size(logFile) > MAX_LOG_FILE) {
                Files.deleteIfExists(logFile);
            }
            String line = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"))
                    + " [" + level + "] " + message + System.lineSeparator();
            Files.writeString(logFile, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // журнал в файл — вспомогательная функция
        }
    }

    // ------------------------------------------------------------------ server

    public boolean isServerRunning() {
        MockAuthServer s = server;
        return s != null && s.isRunning();
    }

    public MockAuthServer server() {
        return server;
    }

    public synchronized void startServer(int port) throws IOException {
        if (isServerRunning()) {
            log(Level.WARN, I18n.t("svc.already"));
            return;
        }
        MockAuthServer s = new MockAuthServer(port, baseDir,
                (error, msg) -> log(error ? Level.ERROR : Level.INFO, msg), requestListener);
        try {
            s.start();
        } catch (BindException e) {
            s.close();
            throw new IOException(I18n.t("svc.port.busy", port, e.getMessage()), e);
        }
        server = s;
    }

    public synchronized void stopServer() {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    // ------------------------------------------------------------------ game

    public Optional<Path> findGame() {
        Optional<Path> p = GameLocator.find();
        if (p.isPresent()) {
            log(Level.OK, I18n.t("svc.game.found", p.get()));
        } else {
            log(Level.WARN, I18n.t("svc.game.notfound"));
        }
        return p;
    }

    /** Бэкап → ServerConfig.json → патч URL в DLL. Порт всегда {@link #GAME_PORT}. */
    public void applyPatch(Path gameDir, List<String> markers) throws IOException {
        Path dll = gameDir.resolve(GameLocator.REL_DLL);
        if (BackupManager.ensureBackup(dll)) {
            log(Level.INFO, I18n.t("svc.backup.created", BackupManager.backupPath(dll)));
        } else {
            log(Level.INFO, I18n.t("svc.backup.exists", BackupManager.backupPath(dll)));
        }

        ServerConfigPatcher.Result cr = ServerConfigPatcher.patch(gameDir, GAME_PORT);
        log(Level.OK, cr.created() ? I18n.t("svc.cfg.created", cr.base()) : I18n.t("svc.cfg.patched", cr.base(), cr.changed()));

        DllStringPatcher.Result r = DllStringPatcher.patch(dll, markers);
        r.details().forEach(d -> log(d.startsWith("SKIP") ? Level.WARN : Level.INFO, "  " + d));
        if (r.replaced() > 0) {
            log(Level.OK, I18n.t("svc.dll.patched", r.replaced(), r.skipped()));
        } else {
            log(Level.INFO, I18n.t("svc.dll.none"));
        }
    }

    public void revert(Path gameDir) throws IOException {
        Path dll = gameDir.resolve(GameLocator.REL_DLL);
        log(Level.INFO, I18n.t(BackupManager.revert(dll) ? "svc.revert.dll.ok" : "svc.revert.dll.none"));
        log(Level.INFO, I18n.t(ServerConfigPatcher.revert(gameDir) ? "svc.revert.cfg.ok" : "svc.revert.cfg.none"));
    }

    public List<String> scan(Path gameDir, boolean original) throws IOException {
        Path dll = gameDir.resolve(GameLocator.REL_DLL);
        Path source = original ? BackupManager.backupPath(dll) : dll;
        if (original && !Files.isRegularFile(source)) {
            source = dll; // бэкапа ещё нет — оригинал это текущий файл
        }
        List<String> urls = DllStringPatcher.scan(source);
        Path out = baseDir.resolve("urls.txt");
        Files.write(out, urls);
        log(Level.OK, I18n.t("svc.scan.result", urls.size(), out));
        urls.stream().limit(40).forEach(u -> log(Level.INFO, "  " + u));
        return urls;
    }

    public void launchGame(Path gameDir) throws IOException {
        Path exe = gameDir.resolve(GameLocator.EXE_NAME);
        try {
            Process p = new ProcessBuilder(exe.toString())
                    .directory(gameDir.toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            gameProcess = p;
            log(Level.OK, I18n.t("svc.game.started", p.pid()));
            p.onExit().thenAccept(pr -> log(Level.INFO, I18n.t("svc.game.exited", pr.exitValue())));
        } catch (IOException e) {
            throw new IOException(I18n.t("svc.game.fail", exe, e.getMessage()), e);
        }
    }

    public boolean isGameRunning() {
        Process p = gameProcess;
        return p != null && p.isAlive();
    }

    @Override
    public void close() {
        stopServer();
    }
}

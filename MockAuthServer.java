package cardlife;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Локальный HTTP-сервер-заглушка авторизации ({@code com.sun.net.httpserver}).
 *
 * <ul>
 *   <li>отвечает {@code 200 OK} + JSON на любой метод и путь, логирует и сохраняет запросы в {@code responses/};</li>
 *   <li>если в {@code overrides/} есть {@code <МЕТОД>_<путь>.json} или {@code <путь>.json}
 *       (например {@code /api/auth/login} -> {@code api_auth_login.json}) — отдаёт его (файл перечитывается на каждый запрос);</li>
 *   <li>служебные страницы: {@code /_status} (HTML), {@code /_health} и {@code /_requests} (JSON);</li>
 *   <li>держит статистику и последние запросы — их показывает GUI.</li>
 * </ul>
 */
public final class MockAuthServer implements AutoCloseable {

    /** Запись об обработанном запросе (для GUI и страницы статуса). */
    public record RequestRecord(long id, LocalDateTime time, String method, String path, String query,
                                int status, int bytes, long millis, String body, String source) {
    }

    /** Приёмник сообщений сервера. */
    public interface Logger {
        void log(boolean error, String message);
    }

    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final DateTimeFormatter SHORT_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_LOG_BODY = 500;
    private static final int MAX_KEPT_FILES = 500;
    private static final int RECENT_LIMIT = 200;

    private final int port;
    private final Path responsesDir;
    private final Path overridesDir;
    private final Logger logger;
    private final Consumer<RequestRecord> listener;

    private final AtomicLong counter = new AtomicLong();
    private final Map<String, AtomicLong> perPath = new ConcurrentHashMap<>();
    private final Deque<RequestRecord> recent = new ArrayDeque<>();

    private volatile HttpServer server;
    private volatile ExecutorService pool;
    private volatile Instant startedAt = Instant.now();

    public MockAuthServer(int port, Path baseDir, Logger logger, Consumer<RequestRecord> listener) {
        this.port = port;
        this.responsesDir = baseDir.resolve("responses");
        this.overridesDir = baseDir.resolve("overrides");
        this.logger = logger != null ? logger : (e, m) -> { };
        this.listener = listener;
    }

    public int port() {
        return port;
    }

    public boolean isRunning() {
        return server != null;
    }

    public long totalRequests() {
        return counter.get();
    }

    public Duration uptime() {
        return Duration.between(startedAt, Instant.now());
    }

    public Path responsesDir() {
        return responsesDir;
    }

    public Path overridesDir() {
        return overridesDir;
    }

    public void start() throws IOException {
        InetAddress loopback = InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        HttpServer s = HttpServer.create(new InetSocketAddress(loopback, port), 0);
        ExecutorService p = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "mock-auth-worker");
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(p);
        s.createContext("/", this::handle);
        s.start();
        server = s;
        pool = p;
        startedAt = Instant.now();
        Files.createDirectories(overridesDir);
        logger.log(false, I18n.t("svc.listen", port));
        logger.log(false, I18n.t("svc.saved", responsesDir.toAbsolutePath()));
    }

    @Override
    public synchronized void close() {
        HttpServer s = server;
        server = null;
        if (s != null) {
            s.stop(1);
            logger.log(false, I18n.t("svc.stopped"));
        }
        ExecutorService p = pool;
        pool = null;
        if (p != null) {
            p.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ handling

    private void handle(HttpExchange ex) {
        long t0 = System.nanoTime();
        String method = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = ex.getRequestURI().getRawQuery();
        String body = "";
        int status = 200;
        int sent = 0;
        String source = "default";
        boolean internal = path.startsWith("/_");

        try {
            body = new String(ex.getRequestBody().readNBytes(MAX_BODY), StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "*");
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD");

            if (internal) {
                source = "internal";
                sent = serveInternal(ex, path, method);
            } else if ("OPTIONS".equalsIgnoreCase(method)) {
                status = 204;
                source = "preflight";
                ex.sendResponseHeaders(204, -1);
            } else {
                byte[] response = loadOverride(method, path);
                if (response != null) {
                    source = "override";
                } else {
                    response = defaultJson(path).getBytes(StandardCharsets.UTF_8);
                }
                sent = send(ex, 200, "application/json; charset=utf-8", response, method);
            }
        } catch (Exception e) {
            status = 500;
            source = "error";
            logger.log(true, "Ошибка обработки запроса " + method + " " + path + ": " + e);
            try {
                sent = send(ex, 500, "application/json; charset=utf-8",
                        "{\"error\":\"internal\"}".getBytes(StandardCharsets.UTF_8), method);
            } catch (Exception ignored) {
                // клиент уже отключился
            }
        } finally {
            ex.close();
        }

        if (!internal) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            long id = counter.incrementAndGet();
            perPath.computeIfAbsent(path, k -> new AtomicLong()).incrementAndGet();
            RequestRecord rec = new RequestRecord(id, LocalDateTime.now(), method, path, query, status, sent, ms,
                    body.length() > 4000 ? body.substring(0, 4000) + "…" : body, source);
            synchronized (recent) {
                recent.addFirst(rec);
                while (recent.size() > RECENT_LIMIT) {
                    recent.pollLast();
                }
            }
            logger.log(status >= 500, method + " " + path + (query != null ? "?" + query : "") + "  → " + status
                    + " [" + source + ", " + ms + " ms]"
                    + (body.isEmpty() ? "" : "  body=" + truncate(body, MAX_LOG_BODY)));
            saveRequest(ex, id, method, path, query, body);
            if (listener != null) {
                try {
                    listener.accept(rec);
                } catch (RuntimeException ignored) {
                    // ошибки GUI не должны влиять на сервер
                }
            }
        }
    }

    private static int send(HttpExchange ex, int status, String type, byte[] data, String method) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if ("HEAD".equalsIgnoreCase(method)) {
            ex.sendResponseHeaders(status, -1);
            return 0;
        }
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(data);
        }
        return data.length;
    }

    private int serveInternal(HttpExchange ex, String path, String method) throws IOException {
        List<RequestRecord> snapshot;
        synchronized (recent) {
            snapshot = new ArrayList<>(recent);
        }
        switch (path) {
            case "/_health" -> {
                String json = "{\"status\":\"ok\",\"port\":" + port + ",\"uptimeSeconds\":" + uptime().toSeconds()
                        + ",\"requests\":" + counter.get() + "}";
                return send(ex, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8), method);
            }
            case "/_requests" -> {
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < snapshot.size(); i++) {
                    RequestRecord r = snapshot.get(i);
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append("{\"id\":").append(r.id())
                            .append(",\"time\":\"").append(r.time()).append('"')
                            .append(",\"method\":\"").append(jsonEscape(r.method())).append('"')
                            .append(",\"path\":\"").append(jsonEscape(r.path())).append('"')
                            .append(",\"status\":").append(r.status())
                            .append(",\"millis\":").append(r.millis())
                            .append(",\"source\":\"").append(r.source()).append("\"}");
                }
                sb.append(']');
                return send(ex, 200, "application/json; charset=utf-8", sb.toString().getBytes(StandardCharsets.UTF_8), method);
            }
            case "/_status" -> {
                return send(ex, 200, "text/html; charset=utf-8", statusPage(snapshot).getBytes(StandardCharsets.UTF_8), method);
            }
            default -> {
                return send(ex, 404, "application/json; charset=utf-8",
                        "{\"error\":\"unknown internal endpoint\"}".getBytes(StandardCharsets.UTF_8), method);
            }
        }
    }

    private String statusPage(List<RequestRecord> snapshot) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!doctype html><html><head><meta charset=\"utf-8\"><meta http-equiv=\"refresh\" content=\"3\">")
                .append("<title>CardLife mock server</title><style>")
                .append("body{font:14px system-ui,sans-serif;background:#1e1f22;color:#dcdcdc;margin:24px}")
                .append("h1{font-size:20px}table{border-collapse:collapse;width:100%}")
                .append("td,th{padding:4px 10px;border-bottom:1px solid #3a3c40;text-align:left}")
                .append(".ok{color:#6cc070}.err{color:#e06c6c}.muted{color:#888}</style></head><body>")
                .append("<h1>CardLife mock server</h1><p class=\"muted\">port ").append(port)
                .append(" · uptime ").append(formatDuration(uptime()))
                .append(" · requests ").append(counter.get()).append("</p>")
                .append("<table><tr><th>#</th><th>Time</th><th>Method</th><th>Path</th><th>Status</th><th>ms</th><th>Source</th></tr>");
        for (int i = 0; i < Math.min(snapshot.size(), 50); i++) {
            RequestRecord r = snapshot.get(i);
            sb.append("<tr><td>").append(r.id()).append("</td><td>").append(r.time().format(SHORT_TIME))
                    .append("</td><td>").append(html(r.method())).append("</td><td>").append(html(r.path()))
                    .append("</td><td class=\"").append(r.status() >= 400 ? "err" : "ok").append("\">").append(r.status())
                    .append("</td><td>").append(r.millis()).append("</td><td>").append(html(r.source())).append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    /**
     * Универсальный ответ. Схемы AuthenticateResponse / DevMessageResponse взяты из Assembly-CSharp.dll;
     * для неизвестных путей добавлены самые частые синонимы полей.
     */
    private static String defaultJson(String path) {
        String p = path.toLowerCase();
        if (p.endsWith("/auth/steamauthenticate") || p.endsWith("/auth/authenticate")) {
            return "{\"PublicId\":\"3f2504e0-4f89-41d3-9a0c-0305e82c3301\",\"EmailAddress\":\"local@localhost\","
                    + "\"DisplayName\":\"LocalPlayer\",\"Purchases\":[],\"Flags\":[],"
                    + "\"Confirmed\":true,\"Token\":\"7c9e6679-7425-40de-944b-e07fc1f90ae7\","
                    + "\"SteamId\":\"76561198000000000\",\"ID\":12345}";
        }
        if (p.endsWith("/status/devmsg")) {
            return "{\"message\":\"\"}";
        }
        return "{"
                + "\"success\":true,\"status\":\"success\",\"error\":null,"
                + "\"session_token\":\"mock_token\",\"sessionToken\":\"mock_token\",\"token\":\"mock_token\","
                + "\"user_id\":\"12345\",\"userId\":\"12345\",\"display_name\":\"LocalPlayer\","
                + "\"Success\":true,\"Status\":\"success\",\"Error\":null,"
                + "\"Token\":\"mock_token\",\"SessionToken\":\"mock_token\",\"UserId\":\"12345\","
                + "\"DisplayName\":\"LocalPlayer\",\"Message\":\"\",\"message\":\"\","
                + "\"path\":\"" + jsonEscape(path) + "\"}";
    }

    private byte[] loadOverride(String method, String path) {
        String name = safeName(path);
        for (String candidate : new String[]{method.toUpperCase() + "_" + name + ".json", name + ".json"}) {
            Path file = overridesDir.resolve(candidate);
            try {
                if (Files.isRegularFile(file)) {
                    return Files.readAllBytes(file);
                }
            } catch (IOException e) {
                logger.log(true, "Не удалось прочитать " + file + ": " + e.getMessage());
            }
        }
        return null;
    }

    private void saveRequest(HttpExchange ex, long id, String method, String path, String query, String body) {
        try {
            Files.createDirectories(responsesDir);
            String name = String.format("%s-%04d_%s_%s.txt", LocalDateTime.now().format(FILE_TIME), id, method, safeName(path));
            StringBuilder sb = new StringBuilder();
            sb.append(method).append(' ').append(ex.getRequestURI()).append('\n');
            sb.append("Remote: ").append(ex.getRemoteAddress()).append('\n');
            for (Map.Entry<String, List<String>> h : ex.getRequestHeaders().entrySet()) {
                sb.append(h.getKey()).append(": ").append(String.join(", ", h.getValue())).append('\n');
            }
            sb.append('\n').append(body);
            Files.writeString(responsesDir.resolve(name), sb.toString(), StandardCharsets.UTF_8);
            if (id % 25 == 0) {
                pruneResponses();
            }
        } catch (IOException e) {
            logger.log(true, "Не удалось сохранить запрос: " + e.getMessage());
        }
    }

    /** Не даём папке responses расти бесконечно: оставляем последние {@value #MAX_KEPT_FILES} файлов. */
    private void pruneResponses() {
        try (Stream<Path> s = Files.list(responsesDir)) {
            List<Path> files = s.filter(Files::isRegularFile).sorted().toList();
            for (int i = 0; i < files.size() - MAX_KEPT_FILES; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ignored) {
            // не критично
        }
    }

    // ------------------------------------------------------------------ helpers

    /** /api/auth/login -> api_auth_login; безопасно для имени файла (без path traversal). */
    static String safeName(String path) {
        String s = path.replaceAll("^/+|/+$", "").replace('/', '_').replaceAll("[^A-Za-z0-9._-]+", "_");
        if (s.isEmpty()) {
            s = "root";
        }
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    static String formatDuration(Duration d) {
        long s = d.toSeconds();
        return String.format("%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static String html(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    private static String truncate(String s, int max) {
        String flat = s.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }
}

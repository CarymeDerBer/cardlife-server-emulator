package cardlife;

import javax.swing.JFileChooser;
import java.awt.GraphicsEnvironment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

/**
 * Лаунчер CardLife: поиск игры -> бэкап -> патч -> Mock Auth Server -> запуск CardLife.exe.
 *
 * <pre>
 * java -jar cardlife-launcher.jar --launcher            (графический интерфейс)
 * java -jar cardlife-launcher.jar [путь_к_игре] [--game путь] [--gui] [--scan] [--revert]
 *                                 [--no-patch] [--no-launch] [--server-only] [--port N] [--markers a,b,c]
 * </pre>
 */
public final class Main {

    private static final BufferedReader STDIN = new BufferedReader(new InputStreamReader(System.in));

    private record Options(Path game, boolean scan, boolean scanCurrent, boolean revert, boolean noPatch,
                           boolean noLaunch, boolean gui, boolean serverOnly, int port, List<String> markers) {
    }

    private Main() {
    }

    public static void main(String[] args) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            e.printStackTrace();
            try {
                java.nio.file.Files.writeString(Paths.get("launcher-error.log"),
                        java.time.LocalDateTime.now() + " " + t.getName() + "\n" + stackTrace(e) + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException ignored) {
                // нечего делать
            }
        });
        if (Arrays.asList(args).contains("--launcher")) {
            LauncherGui.open();
            return;
        }
        System.exit(run(args));
    }

    private static String stackTrace(Throwable e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    // ------------------------------------------------------------------ flow

    private static int run(String[] args) {
        Options o;
        try {
            o = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            printHelp();
            return 2;
        }

        LauncherService svc = new LauncherService((level, msg) ->
                (level == LauncherService.Level.ERROR ? System.err : System.out).println(msg),
                null, Paths.get("").toAbsolutePath());

        Path gameDir = null;
        if (!o.serverOnly()) {
            gameDir = resolveGameDir(o, svc);
            if (gameDir == null) {
                System.err.println("Папка с игрой не найдена. Укажите её: --game \"D:\\SteamLibrary\\steamapps\\common\\CardLife\""
                        + "\nИли запустите только сервер: --server-only");
                return 1;
            }
            System.out.println(I18n.t("svc.game.found", gameDir));
        }

        try {
            if (!o.serverOnly()) {
                if (o.revert()) {
                    svc.revert(gameDir);
                    return 0;
                }
                if (o.scan() || o.scanCurrent()) {
                    svc.scan(gameDir, o.scan());
                    return 0;
                }
                if (!o.noPatch()) {
                    svc.applyPatch(gameDir, o.markers());
                }
            }
        } catch (IOException e) {
            System.err.println(I18n.t("svc.io.error", e.getMessage()));
            return 1;
        }

        int port = o.serverOnly() ? o.port() : LauncherService.GAME_PORT;
        try {
            svc.startServer(port);
        } catch (IOException e) {
            System.err.println(e.getMessage()
                    + "\nНайти процесс:  netstat -ano | findstr :" + port
                    + "\nЗавершить:      taskkill /PID <номер> /F");
            return 1;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(svc::close, "server-shutdown"));

        if (!o.noLaunch() && !o.serverOnly() && gameDir != null) {
            try {
                svc.launchGame(gameDir);
            } catch (IOException e) {
                System.err.println(e.getMessage() + "\n(CardLife.exe — Windows-приложение; на Linux нужен Proton/Wine.)");
            }
        }

        waitForQuit();
        svc.close();
        return 0;
    }

    private static void waitForQuit() {
        CountDownLatch latch = new CountDownLatch(1);
        Thread t = new Thread(() -> {
            try {
                String line;
                while ((line = STDIN.readLine()) != null) {
                    if (line.trim().equalsIgnoreCase("q")) {
                        latch.countDown();
                        return;
                    }
                }
            } catch (IOException ignored) {
                // stdin недоступен — ждём Ctrl+C
            }
        }, "stdin-watcher");
        t.setDaemon(true);
        t.start();

        System.out.println("Сервер работает. Введите q + Enter (или Ctrl+C), чтобы остановить.");
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ game dir resolution

    private static Path resolveGameDir(Options o, LauncherService svc) {
        if (o.game() != null) {
            Path p = o.game().toAbsolutePath().normalize();
            if (GameLocator.isValidGameDir(p)) {
                return p;
            }
            System.err.println(I18n.t("svc.game.badfolder", p));
            return null;
        }

        Optional<Path> found = svc.findGame();
        if (found.isPresent()) {
            return found.get();
        }

        if (o.gui() && !GraphicsEnvironment.isHeadless()) {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Выберите папку с CardLife.exe");
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                Path p = fc.getSelectedFile().toPath();
                if (GameLocator.isValidGameDir(p)) {
                    return p;
                }
                System.err.println(I18n.t("svc.game.badfolder", p));
            }
            return null;
        }

        for (int attempt = 0; attempt < 3; attempt++) {
            System.out.print("Введите путь к папке с CardLife.exe (пусто — выход): ");
            try {
                String line = STDIN.readLine();
                if (line == null || line.isBlank()) {
                    return null;
                }
                Path p = Paths.get(line.trim().replace("\"", "")).toAbsolutePath().normalize();
                if (GameLocator.isValidGameDir(p)) {
                    return p;
                }
                System.out.println(I18n.t("svc.game.badfolder", p));
            } catch (IOException | RuntimeException e) {
                System.out.println("Некорректный путь: " + e.getMessage());
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ CLI

    private static Options parse(String[] a) {
        Path game = null;
        boolean scan = false;
        boolean scanCurrent = false;
        boolean revert = false;
        boolean noPatch = false;
        boolean noLaunch = false;
        boolean gui = false;
        boolean serverOnly = false;
        int port = LauncherService.GAME_PORT;
        List<String> markers = DllStringPatcher.DEFAULT_MARKERS;

        for (int i = 0; i < a.length; i++) {
            switch (a[i]) {
                case "--game" -> game = Paths.get(value(a, ++i, "--game"));
                case "--port" -> {
                    try {
                        port = Integer.parseInt(value(a, ++i, "--port"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--port: нужно число 1024–65535");
                    }
                    if (port < 1024 || port > 65535) {
                        throw new IllegalArgumentException("--port: нужно число 1024–65535");
                    }
                }
                case "--markers" -> markers = Arrays.stream(value(a, ++i, "--markers").split(","))
                        .map(String::trim).filter(s -> !s.isEmpty()).toList();
                case "--scan" -> scan = true;
                case "--scan-current" -> scanCurrent = true;
                case "--revert" -> revert = true;
                case "--no-patch" -> noPatch = true;
                case "--no-launch" -> noLaunch = true;
                case "--gui" -> gui = true;
                case "--server-only" -> serverOnly = true;
                case "-h", "--help" -> {
                    printHelp();
                    System.exit(0);
                }
                default -> {
                    if (!a[i].startsWith("--") && game == null) {
                        game = Paths.get(a[i]);
                    } else {
                        throw new IllegalArgumentException("Неизвестный аргумент: " + a[i]);
                    }
                }
            }
        }
        return new Options(game, scan, scanCurrent, revert, noPatch, noLaunch, gui, serverOnly, port, markers);
    }

    private static String value(String[] a, int i, String flag) {
        if (i >= a.length) {
            throw new IllegalArgumentException("Для " + flag + " нужно значение");
        }
        return a[i];
    }

    private static void printHelp() {
        System.out.println("""
                Использование: java -jar cardlife-launcher.jar [путь_к_игре] [опции]
                  --launcher         графический интерфейс
                  --game <путь>      папка с CardLife.exe (иначе автопоиск, затем запрос)
                  --gui              если автопоиск не сработал — выбор папки в окне
                  --scan             URL из оригинальной Assembly-CSharp.dll -> urls.txt, затем выход
                  --scan-current     то же для текущей DLL
                  --markers a,b,c    фрагменты хостов для замены (по умолчанию cardlife,freejam,fjgames)
                  --no-patch         не трогать DLL и ServerConfig.json
                  --server-only      только сервер (без поиска игры, патча и запуска)
                  --port <N>         порт для --server-only (по умолчанию 8080; с игрой всегда 8080)
                  --no-launch        запустить сервер, но не игру
                  --revert           вернуть оригинальные файлы из .bak и выйти
                  -h, --help         эта справка""");
    }
}

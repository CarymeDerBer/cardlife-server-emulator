package cardlife;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Поиск установленной CardLife.
 * Порядок: реестр Steam (через {@code reg query}) -> стандартные пути на дисках A..Z ->
 * libraryfolders.vdf -> steamapps/common/CardLife (+ поиск по installdir в appmanifest).
 */
public final class GameLocator {

    public static final String EXE_NAME = "CardLife.exe";
    public static final Path REL_DLL = Paths.get("CardLife_Data", "Managed", "Assembly-CSharp.dll");

    private static final Pattern REG_SZ = Pattern.compile("REG_SZ\\s+(.+?)\\s*$");
    private static final Pattern VDF_PATH = Pattern.compile("\"path\"\\s+\"([^\"]+)\"");
    private static final Pattern INSTALL_DIR = Pattern.compile("\"installdir\"\\s+\"([^\"]+)\"");

    private GameLocator() {
    }

    /** Папка считается игрой, если в ней есть CardLife.exe и Assembly-CSharp.dll. */
    public static boolean isValidGameDir(Path dir) {
        return dir != null
                && Files.isRegularFile(dir.resolve(EXE_NAME))
                && Files.isRegularFile(dir.resolve(REL_DLL));
    }

    public static Optional<Path> find() {
        for (Path steamRoot : steamRoots()) {
            for (Path library : libraryFolders(steamRoot)) {
                Optional<Path> hit = searchLibrary(library);
                if (hit.isPresent()) {
                    return hit;
                }
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- Steam roots

    private static Set<Path> steamRoots() {
        Set<Path> roots = new LinkedHashSet<>();

        // 1) Реестр Windows
        addIfDir(roots, regQuery("HKCU\\Software\\Valve\\Steam", "SteamPath"));
        addIfDir(roots, regQuery("HKLM\\SOFTWARE\\WOW6432Node\\Valve\\Steam", "InstallPath"));
        addIfDir(roots, regQuery("HKLM\\SOFTWARE\\Valve\\Steam", "InstallPath"));

        // 2) Стандартные расположения на всех дисках
        for (char drive = 'C'; drive <= 'Z'; drive++) {
            String d = drive + ":\\";
            addIfDir(roots, d + "Program Files (x86)\\Steam");
            addIfDir(roots, d + "Program Files\\Steam");
            addIfDir(roots, d + "Steam");
            addIfDir(roots, d + "Games\\Steam");
            addIfDir(roots, d + "SteamLibrary"); // часто это только библиотека, но vdf/common там тоже ищем
        }
        return roots;
    }

    private static void addIfDir(Set<Path> set, String p) {
        if (p == null || p.isBlank()) {
            return;
        }
        try {
            Path path = Paths.get(p);
            if (Files.isDirectory(path)) {
                set.add(path.toAbsolutePath().normalize());
            }
        } catch (RuntimeException ignored) {
            // некорректный путь — пропускаем
        }
    }

    /** Читает значение из реестра командой {@code reg query}; на не-Windows вернёт null. */
    static String regQuery(String key, String value) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", key, "/v", value)
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), consoleCharset());
            p.waitFor(5, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                Matcher m = REG_SZ.matcher(line);
                if (m.find()) {
                    return m.group(1).replace('/', '\\');
                }
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
        return null;
    }

    /** Кодировка вывода reg.exe: консольная (OEM), если известна, иначе системная. */
    private static Charset consoleCharset() {
        for (String prop : new String[]{"sun.stdout.encoding", "native.encoding"}) {
            String name = System.getProperty(prop);
            if (name != null) {
                try {
                    return Charset.forName(name);
                } catch (RuntimeException ignored) {
                    // неизвестная кодировка — пробуем следующую
                }
            }
        }
        return Charset.defaultCharset();
    }

    // ---------------------------------------------------------------- libraries

    private static Set<Path> libraryFolders(Path steamRoot) {
        Set<Path> libs = new LinkedHashSet<>();
        libs.add(steamRoot);
        Path vdf = steamRoot.resolve("steamapps").resolve("libraryfolders.vdf");
        if (Files.isRegularFile(vdf)) {
            try {
                String text = Files.readString(vdf, StandardCharsets.UTF_8);
                Matcher m = VDF_PATH.matcher(text);
                while (m.find()) {
                    String raw = m.group(1).replace("\\\\", "\\");
                    Path p = Paths.get(raw);
                    if (Files.isDirectory(p)) {
                        libs.add(p);
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // повреждённый vdf — работаем с тем, что есть
            }
        }
        return libs;
    }

    private static Optional<Path> searchLibrary(Path library) {
        Path steamapps = library.resolve("steamapps");
        Path common = steamapps.resolve("common");

        // a) типичное имя
        Path direct = common.resolve("CardLife");
        if (isValidGameDir(direct)) {
            return Optional.of(direct);
        }

        // b) любая папка с "cardlife" в имени
        if (Files.isDirectory(common)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(common)) {
                for (Path d : ds) {
                    if (d.getFileName().toString().toLowerCase().contains("cardlife") && isValidGameDir(d)) {
                        return Optional.of(d);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        // c) appmanifest_*.acf -> installdir
        if (Files.isDirectory(steamapps)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(steamapps, "appmanifest_*.acf")) {
                for (Path acf : ds) {
                    Matcher m = INSTALL_DIR.matcher(Files.readString(acf, StandardCharsets.UTF_8));
                    if (m.find() && m.group(1).toLowerCase().contains("cardlife")) {
                        Path p = common.resolve(m.group(1));
                        if (isValidGameDir(p)) {
                            return Optional.of(p);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return Optional.empty();
    }
}

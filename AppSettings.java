package cardlife;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/** Настройки лаунчера в launcher.properties (язык, папка игры, порт, тема). */
public final class AppSettings {

    private final Path file;
    private final Properties props = new Properties();

    private AppSettings(Path file) {
        this.file = file;
    }

    public static AppSettings load() {
        AppSettings s = new AppSettings(Paths.get("launcher.properties").toAbsolutePath());
        if (Files.isRegularFile(s.file)) {
            try (InputStream in = Files.newInputStream(s.file)) {
                s.props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            } catch (IOException | IllegalArgumentException ignored) {
                // повреждённый файл — работаем со значениями по умолчанию
            }
        }
        return s;
    }

    public String get(String key, String def) {
        return props.getProperty(key, def);
    }

    public int getInt(String key, int def) {
        try {
            return Integer.parseInt(props.getProperty(key, "").trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    public boolean getBool(String key, boolean def) {
        String v = props.getProperty(key);
        return v == null ? def : Boolean.parseBoolean(v.trim());
    }

    public void set(String key, String value) {
        props.setProperty(key, value == null ? "" : value);
    }

    public void save() {
        try (OutputStream out = Files.newOutputStream(file)) {
            props.store(new java.io.OutputStreamWriter(out, java.nio.charset.StandardCharsets.UTF_8), "CardLife launcher settings");
        } catch (IOException ignored) {
            // не критично
        }
    }
}

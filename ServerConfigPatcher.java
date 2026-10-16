package cardlife;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Игра берёт адреса сервисов из ServerConfig.json в папке игры (AuthUrl, LobbyUrl, InventoryUrl и Fallback*).
 * Подменяем их на локальный Mock-сервер. Остальные поля существующего файла сохраняются.
 * Оригинал лежит в ServerConfig.json.bak.
 */
public final class ServerConfigPatcher {

    public static final String FILE_NAME = "ServerConfig.json";

    private static final Pattern URL_FIELD = Pattern.compile(
            "(\"(?:Fallback)?(?:Auth|Lobby|Inventory)Url\"\\s*:\\s*\")[^\"]*(\")");

    /** Результат патча: сколько полей заменено и создан ли файл заново. */
    public record Result(String base, int changed, boolean created) {
    }

    private ServerConfigPatcher() {
    }

    private static Path bak(Path cfg) {
        return cfg.resolveSibling(FILE_NAME + ".bak");
    }

    public static Result patch(Path gameDir, int port) throws IOException {
        Path cfg = gameDir.resolve(FILE_NAME);
        if (Files.isRegularFile(cfg) && !Files.exists(bak(cfg))) {
            Files.copy(cfg, bak(cfg));
        }
        String base = "http://127.0.0.1:" + port + "/api/";

        if (Files.isRegularFile(cfg)) {
            String text = Files.readString(cfg, StandardCharsets.UTF_8);
            Matcher m = URL_FIELD.matcher(text);
            StringBuilder out = new StringBuilder();
            int changed = 0;
            while (m.find()) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(1) + base + m.group(2)));
                changed++;
            }
            m.appendTail(out);
            if (changed >= 3) { // найдены основные адреса — остальное содержимое не трогаем
                Files.writeString(cfg, out.toString(), StandardCharsets.UTF_8);
                return new Result(base, changed, false);
            }
        }

        String json = "{\n"
                + "\t\"AuthUrl\": \"" + base + "\",\n"
                + "\t\"LobbyUrl\": \"" + base + "\",\n"
                + "\t\"InventoryUrl\": \"" + base + "\",\n"
                + "\t\"FallbackAuthUrl\": \"" + base + "\",\n"
                + "\t\"FallbackLobbyUrl\": \"" + base + "\",\n"
                + "\t\"FallbackInventoryUrl\": \"" + base + "\",\n"
                + "\t\"GameServerPath\": \"/\",\n"
                + "\t\"GameServerExe\": \"/CardlifeGameServer.exe\",\n"
                + "\t\"PhotonUrl\": \"http://photon.cardlifegame.com\"\n"
                + "}\n";
        Path tmp = cfg.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, cfg, StandardCopyOption.REPLACE_EXISTING);
        return new Result(base, 6, true);
    }

    public static boolean revert(Path gameDir) throws IOException {
        Path cfg = gameDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(bak(cfg))) {
            return false;
        }
        Files.copy(bak(cfg), cfg, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}

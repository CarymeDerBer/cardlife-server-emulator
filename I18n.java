package cardlife;

import java.util.HashMap;
import java.util.Map;

/** Простейшая локализация: ключ -> {ru, en, uk}. Используется и GUI, и сервисом (логи). */
public final class I18n {

    public enum Lang {
        RU("Русский"), EN("English"), UK("Українська");

        private final String label;

        Lang(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        public static Lang fromCode(String code) {
            for (Lang l : values()) {
                if (l.name().equalsIgnoreCase(code)) {
                    return l;
                }
            }
            return RU;
        }
    }

    private static final Map<String, String[]> M = new HashMap<>();
    private static volatile Lang lang = Lang.RU;

    private I18n() {
    }

    public static Lang lang() {
        return lang;
    }

    public static void setLang(Lang l) {
        lang = l == null ? Lang.RU : l;
    }

    public static String t(String key, Object... args) {
        String[] v = M.get(key);
        String s = v == null ? key : v[lang.ordinal()];
        return args.length == 0 ? s : String.format(s, args);
    }

    private static void p(String key, String ru, String en, String uk) {
        M.put(key, new String[]{ru, en, uk});
    }

    static {
        // ---- GUI
        p("app.title", "CardLife — локальный сервер", "CardLife — local server", "CardLife — локальний сервер");
        p("lang", "Язык:", "Language:", "Мова:");
        p("theme.dark", "Тёмная тема", "Dark theme", "Темна тема");
        p("game.folder", "Папка игры:", "Game folder:", "Папка гри:");
        p("browse", "Выбрать…", "Browse…", "Обрати…");
        p("detect", "Найти игру", "Detect", "Знайти гру");
        p("port", "Порт сервера:", "Server port:", "Порт сервера:");
        p("port.hint", "В режиме «Сервер + игра» всегда порт 8080 (он записывается в патч игры).",
                "“Server + game” mode always uses port 8080 (it is written into the game patch).",
                "У режимі «Сервер + гра» завжди порт 8080 (його записано в патч гри).");
        p("btn.server", "Только сервер", "Server only", "Лише сервер");
        p("btn.both", "Сервер + игра", "Server + game", "Сервер + гра");
        p("btn.stop", "Остановить", "Stop", "Зупинити");
        p("btn.tools", "Инструменты ▾", "Tools ▾", "Інструменти ▾");
        p("tool.revert", "Откатить патч игры", "Revert game patch", "Відкотити патч гри");
        p("tool.scan.orig", "Сканировать URL (оригинал DLL)", "Scan URLs (original DLL)", "Сканувати URL (оригінал DLL)");
        p("tool.scan.cur", "Сканировать URL (текущая DLL)", "Scan URLs (current DLL)", "Сканувати URL (поточна DLL)");
        p("tool.open.resp", "Открыть папку responses", "Open responses folder", "Відкрити папку responses");
        p("tool.open.over", "Открыть папку overrides", "Open overrides folder", "Відкрити папку overrides");
        p("tool.open.game", "Открыть папку игры", "Open game folder", "Відкрити папку гри");
        p("tool.status", "Страница статуса в браузере", "Status page in browser", "Сторінка статусу в браузері");
        p("tab.log", "Журнал", "Log", "Журнал");
        p("tab.requests", "Запросы", "Requests", "Запити");
        p("log.clear", "Очистить", "Clear", "Очистити");
        p("log.copy", "Копировать", "Copy", "Копіювати");
        p("log.save", "Сохранить…", "Save…", "Зберегти…");
        p("log.autoscroll", "Автопрокрутка", "Auto-scroll", "Автопрокрутка");
        p("col.time", "Время", "Time", "Час");
        p("col.method", "Метод", "Method", "Метод");
        p("col.path", "Путь", "Path", "Шлях");
        p("col.status", "Код", "Status", "Код");
        p("col.ms", "мс", "ms", "мс");
        p("col.size", "Байт", "Bytes", "Байт");
        p("status.stopped", "Остановлен", "Stopped", "Зупинено");
        p("status.running", "Работает · порт %d", "Running · port %d", "Працює · порт %d");
        p("status.busy", "Выполняется…", "Working…", "Виконується…");
        p("status.game", " · игра запущена", " · game running", " · гра запущена");
        p("status.requests", "Запросов: %d", "Requests: %d", "Запитів: %d");
        p("status.uptime", "Аптайм: %s", "Uptime: %s", "Аптайм: %s");
        p("dlg.error", "Ошибка", "Error", "Помилка");
        p("dlg.confirm", "Подтверждение", "Confirm", "Підтвердження");
        p("dlg.choose", "Выберите папку, где находится CardLife.exe", "Select the folder containing CardLife.exe",
                "Оберіть папку, де знаходиться CardLife.exe");
        p("dlg.bad.folder", "В папке нет CardLife.exe и CardLife_Data\\Managed\\Assembly-CSharp.dll.",
                "The folder does not contain CardLife.exe and CardLife_Data\\Managed\\Assembly-CSharp.dll.",
                "У папці немає CardLife.exe та CardLife_Data\\Managed\\Assembly-CSharp.dll.");
        p("dlg.need.game", "Для запуска с игрой сначала укажите корректную папку игры (кнопка «Найти игру» или «Выбрать…»).",
                "To start with the game, first choose a valid game folder (“Detect” or “Browse…”).",
                "Для запуску з грою спочатку вкажіть правильну папку гри («Знайти гру» або «Обрати…»).");
        p("dlg.exit", "Сервер ещё работает. Остановить его и выйти?", "The server is still running. Stop it and exit?",
                "Сервер ще працює. Зупинити його та вийти?");
        p("dlg.revert", "Вернуть оригинальные Assembly-CSharp.dll и ServerConfig.json из резервных копий?",
                "Restore the original Assembly-CSharp.dll and ServerConfig.json from backups?",
                "Повернути оригінальні Assembly-CSharp.dll та ServerConfig.json з резервних копій?");
        p("dlg.save.log", "Сохранить журнал", "Save log", "Зберегти журнал");

        // ---- service messages
        p("svc.listen", "Сервер слушает http://127.0.0.1:%d/  (страница статуса: /_status)",
                "Server listening on http://127.0.0.1:%d/  (status page: /_status)",
                "Сервер слухає http://127.0.0.1:%d/  (сторінка статусу: /_status)");
        p("svc.saved", "Запросы сохраняются в: %s", "Requests are saved to: %s", "Запити зберігаються в: %s");
        p("svc.stopped", "Сервер остановлен.", "Server stopped.", "Сервер зупинено.");
        p("svc.already", "Сервер уже запущен.", "The server is already running.", "Сервер уже запущено.");
        p("svc.port.busy", "Не удалось запустить сервер на порту %d: %s. Порт занят другой программой или второй копией лаунчера.",
                "Could not start the server on port %d: %s. The port is used by another program or another launcher instance.",
                "Не вдалося запустити сервер на порту %d: %s. Порт зайнятий іншою програмою або другою копією лаунчера.");
        p("svc.game.found", "Игра найдена: %s", "Game found: %s", "Гру знайдено: %s");
        p("svc.game.notfound", "Автопоиск не нашёл CardLife — укажите папку вручную.",
                "Auto-detect did not find CardLife — choose the folder manually.",
                "Автопошук не знайшов CardLife — вкажіть папку вручну.");
        p("svc.game.badfolder", "В папке нет CardLife.exe / Assembly-CSharp.dll: %s",
                "CardLife.exe / Assembly-CSharp.dll not found in: %s",
                "У папці немає CardLife.exe / Assembly-CSharp.dll: %s");
        p("svc.backup.created", "Создан бэкап: %s", "Backup created: %s", "Створено бекап: %s");
        p("svc.backup.exists", "Бэкап уже есть, не трогаю: %s", "Backup already exists, keeping it: %s", "Бекап уже є, не чіпаю: %s");
        p("svc.cfg.patched", "ServerConfig.json: адреса → %s (изменено полей: %d)",
                "ServerConfig.json: addresses → %s (fields changed: %d)",
                "ServerConfig.json: адреси → %s (змінено полів: %d)");
        p("svc.cfg.created", "ServerConfig.json создан заново: адреса → %s", "ServerConfig.json written from scratch: addresses → %s",
                "ServerConfig.json створено заново: адреси → %s");
        p("svc.dll.patched", "Патч DLL применён. Заменено строк: %d, пропущено: %d",
                "DLL patch applied. Strings replaced: %d, skipped: %d", "Патч DLL застосовано. Замінено рядків: %d, пропущено: %d");
        p("svc.dll.none", "В DLL нет подходящих URL (уже пропатчено или маркеры не совпали).",
                "No matching URLs in the DLL (already patched or markers did not match).",
                "У DLL немає відповідних URL (вже пропатчено або маркери не збіглися).");
        p("svc.revert.dll.ok", "Assembly-CSharp.dll восстановлен из .bak.", "Assembly-CSharp.dll restored from .bak.", "Assembly-CSharp.dll відновлено з .bak.");
        p("svc.revert.dll.none", "Бэкап Assembly-CSharp.dll не найден.", "Assembly-CSharp.dll backup not found.", "Бекап Assembly-CSharp.dll не знайдено.");
        p("svc.revert.cfg.ok", "ServerConfig.json восстановлен из .bak.", "ServerConfig.json restored from .bak.", "ServerConfig.json відновлено з .bak.");
        p("svc.revert.cfg.none", "Бэкап ServerConfig.json не найден.", "ServerConfig.json backup not found.", "Бекап ServerConfig.json не знайдено.");
        p("svc.scan.result", "Найдено строк с «://»: %d (список сохранён в %s)", "Strings containing “://”: %d (saved to %s)",
                "Знайдено рядків із «://»: %d (список збережено в %s)");
        p("svc.game.started", "Игра запущена (PID %d).", "Game started (PID %d).", "Гру запущено (PID %d).");
        p("svc.game.exited", "Процесс игры завершился (код %d). Сервер продолжает работу.",
                "Game process exited (code %d). The server keeps running.", "Процес гри завершився (код %d). Сервер продовжує роботу.");
        p("svc.game.fail", "Не удалось запустить %s: %s", "Could not start %s: %s", "Не вдалося запустити %s: %s");
        p("svc.io.error", "Ошибка работы с файлами: %s. Если «Отказано в доступе» — запустите от имени администратора.",
                "File error: %s. If “Access denied” — run as administrator.",
                "Помилка роботи з файлами: %s. Якщо «Відмовлено в доступі» — запустіть від імені адміністратора.");
        p("svc.nostatus", "Сначала запустите сервер.", "Start the server first.", "Спочатку запустіть сервер.");
    }
}

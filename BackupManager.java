package cardlife;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Резервная копия Assembly-CSharp.dll.bak: создаётся один раз и никогда не перезаписывается. */
public final class BackupManager {

    private BackupManager() {
    }

    public static Path backupPath(Path dll) {
        return dll.resolveSibling(dll.getFileName() + ".bak");
    }

    /**
     * Создаёт .bak, если его ещё нет.
     *
     * @return true — бэкап создан сейчас; false — уже существовал (не тронут)
     */
    public static boolean ensureBackup(Path dll) throws IOException {
        Path bak = backupPath(dll);
        if (Files.exists(bak)) {
            return false;
        }
        Files.copy(dll, bak);
        return true;
    }

    /** Возвращает оригинал из .bak. @return false, если бэкапа нет. */
    public static boolean revert(Path dll) throws IOException {
        Path bak = backupPath(dll);
        if (!Files.isRegularFile(bak)) {
            return false;
        }
        Files.copy(bak, dll, StandardCopyOption.REPLACE_EXISTING);
        return true;
    }
}

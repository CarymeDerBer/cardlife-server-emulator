package cardlife;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Патч строк-URL прямо в байтах управляемой (.NET) сборки, без внешних библиотек и скриптов.
 *
 * <p>Принцип: .NET хранит строковые литералы кода (ldstr) в метаданных, в куче {@code #US}
 * (UTF-16LE, с префиксом длины и завершающим байтом), а литералы {@code const string}-полей —
 * в куче {@code #Blob} (UTF-16LE, с префиксом длины). Мы разбираем PE -> CLI-заголовок ->
 * метаданные, находим эти кучи, обходим записи и в подходящих URL заменяем схему+хост+порт на
 * {@link #LOCAL_BASE}, сохраняя путь.
 *
 * <p>Ограничение: замена делается «на месте», поэтому новая строка должна быть не длиннее старой
 * (префикс длины укорачивается, хвост забивается нулями — это корректные пустые записи кучи).
 * Адреса вида {@code https://api.cardlifegame.com/...} длиннее {@code http://127.0.0.1:8080/...},
 * так что обычно проблем нет; слишком короткие URL помечаются как SKIP.
 */
public final class DllStringPatcher {

    public static final String LOCAL_BASE = "http://127.0.0.1:8080";
    public static final List<String> DEFAULT_MARKERS = List.of("cardlife", "freejam", "fjgames");

    private static final Pattern URL =
            Pattern.compile("^(https?://)([^/:\\s]+)(:\\d+)?(/.*)?$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private DllStringPatcher() {
    }

    public record Result(int replaced, int skipped, List<String> details) {
    }

    private record Heap(String name, int start, int size) {
    }

    /** Найденная запись кучи, содержащая "://". */
    private record Hit(String heap, int relOffset, int lenPos, int width, int dataStart,
                       int charBytes, boolean us, String value) {
    }

    // ------------------------------------------------------------------ public API

    /** Все строки с "://" из #US и #Blob — чтобы увидеть реальные адреса серверов игры. */
    public static List<String> scan(Path dll) throws IOException {
        List<String> out = new ArrayList<>();
        for (Hit h : collect(Files.readAllBytes(dll))) {
            out.add(String.format("%s+0x%X  %s", h.heap(), h.relOffset(), h.value()));
        }
        return out;
    }

    /** Заменяет URL с хостами-маркерами на локальный сервер и записывает DLL обратно (если что-то менялось). */
    public static Result patch(Path dll, Collection<String> markers) throws IOException {
        byte[] data = Files.readAllBytes(dll);
        int replaced = 0;
        int skipped = 0;
        List<String> details = new ArrayList<>();

        for (Hit h : collect(data)) {
            Matcher m = URL.matcher(h.value());
            if (!m.matches()) {
                continue;
            }
            String host = m.group(2).toLowerCase(Locale.ROOT);
            if (host.equals("127.0.0.1") || host.equals("localhost")) {
                continue; // уже пропатчено
            }
            boolean ipHost = IPV4.matcher(host).matches(); // голые IP (игровые серверы) патчим всегда
            if (!ipHost && markers.stream().noneMatch(k -> host.contains(k.toLowerCase(Locale.ROOT)))) {
                continue;
            }
            String rest = m.group(4) == null ? "" : m.group(4);
            String replacement = LOCAL_BASE + rest;
            byte[] nb = replacement.getBytes(StandardCharsets.UTF_16LE);

            if (nb.length > h.charBytes()) {
                skipped++;
                details.add(String.format("SKIP %s+0x%X (new string longer than original): %s", h.heap(), h.relOffset(), h.value()));
                continue;
            }
            rewrite(data, h, nb);
            replaced++;
            details.add(String.format("%s+0x%X: %s -> %s", h.heap(), h.relOffset(), h.value(), replacement));
        }

        if (replaced > 0) {
            Path tmp = dll.resolveSibling(dll.getFileName() + ".patched.tmp");
            Files.write(tmp, data);
            Files.move(tmp, dll, StandardCopyOption.REPLACE_EXISTING);
        }
        return new Result(replaced, skipped, details);
    }

    // ------------------------------------------------------------------ rewrite

    private static void rewrite(byte[] a, Hit h, byte[] newChars) {
        int origLen = h.charBytes() + (h.us() ? 1 : 0);
        int dataEnd = h.dataStart() + origLen;
        byte terminal = h.us() ? a[dataEnd - 1] : 0;
        int newLen = newChars.length + (h.us() ? 1 : 0);

        writeCompressed(a, h.lenPos(), h.width(), newLen); // ширина префикса остаётся прежней
        System.arraycopy(newChars, 0, a, h.dataStart(), newChars.length);
        int p = h.dataStart() + newChars.length;
        if (h.us()) {
            a[p++] = terminal;
        }
        Arrays.fill(a, p, dataEnd, (byte) 0);
    }

    private static void writeCompressed(byte[] a, int pos, int width, int v) {
        switch (width) {
            case 1 -> a[pos] = (byte) v;
            case 2 -> {
                a[pos] = (byte) (0x80 | (v >> 8));
                a[pos + 1] = (byte) v;
            }
            default -> {
                a[pos] = (byte) (0xC0 | (v >> 24));
                a[pos + 1] = (byte) (v >> 16);
                a[pos + 2] = (byte) (v >> 8);
                a[pos + 3] = (byte) v;
            }
        }
    }

    // ------------------------------------------------------------------ heap walking

    private static List<Hit> collect(byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        List<Hit> hits = new ArrayList<>();

        for (Heap heap : readHeaps(b)) {
            boolean us = heap.name().equals("#US");
            if (!us && !heap.name().equals("#Blob")) {
                continue;
            }
            int pos = heap.start() + 1; // запись с индексом 0 — пустая
            int end = heap.start() + heap.size();

            while (pos < end) {
                int b0 = data[pos] & 0xFF;
                int width;
                int len;
                if ((b0 & 0x80) == 0) {
                    width = 1;
                    len = b0;
                } else if ((b0 & 0xC0) == 0x80) {
                    if (pos + 1 >= end) break;
                    width = 2;
                    len = ((b0 & 0x3F) << 8) | (data[pos + 1] & 0xFF);
                } else if ((b0 & 0xE0) == 0xC0) {
                    if (pos + 3 >= end) break;
                    width = 4;
                    len = ((b0 & 0x1F) << 24) | ((data[pos + 1] & 0xFF) << 16)
                            | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                } else {
                    break; // рассинхрон — дальше идти небезопасно
                }

                int dataStart = pos + width;
                int dataEnd = dataStart + len;
                if (dataEnd > end) {
                    break;
                }
                int charBytes = us ? len - 1 : len;

                // минимум "http://x" = 8 символов; UTF-16 => чётное число байт, второй байт первого символа = 0
                if (len > 0 && charBytes >= 16 && (charBytes & 1) == 0 && data[dataStart + 1] == 0) {
                    String s = new String(data, dataStart, charBytes, StandardCharsets.UTF_16LE);
                    if (s.contains("://") && isPrintable(s)) {
                        hits.add(new Hit(heap.name(), pos - heap.start(), pos, width, dataStart, charBytes, us, s));
                    }
                }
                pos = dataEnd;
            }
        }
        return hits;
    }

    private static boolean isPrintable(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ PE / CLI metadata parsing

    private static List<Heap> readHeaps(ByteBuffer b) throws IOException {
        try {
            int pe = b.getInt(0x3C);
            if (b.getInt(pe) != 0x00004550) { // "PE\0\0"
                throw new IOException("Не PE-файл");
            }
            int sections = b.getShort(pe + 6) & 0xFFFF;
            int optSize = b.getShort(pe + 20) & 0xFFFF;
            int opt = pe + 24;
            int magic = b.getShort(opt) & 0xFFFF;
            int dataDirs = opt + (magic == 0x20B ? 112 : 96);
            int cliRva = b.getInt(dataDirs + 14 * 8); // IMAGE_DIRECTORY_ENTRY_COM_DESCRIPTOR
            if (cliRva == 0) {
                throw new IOException("Это не управляемая (.NET) сборка");
            }
            int sectionTable = opt + optSize;

            int cli = rvaToOffset(b, sectionTable, sections, cliRva);
            int mdRva = b.getInt(cli + 8);
            int md = rvaToOffset(b, sectionTable, sections, mdRva);
            if (b.getInt(md) != 0x424A5342) { // "BSJB"
                throw new IOException("Не найден корень метаданных (BSJB)");
            }
            int verLen = b.getInt(md + 12);
            int p = md + 16 + verLen;
            int streams = b.getShort(p + 2) & 0xFFFF;
            p += 4;

            List<Heap> heaps = new ArrayList<>();
            for (int i = 0; i < streams; i++) {
                int off = b.getInt(p);
                int size = b.getInt(p + 4);
                p += 8;
                int nameStart = p;
                while (b.get(p) != 0) {
                    p++;
                }
                String name = new String(b.array(), nameStart, p - nameStart, StandardCharsets.US_ASCII);
                p = nameStart + (((p - nameStart) + 1 + 3) & ~3);
                heaps.add(new Heap(name, md + off, size));
            }
            return heaps;
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Повреждённый или неожиданный формат сборки", e);
        }
    }

    private static int rvaToOffset(ByteBuffer b, int sectionTable, int count, int rva) throws IOException {
        for (int i = 0; i < count; i++) {
            int s = sectionTable + i * 40;
            int virtualSize = b.getInt(s + 8);
            int virtualAddr = b.getInt(s + 12);
            int rawSize = b.getInt(s + 16);
            int rawPtr = b.getInt(s + 20);
            if (rva >= virtualAddr && rva < virtualAddr + Math.max(virtualSize, rawSize)) {
                return rva - virtualAddr + rawPtr;
            }
        }
        throw new IOException("RVA 0x" + Integer.toHexString(rva) + " не принадлежит ни одной секции");
    }
}
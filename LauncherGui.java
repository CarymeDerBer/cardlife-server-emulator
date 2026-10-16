package cardlife;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Графический лаунчер: выбор папки игры/порта, запуск сервера и игры, живой журнал и таблица запросов.
 * FlatLaf подключается через reflection — если библиотека не скачалась, окно работает со стандартным видом.
 */
public final class LauncherGui {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_LINES = 5000;
    private static final int MAX_TABLE_ROWS = 1000;

    // ---- state
    private final AppSettings settings = AppSettings.load();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "launcher-worker");
        t.setDaemon(true);
        return t;
    });
    private final LauncherService svc;
    private boolean busy;
    private boolean dark;

    // ---- widgets
    private final JFrame frame = new JFrame();
    private final JLabel statusLabel = new JLabel();
    private final DotIcon dot = new DotIcon();
    private final JLabel langLabel = new JLabel();
    private final JComboBox<I18n.Lang> language = new JComboBox<>(I18n.Lang.values());
    private final JCheckBox darkBox = new JCheckBox();
    private final JLabel pathLabel = new JLabel();
    private final JTextField gamePath = new JTextField();
    private final JButton browse = new JButton();
    private final JButton detect = new JButton();
    private final JLabel portLabel = new JLabel();
    private final JSpinner portSpinner = new JSpinner(new SpinnerNumberModel(LauncherService.GAME_PORT, 1024, 65535, 1));
    private final JLabel portHint = new JLabel();
    private final JButton btnServer = new JButton();
    private final JButton btnBoth = new JButton();
    private final JButton btnStop = new JButton();
    private final JButton btnTools = new JButton();
    private final JTabbedPane tabs = new JTabbedPane();
    private final JTextPane logPane = new JTextPane();
    private final JButton logClear = new JButton();
    private final JButton logCopy = new JButton();
    private final JButton logSave = new JButton();
    private final JCheckBox autoScroll = new JCheckBox();
    private final RequestModel requestModel = new RequestModel();
    private final JTable requestTable = new JTable(requestModel);
    private final JTextArea requestDetail = new JTextArea();
    private final JLabel statsLabel = new JLabel();

    private LauncherGui() {
        I18n.setLang(I18n.Lang.fromCode(settings.get("lang", "RU")));
        dark = settings.getBool("dark", true);
        svc = new LauncherService(this::onLog, this::onRequest, Paths.get("").toAbsolutePath());
        buildUi();
        loadSettings();
        applyLanguage();
        refreshState();

        new javax.swing.Timer(1000, e -> refreshStats()).start();
    }

    public static void open() {
        SwingUtilities.invokeLater(() -> {
            UIManager.put("Button.arc", 10);
            UIManager.put("Component.arc", 10);
            UIManager.put("TextComponent.arc", 8);
            UIManager.put("ScrollBar.thumbArc", 999);
            UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
            UIManager.put("TabbedPane.showTabSeparators", true);
            boolean d = AppSettings.load().getBool("dark", true);
            applyLaf(d);
            LauncherGui gui = new LauncherGui();
            gui.frame.setVisible(true);
            gui.autoDetectIfEmpty();
        });
    }

    private static void applyLaf(boolean dark) {
        try {
            UIManager.setLookAndFeel(dark ? "com.formdev.flatlaf.FlatDarkLaf" : "com.formdev.flatlaf.FlatLightLaf");
        } catch (Exception flatMissing) {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // остаётся стандартный Metal
            }
        }
    }

    // ------------------------------------------------------------------ UI construction

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(820, 560));
        frame.setSize(980, 680);
        frame.setLocationRelativeTo(null);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(new EmptyBorder(14, 16, 12, 16));

        // header: status + language/theme, game folder, port
        JPanel header = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        statusLabel.setIcon(dot);
        statusLabel.setIconTextGap(10);
        statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 16f));
        c.gridx = 0; c.gridy = 0; c.gridwidth = 2; c.weightx = 1;
        header.add(statusLabel, c);
        JPanel langBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        langBox.add(langLabel);
        langBox.add(language);
        langBox.add(darkBox);
        c.gridx = 2; c.gridwidth = 2; c.weightx = 0;
        header.add(langBox, c);

        c.gridwidth = 1;
        c.gridx = 0; c.gridy = 1; c.weightx = 0;
        header.add(pathLabel, c);
        c.gridx = 1; c.weightx = 1;
        header.add(gamePath, c);
        c.gridx = 2; c.weightx = 0;
        header.add(browse, c);
        c.gridx = 3;
        header.add(detect, c);

        c.gridx = 0; c.gridy = 2;
        header.add(portLabel, c);
        JPanel portRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        portSpinner.setEditor(new JSpinner.NumberEditor(portSpinner, "#"));
        ((JSpinner.DefaultEditor) portSpinner.getEditor()).getTextField().setColumns(6);
        portRow.add(portSpinner);
        portRow.add(portHint);
        c.gridx = 1; c.gridwidth = 3; c.weightx = 1;
        header.add(portRow, c);
        root.add(header, BorderLayout.NORTH);

        // log tab
        logPane.setEditable(false);
        logPane.setFont(monoFont());
        JPanel logPanel = new JPanel(new BorderLayout(0, 6));
        logPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        logPanel.add(new JScrollPane(logPane), BorderLayout.CENTER);
        JPanel logBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        logBar.add(logClear);
        logBar.add(logCopy);
        logBar.add(logSave);
        logBar.add(autoScroll);
        autoScroll.setSelected(true);
        logPanel.add(logBar, BorderLayout.SOUTH);

        // requests tab
        requestTable.setFillsViewportHeight(true);
        requestTable.setRowHeight(24);
        requestTable.setShowVerticalLines(false);
        setupRequestColumns();
        requestTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showRequestDetail();
            }
        });
        requestDetail.setEditable(false);
        requestDetail.setFont(monoFont());
        requestDetail.setLineWrap(true);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(requestTable), new JScrollPane(requestDetail));
        split.setResizeWeight(0.7);
        split.setBorder(null);
        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.setBorder(new EmptyBorder(8, 0, 0, 0));
        reqPanel.add(split, BorderLayout.CENTER);

        tabs.addTab("", logPanel);
        tabs.addTab("", reqPanel);
        root.add(tabs, BorderLayout.CENTER);

        // bottom: buttons + stats
        JPanel bottom = new JPanel(new BorderLayout(0, 8));
        JPanel buttons = new JPanel(new BorderLayout());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        for (JButton b : new JButton[]{btnServer, btnBoth, btnStop}) {
            b.setPreferredSize(new Dimension(170, 36));
            left.add(b);
        }
        btnBoth.putClientProperty("JButton.buttonType", "default");
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        btnTools.setPreferredSize(new Dimension(170, 36));
        right.add(btnTools);
        buttons.add(left, BorderLayout.WEST);
        buttons.add(right, BorderLayout.EAST);
        bottom.add(buttons, BorderLayout.CENTER);
        bottom.add(statsLabel, BorderLayout.SOUTH);
        refreshMutedColors();
        root.add(bottom, BorderLayout.SOUTH);
        frame.setContentPane(root);

        // actions
        language.addActionListener(e -> {
            I18n.setLang((I18n.Lang) language.getSelectedItem());
            applyLanguage();
            saveSettings();
        });
        darkBox.addActionListener(e -> {
            dark = darkBox.isSelected();
            applyLaf(dark);
            SwingUtilities.updateComponentTreeUI(frame);
            logPane.setFont(monoFont());
            refreshMutedColors();
            saveSettings();
        });
        browse.addActionListener(e -> chooseGame());
        detect.addActionListener(e -> runAsync(this::detectGame));
        btnServer.addActionListener(e -> start(false));
        btnBoth.addActionListener(e -> start(true));
        btnStop.addActionListener(e -> runAsync(() -> {
            svc.stopServer();
            return null;
        }));
        btnTools.addActionListener(e -> showToolsMenu());
        logClear.addActionListener(e -> logPane.setText(""));
        logCopy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(logPane.getText()), null));
        logSave.addActionListener(e -> saveLog());
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                onClose();
            }
        });
    }

    private void refreshMutedColors() {
        Color muted = UIManager.getColor("Label.disabledForeground");
        portHint.setForeground(muted);
        statsLabel.setForeground(muted);
    }

    private void setupRequestColumns() {
        requestTable.getColumnModel().getColumn(0).setMaxWidth(60);
        requestTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        requestTable.getColumnModel().getColumn(2).setPreferredWidth(70);
        requestTable.getColumnModel().getColumn(3).setPreferredWidth(420);
        requestTable.getColumnModel().getColumn(4).setPreferredWidth(60);
        requestTable.getColumnModel().getColumn(5).setPreferredWidth(50);
        requestTable.getColumnModel().getColumn(6).setPreferredWidth(70);
        requestTable.getColumnModel().getColumn(4).setCellRenderer(new StatusRenderer());
    }

    private static Font monoFont() {
        Font f = new Font("Consolas", Font.PLAIN, 13);
        return f.getFamily().equalsIgnoreCase("Consolas") ? f : new Font(Font.MONOSPACED, Font.PLAIN, 13);
    }

    // ------------------------------------------------------------------ localisation / state

    private void applyLanguage() {
        frame.setTitle(I18n.t("app.title"));
        langLabel.setText(I18n.t("lang"));
        darkBox.setText(I18n.t("theme.dark"));
        pathLabel.setText(I18n.t("game.folder"));
        browse.setText(I18n.t("browse"));
        detect.setText(I18n.t("detect"));
        portLabel.setText(I18n.t("port"));
        portHint.setText(I18n.t("port.hint"));
        btnServer.setText(I18n.t("btn.server"));
        btnBoth.setText(I18n.t("btn.both"));
        btnStop.setText(I18n.t("btn.stop"));
        btnTools.setText(I18n.t("btn.tools"));
        tabs.setTitleAt(0, I18n.t("tab.log"));
        tabs.setTitleAt(1, I18n.t("tab.requests"));
        logClear.setText(I18n.t("log.clear"));
        logCopy.setText(I18n.t("log.copy"));
        logSave.setText(I18n.t("log.save"));
        autoScroll.setText(I18n.t("log.autoscroll"));
        requestModel.fireTableStructureChanged();
        setupRequestColumns();
        refreshState();
        refreshStats();
    }

    private void refreshState() {
        boolean running = svc.isServerRunning();
        MockAuthServer s = svc.server();
        String game = svc.isGameRunning() ? I18n.t("status.game") : "";
        if (busy) {
            dot.color = new Color(0xE0A030);
            statusLabel.setText(I18n.t("status.busy"));
        } else if (running && s != null) {
            dot.color = new Color(0x4CAF50);
            statusLabel.setText(I18n.t("status.running", s.port()) + game);
        } else {
            dot.color = new Color(0x888888);
            statusLabel.setText(I18n.t("status.stopped") + game);
        }
        btnServer.setEnabled(!running && !busy);
        btnBoth.setEnabled(!running && !busy);
        btnStop.setEnabled(running && !busy);
        btnTools.setEnabled(!busy);
        portSpinner.setEnabled(!running && !busy);
        gamePath.setEnabled(!busy);
        browse.setEnabled(!busy);
        detect.setEnabled(!busy);
        statusLabel.repaint();
    }

    private void refreshStats() {
        MockAuthServer s = svc.server();
        if (s != null && s.isRunning()) {
            statsLabel.setText(I18n.t("status.requests", s.totalRequests()) + "   ·   "
                    + I18n.t("status.uptime", MockAuthServer.formatDuration(s.uptime())));
        } else {
            statsLabel.setText(" ");
        }
        refreshState();
    }

    private void loadSettings() {
        gamePath.setText(settings.get("game", ""));
        portSpinner.setValue(Math.max(1024, Math.min(65535, settings.getInt("port", LauncherService.GAME_PORT))));
        language.setSelectedItem(I18n.lang());
        darkBox.setSelected(dark);
    }

    private void saveSettings() {
        settings.set("lang", I18n.lang().name());
        settings.set("game", gamePath.getText().trim());
        settings.set("port", String.valueOf(portSpinner.getValue()));
        settings.set("dark", String.valueOf(dark));
        settings.save();
    }

    // ------------------------------------------------------------------ logging & requests

    private void onLog(LauncherService.Level level, String message) {
        String line = LocalTime.now().format(TIME) + "  " + message + "\n";
        SwingUtilities.invokeLater(() -> appendLog(level, line));
    }

    private void appendLog(LauncherService.Level level, String line) {
        StyledDocument doc = logPane.getStyledDocument();
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        switch (level) {
            case ERROR -> StyleConstants.setForeground(attrs, new Color(0xE05555));
            case WARN -> StyleConstants.setForeground(attrs, new Color(0xE0A030));
            case OK -> StyleConstants.setForeground(attrs, new Color(0x4CAF50));
            default -> {
                Color fg = UIManager.getColor("TextPane.foreground");
                StyleConstants.setForeground(attrs, fg != null ? fg : logPane.getForeground());
            }
        }
        try {
            doc.insertString(doc.getLength(), line, attrs);
            if (doc.getDefaultRootElement().getElementCount() > MAX_LOG_LINES) {
                int cut = doc.getDefaultRootElement().getElement(1000).getStartOffset();
                doc.remove(0, cut);
            }
        } catch (BadLocationException ignored) {
            // документ изменился — пропускаем строку
        }
        if (autoScroll.isSelected()) {
            logPane.setCaretPosition(doc.getLength());
        }
    }

    private void onRequest(MockAuthServer.RequestRecord rec) {
        SwingUtilities.invokeLater(() -> {
            requestModel.add(rec);
            if (requestTable.getSelectedRow() < 0 && autoScroll.isSelected()) {
                requestTable.scrollRectToVisible(requestTable.getCellRect(requestModel.getRowCount() - 1, 0, true));
            }
        });
    }

    private void showRequestDetail() {
        int row = requestTable.getSelectedRow();
        if (row < 0 || row >= requestModel.getRowCount()) {
            requestDetail.setText("");
            return;
        }
        MockAuthServer.RequestRecord r = requestModel.get(row);
        requestDetail.setText(r.method() + " " + r.path() + (r.query() != null ? "?" + r.query() : "")
                + "\n→ " + r.status() + "  (" + r.source() + ", " + r.millis() + " ms, " + r.bytes() + " B)\n\n"
                + r.body());
        requestDetail.setCaretPosition(0);
    }

    private void saveLog() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle(I18n.t("dlg.save.log"));
        fc.setSelectedFile(new java.io.File("cardlife-log.txt"));
        if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            try {
                Files.writeString(fc.getSelectedFile().toPath(), logPane.getText(), StandardCharsets.UTF_8);
            } catch (IOException ex) {
                error(ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ actions

    private void chooseGame() {
        String cur = gamePath.getText().trim();
        JFileChooser fc = new JFileChooser(cur.isEmpty() ? null : new java.io.File(cur));
        fc.setDialogTitle(I18n.t("dlg.choose"));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path p = fc.getSelectedFile().toPath();
            if (GameLocator.isValidGameDir(p)) {
                gamePath.setText(p.toAbsolutePath().toString());
                saveSettings();
            } else {
                JOptionPane.showMessageDialog(frame, I18n.t("dlg.bad.folder"), I18n.t("dlg.error"), JOptionPane.WARNING_MESSAGE);
            }
        }
    }

    private void autoDetectIfEmpty() {
        String cur = gamePath.getText().trim();
        boolean valid = false;
        try {
            valid = !cur.isEmpty() && GameLocator.isValidGameDir(Paths.get(cur));
        } catch (RuntimeException ignored) {
            // некорректный сохранённый путь
        }
        if (!valid) {
            runAsync(this::detectGame);
        }
    }

    private Void detectGame() {
        Optional<Path> p = svc.findGame();
        p.ifPresent(path -> SwingUtilities.invokeLater(() -> {
            gamePath.setText(path.toAbsolutePath().toString());
            saveSettings();
        }));
        return null;
    }

    private Path validatedGame() {
        String text = gamePath.getText().trim().replace("\"", "");
        try {
            Path p = Paths.get(text);
            if (!text.isEmpty() && GameLocator.isValidGameDir(p)) {
                return p;
            }
        } catch (RuntimeException ignored) {
            // некорректный путь
        }
        JOptionPane.showMessageDialog(frame, I18n.t("dlg.need.game"), I18n.t("dlg.error"), JOptionPane.WARNING_MESSAGE);
        return null;
    }

    private void start(boolean withGame) {
        Path game = null;
        if (withGame) {
            game = validatedGame();
            if (game == null) {
                return;
            }
        }
        saveSettings();
        final Path g = game;
        final int port = withGame ? LauncherService.GAME_PORT : (Integer) portSpinner.getValue();
        runAsync(() -> {
            if (g != null) {
                svc.applyPatch(g, DllStringPatcher.DEFAULT_MARKERS);
            }
            svc.startServer(port);
            if (g != null) {
                svc.launchGame(g);
            }
            return null;
        });
    }

    private void showToolsMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.add(menuItem(I18n.t("tool.status"), e -> openStatusPage()));
        menu.add(menuItem(I18n.t("tool.open.resp"), e -> openFolder(svc.baseDir().resolve("responses"))));
        menu.add(menuItem(I18n.t("tool.open.over"), e -> openFolder(svc.baseDir().resolve("overrides"))));
        menu.add(menuItem(I18n.t("tool.open.game"), e -> {
            String t = gamePath.getText().trim();
            if (!t.isEmpty()) {
                openFolder(Paths.get(t));
            }
        }));
        menu.addSeparator();
        menu.add(menuItem(I18n.t("tool.scan.orig"), e -> scan(true)));
        menu.add(menuItem(I18n.t("tool.scan.cur"), e -> scan(false)));
        menu.addSeparator();
        menu.add(menuItem(I18n.t("tool.revert"), e -> revert()));
        menu.show(btnTools, 0, -menu.getPreferredSize().height);
    }

    private static JMenuItem menuItem(String text, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(text);
        mi.addActionListener(l);
        return mi;
    }

    private void scan(boolean original) {
        Path game = validatedGame();
        if (game != null) {
            runAsync(() -> {
                svc.scan(game, original);
                return null;
            });
        }
    }

    private void revert() {
        Path game = validatedGame();
        if (game == null) {
            return;
        }
        if (JOptionPane.showConfirmDialog(frame, I18n.t("dlg.revert"), I18n.t("dlg.confirm"),
                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            runAsync(() -> {
                svc.revert(game);
                return null;
            });
        }
    }

    private void openFolder(Path dir) {
        try {
            Files.createDirectories(dir);
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(dir.toFile());
            }
        } catch (IOException | RuntimeException ex) {
            error(String.valueOf(ex.getMessage()));
        }
    }

    private void openStatusPage() {
        MockAuthServer s = svc.server();
        if (s == null || !s.isRunning()) {
            svc.log(LauncherService.Level.WARN, I18n.t("svc.nostatus"));
            return;
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create("http://127.0.0.1:" + s.port() + "/_status"));
            }
        } catch (IOException | RuntimeException ex) {
            error(String.valueOf(ex.getMessage()));
        }
    }

    // ------------------------------------------------------------------ background tasks

    @FunctionalInterface
    private interface Task {
        Void run() throws Exception;
    }

    private void runAsync(Task task) {
        busy = true;
        refreshState();
        worker.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : String.valueOf(e);
                if (e instanceof IOException && !(e.getMessage() != null && e.getCause() != null)) {
                    msg = I18n.t("svc.io.error", msg);
                }
                final String shown = msg;
                svc.log(LauncherService.Level.ERROR, shown);
                SwingUtilities.invokeLater(() -> error(shown));
            } finally {
                SwingUtilities.invokeLater(() -> {
                    busy = false;
                    refreshState();
                });
            }
        });
    }

    private void error(String msg) {
        JOptionPane.showMessageDialog(frame, msg, I18n.t("dlg.error"), JOptionPane.ERROR_MESSAGE);
    }

    private void onClose() {
        if (svc.isServerRunning()) {
            int answer = JOptionPane.showConfirmDialog(frame, I18n.t("dlg.exit"), I18n.t("dlg.confirm"), JOptionPane.YES_NO_OPTION);
            if (answer != JOptionPane.YES_OPTION) {
                return;
            }
        }
        saveSettings();
        svc.close();
        frame.dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ small components

    private static final class DotIcon implements Icon {
        Color color = Color.GRAY;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillOval(x, y, 14, 14);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        StatusRenderer() {
            setHorizontalAlignment(CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int r, int col) {
            Component c = super.getTableCellRendererComponent(t, v, sel, focus, r, col);
            if (!sel && v instanceof Integer code) {
                c.setForeground(code >= 400 ? new Color(0xE05555) : new Color(0x4CAF50));
            }
            return c;
        }
    }

    private static final class RequestModel extends AbstractTableModel {
        private final List<MockAuthServer.RequestRecord> rows = new ArrayList<>();

        void add(MockAuthServer.RequestRecord r) {
            rows.add(r);
            if (rows.size() > MAX_TABLE_ROWS) {
                rows.subList(0, 100).clear();
                fireTableDataChanged();
            } else {
                fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            }
        }

        MockAuthServer.RequestRecord get(int row) {
            return rows.get(row);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 7;
        }

        @Override
        public String getColumnName(int c) {
            return switch (c) {
                case 0 -> "#";
                case 1 -> I18n.t("col.time");
                case 2 -> I18n.t("col.method");
                case 3 -> I18n.t("col.path");
                case 4 -> I18n.t("col.status");
                case 5 -> I18n.t("col.ms");
                default -> I18n.t("col.size");
            };
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return (c == 0 || c == 4 || c == 5 || c == 6) ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int r, int c) {
            MockAuthServer.RequestRecord q = rows.get(r);
            return switch (c) {
                case 0 -> (int) q.id();
                case 1 -> q.time().format(TIME);
                case 2 -> q.method();
                case 3 -> q.path() + (q.query() != null ? "?" + q.query() : "");
                case 4 -> q.status();
                case 5 -> (int) q.millis();
                default -> q.bytes();
            };
        }
    }
}

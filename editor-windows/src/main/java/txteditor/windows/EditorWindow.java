package txteditor.windows;

import txteditor.core.EditorModel;
import txteditor.core.SessionSnapshot;
import txteditor.core.TextKeys;
import txteditor.core.text.LanguageId;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.Element;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import static txteditor.windows.EditorIcon.Kind.*;

public final class EditorWindow extends JFrame {
    private static final int RECENT_LIMIT = 5;
    private static Properties cachedPrefs;
    final EditorModel model = new EditorModel();
    /**
     * JTextPane 默认不折行；视口宽度跟随即恢复原来 setLineWrap(true) 的效果。
     * shortcut: 折行按字符而非单词（JTextPane 没有 setWrapStyleWord），等有人抱怨断词再写 View。
     */
    final JTextPane area = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
    };
    final MarkdownPreview preview = new MarkdownPreview();
    final JTextField query = new JTextField(14);
    final JTextField replacement = new JTextField(14);
    final JLabel status = new JLabel(" ");
    final JPanel searchPanel = new JPanel(new BorderLayout(0, 8));
    LanguageId language = LanguageId.PLAIN;
    boolean previewMode;
    private final JPanel replaceRow = new JPanel(new BorderLayout(10, 0));
    private final JLabel documentName = new JLabel(), documentPath = new JLabel(), savedState = new JLabel();
    private final JLabel searchResult = new JLabel("区分大小写 · 按字面查找 · 末尾自动回绕");
    private final JLabel position = new JLabel();
    private final JButton zoomLabel = new JButton("100%");
    private final JMenu recentMenu = new JMenu("最近文件");
    private final Map<LanguageId, JRadioButtonMenuItem> languageItems = new EnumMap<LanguageId, JRadioButtonMenuItem>(LanguageId.class);
    private final List<Path> recent = new ArrayList<Path>();
    private final Properties prefs;
    private final Timer sessionTimer = new Timer(700, event -> persistSession());
    final JPanel documentTop = surface(new JPanel(new BorderLayout()));
    final JPanel searchDock = surface(new JPanel(new BorderLayout()));
    final EditorBinding binding;
    private final JScrollPane scroll;
    private final CardLayout cards = new CardLayout();
    private final JPanel stage = new JPanel(cards);
    private final List<Action> actions = new ArrayList<Action>();
    private final List<JComponent> searchControls = new ArrayList<JComponent>();
    private Action undoAction, redoAction, nextAction, replaceAction, zoomInAction, zoomOutAction, themeAction, previewAction;
    private JToggleButton previewToggle;
    private JRadioButtonMenuItem themeSystem, themeLight, themeDark;
    private JButton searchToggle;
    private JPanel heading, fileHeader, shell, footer;
    private Path file;
    private boolean busy;
    private boolean readOnly;
    private boolean restored;
    private boolean docked;
    private int zoom = 100;
    private long fileModified;
    private long fileSize;
    private String themeMode = "system";

    public EditorWindow() {
        super("文本编辑器");
        prefs = preferences();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(UIScale.scale(new Dimension(1080, 780)));
        setMinimumSize(UIScale.scale(new Dimension(720, 520)));
        Rectangle saved = savedBounds();
        if (saved == null) { setLocationByPlatform(true); setLocationRelativeTo(null); }
        else { setSize(saved.width, saved.height); setLocation(saved.x, saved.y); }
        area.setFont(javax.swing.text.StyleContext.getDefaultStyleContext()
                .getFont("Microsoft YaHei UI", Font.PLAIN, 18).deriveFont(UIScale.scale(18f)));
        // JTextPane 没有 setMargin；边框留白等效，BasicTextUI 用 getInsets() 排版正文。
        area.setBorder(BorderFactory.createEmptyBorder(22, 26, 22, 26));
        area.getAccessibleContext().setAccessibleName("纯文本编辑区");
        query.getAccessibleContext().setAccessibleName("查找词，区分大小写");
        replacement.getAccessibleContext().setAccessibleName("替换文本");
        binding = new EditorBinding(area, model, this::documentChanged, this::showError);
        JMenuBar menus = new JMenuBar();
        JMenu files = new JMenu("文件"); files.setMnemonic(KeyEvent.VK_F);
        // 新建先选类型：决定默认后缀与高亮语言，Ctrl+N 仍直接建纯文本。
        JMenu newFile = new JMenu("新建");
        for (final LanguageId type : LanguageId.values())
            addAction(newFile, type.title + "（" + type.defaultSuffix + "）",
                    type == LanguageId.PLAIN ? "control N" : null, () -> leave(() -> newDocument(type)));
        files.add(newFile);
        Action newAction = (Action) newFile.getItem(0).getAction();
        Action openAction = addAction(files, "打开…", "control O", () -> leave(this::chooseOpen));
        files.add(recentMenu);
        Action saveAction = addAction(files, "保存", "control S", () -> save(false, () -> { }));
        addAction(files, "另存为…", "control shift S", () -> save(true, () -> { }));
        files.addSeparator(); addAction(files, "退出", "alt F4", () -> leave(this::shutdown));
        JMenu edit = new JMenu("编辑"); edit.setMnemonic(KeyEvent.VK_E);
        undoAction = addAction(edit, "撤销", "control Z", () -> { binding.finishInput(); model.undo(); binding.refresh(); area.requestFocusInWindow(); });
        redoAction = addAction(edit, "重做", "control Y", () -> { binding.finishInput(); model.redo(); binding.refresh(); area.requestFocusInWindow(); });
        edit.addSeparator();
        addAction(edit, "剪切", "control X", () -> focusedText().cut());
        addAction(edit, "复制", "control C", () -> focusedText().copy());
        addAction(edit, "粘贴", "control V", () -> focusedText().paste());
        addAction(edit, "全选", "control A", () -> focusedText().selectAll());
        JMenu searchMenu = new JMenu("查找");
        addAction(searchMenu, "查找…", "control F", () -> showSearch(false));
        nextAction = addAction(searchMenu, "下一处", "F3", this::find);
        Action showReplace = addAction(searchMenu, "替换…", "control H", () -> showSearch(true));
        replaceAction = action("替换当前", null, this::replaceCurrent);
        Action closeSearch = action("关闭查找", "ESCAPE", this::closeSearch);
        JMenu view = new JMenu("视图");
        ButtonGroup modes = new ButtonGroup();
        themeSystem = themeItem(view, modes, "跟随系统", "system");
        themeLight = themeItem(view, modes, "浅色", "light");
        themeDark = themeItem(view, modes, "深色", "dark");
        view.addSeparator();
        zoomInAction = addAction(view, "放大", "control EQUALS", () -> setZoom(zoom + 10));
        zoomOutAction = addAction(view, "缩小", "control MINUS", () -> setZoom(zoom - 10));
        Action resetZoom = addAction(view, "重置缩放", "control 0", () -> setZoom(100));
        view.addSeparator();
        previewAction = addAction(view, "预览 Markdown", "control E", () -> setPreview(!previewMode));
        view.addSeparator();
        JMenu languageMenu = new JMenu("语言");
        ButtonGroup languageGroup = new ButtonGroup();
        for (LanguageId id : LanguageId.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(id.title);
            item.setSelected(id == language);
            item.addActionListener(event -> { if (!busy) adoptLanguage(id); });
            languageGroup.add(item); languageMenu.add(item);
            languageItems.put(id, item);
        }
        view.add(languageMenu);
        themeAction = action("主题：跟随系统", null, this::cycleTheme);
        menus.add(files); menus.add(edit); menus.add(searchMenu); menus.add(view); setJMenuBar(menus);

        shell = new JPanel(new BorderLayout(0, 14));
        shell.setBorder(BorderFactory.createEmptyBorder(18, 20, 0, 20));
        heading = new JPanel(new BorderLayout(16, 0));
        JPanel brand = new JPanel(new BorderLayout(12, 0));
        JLabel mark = new JLabel("文", SwingConstants.CENTER);
        mark.setOpaque(true); mark.setBackground(new Color(0x2563EB)); mark.setForeground(Color.WHITE);
        mark.setFont(mark.getFont().deriveFont(Font.BOLD, 22f)); mark.setPreferredSize(new Dimension(44, 44));
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 2));
        JLabel title = new JLabel("文本编辑器"); title.setFont(title.getFont().deriveFont(Font.BOLD, 19f));
        titles.add(title); titles.add(muted(new JLabel("纯文本 · 本地文件")));
        brand.add(mark, BorderLayout.WEST); brand.add(titles, BorderLayout.CENTER);
        heading.add(brand, BorderLayout.CENTER);
        heading.add(button(themeAction, THEME, false), BorderLayout.EAST);
        JPanel top = new JPanel(new BorderLayout(0, 16)); top.add(heading, BorderLayout.NORTH);
        JPanel toolbar = new JPanel(); toolbar.setLayout(new BoxLayout(toolbar, BoxLayout.X_AXIS));
        JButton newButton = button(newAction, NEW, false);
        // 工具栏放不下类型后缀，按钮与无障碍名称统一叫「新建」。
        newButton.setText("新建"); newButton.getAccessibleContext().setAccessibleName("新建");
        toolbar.add(newButton); toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(button(openAction, OPEN, false)); toolbar.add(Box.createHorizontalStrut(6));
        JButton save = button(saveAction, SAVE, false); save.putClientProperty("FlatLaf.styleClass", "primary");
        save.putClientProperty("JButton.buttonType", "roundRect");
        toolbar.add(save); toolbar.add(Box.createHorizontalStrut(12));
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setMaximumSize(UIScale.scale(new Dimension(1, 24))); toolbar.add(separator);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(button(undoAction, UNDO, true)); toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(button(redoAction, REDO, true)); toolbar.add(Box.createHorizontalGlue());
        previewToggle = new JToggleButton(previewAction);
        previewToggle.setText("预览");
        previewToggle.setToolTipText("预览 Markdown（Ctrl+E）");
        previewToggle.getAccessibleContext().setAccessibleName("预览 Markdown");
        previewToggle.getAccessibleContext().setAccessibleDescription("在源码与 Markdown 预览之间切换");
        toolbar.add(previewToggle); toolbar.add(Box.createHorizontalStrut(6));
        JButton searchButton = button(showReplace, SEARCH, false); searchButton.setText("查找 / 替换");
        searchToggle = searchButton;
        toolbar.add(searchButton); top.add(toolbar, BorderLayout.SOUTH); shell.add(top, BorderLayout.NORTH);

        JPanel document = surface(new JPanel(new BorderLayout()));
        document.putClientProperty("FlatLaf.style", "border: 1,1,1,1,$Component.borderColor,1,12");
        fileHeader = surface(new JPanel(new BorderLayout(16, 0)));
        fileHeader.setBorder(BorderFactory.createEmptyBorder(15, 20, 14, 20));
        JPanel fileLabels = surface(new JPanel(new GridLayout(2, 1, 0, 4)));
        documentName.setFont(documentName.getFont().deriveFont(Font.BOLD, 16f));
        fileLabels.add(documentName); fileLabels.add(muted(documentPath));
        fileHeader.add(fileLabels, BorderLayout.CENTER);
        savedState.putClientProperty("FlatLaf.styleClass", "badge"); fileHeader.add(savedState, BorderLayout.EAST);
        documentTop.add(fileHeader, BorderLayout.NORTH);

        surface(searchPanel); searchPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 14, 20));
        JPanel findRow = surface(new JPanel(new BorderLayout(10, 0)));
        JLabel findLabel = new JLabel("查找"); findLabel.setLabelFor(query); findRow.add(findLabel, BorderLayout.WEST);
        query.putClientProperty("JTextField.placeholderText", "输入查找内容");
        replacement.putClientProperty("JTextField.placeholderText", "输入替换内容（可留空）");
        findRow.add(query, BorderLayout.CENTER);
        JPanel findButtons = surface(new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)));
        findButtons.add(button(nextAction, null, false)); findButtons.add(button(closeSearch, CLOSE, true));
        findRow.add(findButtons, BorderLayout.EAST);
        surface(replaceRow); JLabel replaceLabel = new JLabel("替换"); replaceLabel.setLabelFor(replacement);
        replaceRow.add(replaceLabel, BorderLayout.WEST); replaceRow.add(replacement, BorderLayout.CENTER);
        replaceRow.add(button(replaceAction, null, false), BorderLayout.EAST);
        JPanel searchRows = surface(new JPanel(new BorderLayout(0, 8)));
        searchRows.add(findRow, BorderLayout.NORTH); searchRows.add(replaceRow, BorderLayout.SOUTH);
        searchPanel.add(searchRows, BorderLayout.CENTER); searchPanel.add(muted(searchResult), BorderLayout.SOUTH);
        searchResult.setToolTipText("区分大小写 · 按字面查找 · 末尾自动回绕");
        searchPanel.setVisible(false); documentTop.add(searchPanel, BorderLayout.CENTER);
        document.add(documentTop, BorderLayout.NORTH);
        scroll = new JScrollPane(area) {
            @Override protected void processMouseWheelEvent(MouseWheelEvent event) {
                if (event.isControlDown()) { setZoom(zoom - event.getWheelRotation() * 10); event.consume(); }
                else super.processMouseWheelEvent(event);
            }
        };
        scroll.setBorder(BorderFactory.createEmptyBorder());
        // 滚动时补色：SyntaxPainter 自己判断可视区是否已覆盖，重复调用是廉价的。
        scroll.getVerticalScrollBar().addAdjustmentListener(event -> binding.painter.schedule());
        JScrollPane previewScroll = new JScrollPane(preview.pane()) {
            @Override protected void processMouseWheelEvent(MouseWheelEvent event) {
                if (event.isControlDown()) { setZoom(zoom - event.getWheelRotation() * 10); event.consume(); }
                else super.processMouseWheelEvent(event);
            }
        };
        previewScroll.setBorder(BorderFactory.createEmptyBorder());
        previewScroll.getVerticalScrollBar().setUnitIncrement(UIScale.scale(24));
        stage.add(scroll, "source");
        stage.add(previewScroll, "preview");
        JPanel body = new JPanel(new BorderLayout());
        body.add(stage, BorderLayout.CENTER); body.add(searchDock, BorderLayout.EAST);
        document.add(body, BorderLayout.CENTER); shell.add(document, BorderLayout.CENTER);
        footer = new JPanel(new BorderLayout(12, 0));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        footer.add(muted(status), BorderLayout.CENTER);
        JPanel zoomControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        zoomControls.add(muted(position)); zoomControls.add(Box.createHorizontalStrut(12));
        JButton minus = button(zoomOutAction, null, true); minus.setText("−"); zoomControls.add(minus);
        zoomLabel.setAction(resetZoom); zoomLabel.setText("100%"); zoomLabel.setToolTipText("重置缩放（Ctrl+0）");
        zoomLabel.getAccessibleContext().setAccessibleName("重置编辑区缩放"); zoomControls.add(zoomLabel);
        JButton plus = button(zoomInAction, null, true); plus.setText("+"); zoomControls.add(plus);
        footer.add(zoomControls, BorderLayout.EAST); shell.add(footer, BorderLayout.SOUTH);
        setContentPane(shell);
        query.addActionListener(e -> find()); replacement.addActionListener(e -> replaceCurrent());
        searchControls.add(query); searchControls.add(replacement);
        query.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { searchChanged(); }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { leave(EditorWindow.this::shutdown); }
            @Override public void windowDeactivated(WindowEvent e) { if (!busy) persistSession(); }
        });
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent event) { layoutSearch(); layoutChrome(); }
        });
        installDropTarget();
        loadRecent();
        setZoom(zoomOf(prefs));
        layoutSearch(); layoutChrome();
        restoreSession();
        applyTheme();
        updateState();
        sessionTimer.setRepeats(false);
        // 系统主题只在窗口重新获得焦点时比对；常驻轮询会让 reg 子进程每秒起一次。
        addWindowFocusListener(new WindowAdapter() {
            @Override public void windowGainedFocus(WindowEvent event) { followSystemTheme(); }
        });
    }

    @Override public void dispose() {
        sessionTimer.stop();
        super.dispose();
    }

    /** 首帧前完成主题安装，避免先白后黑。 */
    static void installSavedTheme() {
        EditorTheme.install(isDark(preferences().getProperty("theme", "system")));
    }

    private static synchronized Properties preferences() {
        if (cachedPrefs == null) cachedPrefs = EditorStore.loadPrefs();
        return cachedPrefs;
    }

    private static boolean isDark(String mode) {
        return "dark".equals(mode) || "system".equals(mode) && EditorTheme.systemIsDark();
    }

    private static String themeName(String mode) {
        return "system".equals(mode) ? "跟随系统" : "dark".equals(mode) ? "深色" : "浅色";
    }

    private static int zoomOf(Properties prefs) {
        try { return Math.max(70, Math.min(200, Integer.parseInt(prefs.getProperty("zoom", "100")))); }
        catch (RuntimeException invalid) { return 100; }
    }

    private Rectangle savedBounds() {
        String value = prefs.getProperty("bounds");
        if (value == null) return null;
        String[] parts = value.split(",");
        if (parts.length != 4) return null;
        try {
            Rectangle bounds = new Rectangle(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
            if (bounds.width < 400 || bounds.height < 300) return null;
            Rectangle centre = new Rectangle(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2, 1, 1);
            for (GraphicsDevice screen : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                if (screen.getDefaultConfiguration().getBounds().intersects(centre)) return bounds;
        } catch (RuntimeException invalid) {
            return null;
        }
        return null; // 还原点落在已拔掉的显示器，回落主屏居中
    }

    private Action addAction(JMenu menu, String name, String key, Runnable operation) {
        Action action = action(name, key, operation); menu.add(action); return action;
    }

    private Action action(String name, String key, Runnable operation) {
        Action action = new AbstractAction(name) { public void actionPerformed(ActionEvent event) { if (!busy) operation.run(); } };
        if (key != null) {
            KeyStroke stroke = KeyStroke.getKeyStroke(key); action.putValue(Action.ACCELERATOR_KEY, stroke);
            getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, name);
            getRootPane().getActionMap().put(name, action);
            area.getInputMap().put(stroke, name); area.getActionMap().put(name, action);
        }
        actions.add(action); return action;
    }

    private JRadioButtonMenuItem themeItem(JMenu menu, ButtonGroup group, String name, String mode) {
        JRadioButtonMenuItem item = new JRadioButtonMenuItem(name);
        item.addActionListener(event -> { if (!busy) setTheme(mode); });
        group.add(item); menu.add(item); return item;
    }

    private JButton button(Action action, EditorIcon.Kind icon, boolean iconOnly) {
        JButton button = new JButton(action);
        if (icon != null) button.setIcon(new EditorIcon(icon));
        if (iconOnly) button.setText("");
        String name = action.getValue(Action.NAME).toString();
        Object key = action.getValue(Action.ACCELERATOR_KEY);
        String hint = name + (key == null ? "" : "（" + key.toString().replace("pressed ", "").replace("control", "Ctrl") + "）");
        button.setToolTipText(hint);
        button.getAccessibleContext().setAccessibleName(name);
        button.getAccessibleContext().setAccessibleDescription(hint); // 快捷键写进可访问描述
        action.addPropertyChangeListener(event -> {
            if (Action.NAME.equals(event.getPropertyName())) {
                button.setToolTipText(event.getNewValue().toString());
                button.getAccessibleContext().setAccessibleName(event.getNewValue().toString());
                button.getAccessibleContext().setAccessibleDescription(event.getNewValue().toString());
            }
        });
        return button;
    }

    private static JPanel surface(JPanel panel) { panel.putClientProperty("FlatLaf.styleClass", "surface"); return panel; }
    private static JLabel muted(JLabel label) { label.putClientProperty("FlatLaf.styleClass", "muted"); return label; }

    private void showSearch(boolean replace) {
        searchPanel.setVisible(true); replaceRow.setVisible(replace);
        searchResult.setText("区分大小写 · 按字面查找 · 末尾自动回绕");
        announceSearch(); layoutSearch(); revalidate();
        (replace ? replacement : query).requestFocusInWindow();
        if (!replace) query.selectAll();
    }

    private void closeSearch() {
        searchPanel.setVisible(false); announceSearch(); layoutSearch(); area.requestFocusInWindow();
    }

    /** 宽度充足时停靠右侧（上限 320），否则回到顶部条。 */
    private void layoutSearch() {
        boolean wide = getWidth() >= UIScale.scale(960);
        if (wide != docked || searchPanel.getParent() == null) {
            docked = wide;
            if (wide) {
                searchPanel.setBorder(BorderFactory.createEmptyBorder(16, 0, 16, 16));
                searchDock.add(searchPanel, BorderLayout.NORTH);
            } else {
                searchPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 14, 20));
                documentTop.add(searchPanel, BorderLayout.CENTER);
            }
        }
        searchDock.setPreferredSize(docked
                ? new Dimension(searchPanel.isVisible() ? UIScale.scale(320) : 0, 0) : null);
        revalidate(); repaint();
    }

    /** 矮窗口收起装饰性抬头与文件信息行并收紧留白，把高度留给正文。 */
    private void layoutChrome() {
        boolean compact = getHeight() < UIScale.scale(700);
        heading.setVisible(!compact);
        fileHeader.setVisible(!compact);
        ((BorderLayout) shell.getLayout()).setVgap(compact ? 6 : 14);
        shell.setBorder(BorderFactory.createEmptyBorder(compact ? 6 : 18, 20, 0, 20));
        footer.setBorder(BorderFactory.createEmptyBorder(0, 0, compact ? 4 : 10, 0));
        revalidate(); repaint();
    }

    /** 展开/收起状态写进可访问描述，屏幕阅读器随按钮播报。 */
    private void announceSearch() {
        String state = searchPanel.isVisible() ? "查找面板已展开" : "查找面板已收起";
        searchToggle.getAccessibleContext().setAccessibleDescription(state);
        searchPanel.getAccessibleContext().setAccessibleDescription(state);
    }

    private void searchChanged() {
        searchResult.setText("区分大小写 · 按字面查找 · 末尾自动回绕");
        nextAction.setEnabled(!busy && !query.getText().isEmpty());
        replaceAction.setEnabled(!busy && !readOnly && !query.getText().isEmpty());
    }

    private void setTheme(String mode) {
        themeMode = mode;
        boolean wantDark = isDark(mode);
        if (wantDark != FlatLaf.isLafDark()) {
            binding.finishInput();
            EditorTheme.install(wantDark); FlatLaf.updateUI();
        }
        applyTheme();
        EditorStore.savePrefs(prefs); // 手动选择立即落盘，进程被杀也不回退到系统主题
        area.requestFocusInWindow();
    }

    private void applyTheme() {
        themeSystem.setSelected("system".equals(themeMode));
        themeLight.setSelected("light".equals(themeMode));
        themeDark.setSelected("dark".equals(themeMode));
        themeAction.putValue(Action.NAME, "主题：" + themeName(themeMode));
        prefs.setProperty("theme", themeMode);
        binding.painter.applyTheme(FlatLaf.isLafDark());
        if (previewMode) preview.render(model.getText(), FlatLaf.isLafDark());
    }

    private void cycleTheme() {
        binding.finishInput();
        setTheme("system".equals(themeMode) ? "light" : "light".equals(themeMode) ? "dark" : "system");
    }

    /** 跟随系统时在窗口获焦后比对一次注册表；结果在后台读取，避免阻塞界面。 */
    private void followSystemTheme() {
        if (!"system".equals(themeMode) || busy) return;
        CompletableFuture.supplyAsync(EditorTheme::systemIsDark).thenAccept(systemDark -> SwingUtilities.invokeLater(() -> {
            if ("system".equals(themeMode) && systemDark != FlatLaf.isLafDark()) setTheme("system");
        }));
    }

    private void setZoom(int value) {
        zoom = Math.max(70, Math.min(200, value));
        float size = UIScale.scale(18f) * zoom / 100;
        binding.setFontSize(area.getFont().deriveFont(size));
        zoomLabel.setText(zoom + "%");
        if (zoomInAction != null) zoomInAction.setEnabled(!busy && zoom < 200);
        if (zoomOutAction != null) zoomOutAction.setEnabled(!busy && zoom > 70);
    }

    /** 源码 / 预览切换；两侧文档互不影响，所以光标、选区与滚动位置天然保留。 */
    private void setPreview(boolean on) {
        if (on && language != LanguageId.MARKDOWN) { updateState(); return; }
        binding.finishInput();
        previewMode = on;
        if (on) { preview.render(model.getText(), FlatLaf.isLafDark()); cards.show(stage, "preview"); preview.pane().requestFocusInWindow(); }
        else { cards.show(stage, "source"); area.requestFocusInWindow(); }
        previewToggle.setSelected(on);
        previewAction.putValue(Action.NAME, on ? "返回源码" : "预览 Markdown");
        preview.pane().getAccessibleContext().setAccessibleDescription(on ? "预览已展开" : "预览已收起");
        updateState();
    }

    /** 识别到新语言：只在真的变了时才通知高亮器，避免打开文件时白扫一遍旧文本。 */
    private void adoptLanguage(LanguageId next) {
        if (next == null) next = LanguageId.PLAIN;
        if (next != language) {
            language = next;
            binding.setLanguage(next);
        }
        languageItems.get(next).setSelected(true);
        if (previewMode && next != LanguageId.MARKDOWN) setPreview(false);
        updateState();
    }

    private JTextComponent focusedText() {
        Component focused = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focused instanceof JTextComponent ? (JTextComponent) focused : area;
    }

    void find() {
        if (busy) return;
        binding.finishInput();
        if (query.getText().isEmpty()) { showSearch(false); searchResult.setText("请输入查找内容"); return; }
        try {
            int hit = model.findNext(query.getText(), model.getSelectionEnd());
            if (hit < 0) { showSearch(false); searchResult.setText(TextKeys.of(TextKeys.NOT_FOUND, query.getText())); return; }
            model.setSelection(hit, hit + query.getText().length()); binding.syncSelection(); area.requestFocusInWindow();
            searchResult.setText("已找到匹配 · F3 查找下一处");
            updateState();
        } catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); }
    }

    void replaceCurrent() {
        if (busy || readOnly) return;
        binding.finishInput();
        String pattern = query.getText();
        if (pattern.isEmpty()) { showSearch(true); searchResult.setText("请输入查找内容"); return; }
        try {
            int start = model.getSelectionStart(), end = model.getSelectionEnd();
            if (!model.getText().substring(start, end).equals(pattern)) {
                start = model.findNext(pattern, end);
                if (start < 0) { showSearch(true); searchResult.setText(TextKeys.of(TextKeys.NOT_FOUND, pattern)); return; }
                end = start + pattern.length(); model.setSelection(start, end);
            }
            model.replace(start, end, replacement.getText()); binding.refresh(); area.requestFocusInWindow();
            searchResult.setText("已替换一处 · 可使用 Ctrl+Z 撤销");
        } catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); }
    }

    private void leave(Runnable next) {
        if (busy) return;
        binding.finishInput();
        if (!model.isModified()) { next.run(); return; }
        Object[] options = {"保存", "放弃", "取消"};
        int answer = JOptionPane.showOptionDialog(this, "当前文档有未保存的修改。", "离开前保存",
                JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[0]);
        if (answer == 0) save(false, next);
        else if (answer == 1) next.run();
    }

    /** 三个过滤器覆盖源码 / Markdown / 纯文本，另外保留“所有文件”；按当前语言预选。 */
    static JFileChooser chooser(Path selected, String defaultName) {
        JFileChooser chooser = new JFileChooser();
        FileNameExtensionFilter source = new FileNameExtensionFilter("源代码 (*.java, *.c, *.h, *.cpp, *.json, *.xml)",
                "java", "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "json", "xml", "html", "htm", "xhtml", "svg");
        FileNameExtensionFilter markdown = new FileNameExtensionFilter("Markdown (*.md, *.markdown)", "md", "markdown", "mdown");
        FileNameExtensionFilter plain = new FileNameExtensionFilter("纯文本 (*.txt, *.log, *.ini, *.properties)",
                "txt", "text", "log", "ini", "properties", "csv");
        chooser.addChoosableFileFilter(source);
        chooser.addChoosableFileFilter(markdown);
        chooser.addChoosableFileFilter(plain);
        chooser.setAcceptAllFileFilterUsed(true);
        String name = defaultName != null ? defaultName : selected != null && selected.getFileName() != null
                ? selected.getFileName().toString() : "";
        switch (LanguageId.forFileName(name)) {
            case MARKDOWN: chooser.setFileFilter(markdown); break;
            case PLAIN: chooser.setFileFilter(plain); break;
            default: chooser.setFileFilter(source); break;
        }
        if (selected != null) chooser.setSelectedFile(selected.toFile());
        else if (defaultName != null) chooser.setSelectedFile(new File(defaultName));
        return chooser;
    }

    private void chooseOpen() {
        JFileChooser chooser = chooser(file, null);
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        openPath(chooser.getSelectedFile().toPath().toAbsolutePath());
    }

    /** 打开一条路径；拖放、最近文件与选择器共用。 */
    private void openPath(Path chosen) {
        setBusy(true);
        new SwingWorker<String, Void>() {
            private long size;
            protected String doInBackground() throws Exception { size = Files.size(chosen); return SafeFiles.read(chosen); }
            protected void done() {
                boolean success = false;
                try {
                    String text = get(); model.load(text);
                    binding.endGroup();
                    file = chosen; readOnly = EditorStore.tooLarge(size);
                    fileModified = EditorStore.modifiedTime(chosen); fileSize = size;
                    addRecent(chosen); EditorStore.clearSession(); restored = false;
                    adoptLanguage(detectLanguage(chosen, text));
                    if (previewMode) preview.render(text, FlatLaf.isLafDark());
                    binding.refresh(); area.setCaretPosition(0);
                    success = true;
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); showError("打开被中断；原内容保留。"); }
                catch (ExecutionException failed) { showError(fileError(failed.getCause())); }
                finally { setBusy(false); area.requestFocusInWindow(); }
                if (success && readOnly) {
                    status.setText(TextKeys.of(TextKeys.TOO_LARGE, "16 MiB"));
                    searchResult.setText(TextKeys.of(TextKeys.TOO_LARGE, "16 MiB"));
                }
            }
        }.execute();
    }

    /** 后缀优先，MIME 与首行只作补充（设计 §5.1）。 */
    private static LanguageId detectLanguage(Path path, String text) {
        String name = path.getFileName() == null ? null : path.getFileName().toString();
        String mime = null;
        try { mime = Files.probeContentType(path); }
        catch (IOException | RuntimeException unavailable) { }
        String head = text.length() > 1024 ? text.substring(0, 1024) : text;
        return LanguageId.detect(name, mime, head);
    }

    private void newDocument(LanguageId type) {
        model.load(""); binding.endGroup();
        file = null; readOnly = false; restored = false;
        adoptLanguage(type);
        setPreview(false);
        area.setEditable(!busy);
        EditorStore.clearSession(); binding.refresh(); area.requestFocusInWindow();
    }

    private void save(boolean saveAs, Runnable afterSuccess) {
        if (busy) return;
        binding.finishInput();
        Path target = file;
        if (saveAs || target == null) {
            JFileChooser chooser = chooser(target, target == null ? language.defaultFileName() : null);
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            target = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "覆盖已有文件？\n" + target,
                    "确认覆盖", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        } else if (EditorStore.externallyModified(target, fileModified, fileSize)) {
            Object[] options = {"覆盖", "另存为"};
            int answer = JOptionPane.showOptionDialog(this, TextKeys.of(TextKeys.EXTERNALLY_MODIFIED, target.toString()),
                    "文件已被修改", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            if (answer == 1) { save(true, afterSuccess); return; }
            if (answer != 0) return;
        }
        final Path chosen = target;
        final String snapshot = model.getText();
        setBusy(true);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { SafeFiles.write(chosen, snapshot); return null; }
            protected void done() {
                boolean success = false;
                try {
                    get(); file = chosen; readOnly = false;
                    fileModified = EditorStore.modifiedTime(chosen); fileSize = EditorStore.size(chosen);
                    model.markSaved(snapshot); EditorStore.clearSession(); addRecent(chosen); success = true;
                    adoptLanguage(LanguageId.forFileName(chosen.getFileName() == null ? null : chosen.getFileName().toString()));
                } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); showError("保存被中断；请检查目标文件后再次保存。"); }
                catch (ExecutionException failed) { saveFailed(fileError(failed.getCause())); }
                finally { setBusy(false); }
                if (success) afterSuccess.run();
            }
        }.execute();
    }

    /** 与安卓端一致：保存失败给「另存为 / 继续编辑」两条路。 */
    private void saveFailed(String message) {
        Object[] options = {"另存为", "继续编辑"};
        int answer = JOptionPane.showOptionDialog(this, message, "保存未完成",
                JOptionPane.DEFAULT_OPTION, JOptionPane.ERROR_MESSAGE, null, options, options[1]);
        if (answer == 0) save(true, () -> { });
    }

    private String fileError(Throwable failure) {
        if (failure instanceof CharacterCodingException) return TextKeys.of(TextKeys.NOT_UTF8);
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    private void installDropTarget() {
        DropTargetListener listener = new DropTargetAdapter() {
            @Override public void drop(DropTargetDropEvent event) {
                if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) { event.rejectDrop(); return; }
                try {
                    event.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> files = (List<File>) event.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (files == null || files.isEmpty()) return;
                    Path chosen = files.get(0).toPath().toAbsolutePath();
                    if (files.size() > 1) status.setText("一次只能打开一个文件；已选择 " + chosen.getFileName());
                    leave(() -> openPath(chosen));
                } catch (Exception failed) {
                    showError("无法读取拖入的文件：" + failed.getMessage());
                }
            }
        };
        new DropTarget(area, DnDConstants.ACTION_COPY, listener, true);
        new DropTarget(getRootPane(), DnDConstants.ACTION_COPY, listener, true);
    }

    private void loadRecent() {
        for (int i = 0; i < RECENT_LIMIT; i++) {
            String value = prefs.getProperty("recent." + i);
            if (value != null && !value.isEmpty()) { Path path = Paths.get(value); if (!recent.contains(path)) recent.add(path); }
        }
        while (recent.size() > RECENT_LIMIT) recent.remove(recent.size() - 1);
        rebuildRecent();
    }

    private void addRecent(Path path) {
        recent.remove(path); recent.add(0, path);
        while (recent.size() > RECENT_LIMIT) recent.remove(recent.size() - 1);
        rebuildRecent();
    }

    private void rebuildRecent() {
        recentMenu.removeAll();
        for (final Path path : recent) {
            JMenuItem item = new JMenuItem(path.getFileName() == null ? path.toString() : path.getFileName().toString());
            item.setToolTipText(path.toString());
            item.setEnabled(Files.exists(path)); // 缺失的文件置灰而不是报错
            item.addActionListener(event -> leave(() -> openPath(path)));
            recentMenu.add(item);
        }
        recentMenu.setEnabled(!recent.isEmpty());
    }

    private SessionSnapshot snapshot() {
        int length = model.length();
        return new SessionSnapshot(model.getText(), model.getSavedText(), file == null ? null : file.toString(),
                Math.min(model.getSelectionStart(), length), Math.min(model.getSelectionEnd(), length),
                scroll.getVerticalScrollBar().getValue(), query.getText(), replacement.getText(), searchPanel.isVisible(),
                language.name(), previewMode ? 1 : 0);
    }

    /** 编辑后 700ms 防抖落盘；未修改的文档不写草稿。 */
    private void persistSession() {
        if (busy || !isDisplayable()) return;
        if (!model.isModified()) { EditorStore.clearSession(); return; }
        try {
            EditorStore.writeSession(snapshot());
        } catch (IOException failed) {
            status.setText("会话暂存失败；请尽快手动保存。（" + failed.getMessage() + "）");
        }
    }

    private void restoreSession() {
        long exit = 0;
        try { exit = Long.parseLong(prefs.getProperty("lastExit", "0")); }
        catch (RuntimeException invalid) { }
        long session = EditorStore.sessionTime();
        if (session == 0 || session <= exit) return;
        SessionSnapshot snapshot = EditorStore.readSession();
        if (snapshot == null) return;
        model.load(snapshot.text);
        binding.endGroup();
        model.markSaved(snapshot.savedText == null ? "" : snapshot.savedText);
        int length = model.length();
        int start = Math.min(snapshot.selectionStart, length), end = Math.min(snapshot.selectionEnd, length);
        if (model.isBoundary(start) && model.isBoundary(end) && start <= end) model.setSelection(start, end);
        if (snapshot.fileName != null && !snapshot.fileName.isEmpty()) file = Paths.get(snapshot.fileName);
        query.setText(snapshot.pattern == null ? "" : snapshot.pattern);
        replacement.setText(snapshot.replacement == null ? "" : snapshot.replacement);
        if (snapshot.searchOpen) showSearch(!replacement.getText().isEmpty());
        adoptLanguage(LanguageId.parse(snapshot.language));
        binding.refresh();
        if (snapshot.viewMode == 1 && language == LanguageId.MARKDOWN) setPreview(true);
        restored = true;
        final int offset = snapshot.scroll;
        SwingUtilities.invokeLater(() -> scroll.getVerticalScrollBar().setValue(offset));
    }

    private void shutdown() {
        sessionTimer.stop();
        persistSession();
        EditorStore.clearSession();
        Rectangle bounds = getBounds();
        prefs.setProperty("theme", themeMode);
        prefs.setProperty("bounds", bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height);
        prefs.setProperty("zoom", String.valueOf(zoom));
        for (int i = 0; i < RECENT_LIMIT; i++) {
            if (i < recent.size()) prefs.setProperty("recent." + i, recent.get(i).toString());
            else prefs.remove("recent." + i);
        }
        prefs.setProperty("lastExit", String.valueOf(System.currentTimeMillis()));
        EditorStore.savePrefs(prefs);
        dispose();
    }

    private void documentChanged() {
        restored = false;
        updateState();
        sessionTimer.restart();
    }

    private void setBusy(boolean value) {
        busy = value; area.setEditable(!value && !readOnly);
        for (Action action : actions) action.setEnabled(!value);
        for (JComponent control : searchControls) control.setEnabled(!value);
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        updateState();
    }

    private void updateState() {
        String name = file == null ? "未命名" + language.defaultSuffix : file.getFileName().toString();
        setTitle((model.isModified() ? "* " : "") + name + " — 文本编辑器");
        documentName.setText(name);
        documentPath.setText(file == null ? "保存后将在这里显示文件位置" : file.toString());
        documentPath.setToolTipText(file == null ? "尚未保存到文件" : file.toString());
        savedState.setText(readOnly ? "只读" : busy ? "文件处理中…" : model.isModified() ? "● 未保存" : file == null ? "新文档" : "已保存");
        status.setText((restored ? "已恢复上次会话  |  " : "") + (busy ? "文件处理中…  " : "")
                + model.getCodePointCount() + " 个码点  |  " + model.getLineCount() + " 行  |  "
                + language.title + "  |  UTF-8 / LF");
        try {
            int caret = area.getCaretPosition();
            Element root = area.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(caret);
            position.setText("第 " + (line + 1) + " 行 : " + (caret - root.getElement(line).getStartOffset() + 1) + " 列");
        } catch (RuntimeException invalid) { position.setText(" "); }
        if (undoAction != null) undoAction.setEnabled(!busy && model.canUndo());
        if (redoAction != null) redoAction.setEnabled(!busy && model.canRedo());
        if (previewAction != null) previewAction.setEnabled(!busy && language == LanguageId.MARKDOWN);
        if (previewToggle != null) previewToggle.setEnabled(!busy && language == LanguageId.MARKDOWN);
        for (JRadioButtonMenuItem item : languageItems.values()) item.setEnabled(!busy);
        if (nextAction != null) {
            nextAction.setEnabled(!busy && !query.getText().isEmpty());
            replaceAction.setEnabled(!busy && !readOnly && !query.getText().isEmpty());
            zoomInAction.setEnabled(!busy && zoom < 200); zoomOutAction.setEnabled(!busy && zoom > 70);
        }
    }

    private void showError(String message) { JOptionPane.showMessageDialog(this, message, "文本编辑器", JOptionPane.ERROR_MESSAGE); }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installSavedTheme();
            new EditorWindow().setVisible(true);
        });
    }
}

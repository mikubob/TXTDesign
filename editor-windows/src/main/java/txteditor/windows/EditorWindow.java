package txteditor.windows;

import txteditor.core.EditorModel;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.*;
import java.nio.charset.CharacterCodingException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import static txteditor.windows.EditorIcon.Kind.*;

public final class EditorWindow extends JFrame {
    final EditorModel model = new EditorModel();
    final JTextArea area = new JTextArea();
    final JTextField query = new JTextField(14);
    final JTextField replacement = new JTextField(14);
    final JLabel status = new JLabel(" ");
    final JPanel searchPanel = new JPanel(new BorderLayout(0, 8));
    private final JPanel replaceRow = new JPanel(new BorderLayout(10, 0));
    private final JLabel documentName = new JLabel(), documentPath = new JLabel(), savedState = new JLabel();
    private final JLabel searchResult = new JLabel("区分大小写 · 按字面查找 · 末尾自动回绕");
    private final JLabel position = new JLabel();
    private final JButton zoomLabel = new JButton("100%");
    final EditorBinding binding;
    private final List<Action> actions = new ArrayList<Action>();
    private final List<JComponent> searchControls = new ArrayList<JComponent>();
    private Action undoAction, redoAction, nextAction, replaceAction, zoomInAction, zoomOutAction, themeAction;
    private Path file;
    private boolean busy;
    private boolean dark;
    private int zoom = 100;

    public EditorWindow() {
        super("文本编辑器");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(UIScale.scale(new Dimension(1080, 780)));
        setMinimumSize(UIScale.scale(new Dimension(760, 600))); setLocationByPlatform(true);
        area.setFont(javax.swing.text.StyleContext.getDefaultStyleContext()
                .getFont("Microsoft YaHei UI", Font.PLAIN, 18).deriveFont(UIScale.scale(18f)));
        area.setMargin(new Insets(22, 26, 22, 26));
        area.setLineWrap(true); area.setWrapStyleWord(true);
        area.getAccessibleContext().setAccessibleName("纯文本编辑区");
        query.getAccessibleContext().setAccessibleName("查找词，区分大小写");
        replacement.getAccessibleContext().setAccessibleName("替换文本");
        binding = new EditorBinding(area, model, this::updateState, this::showError);
        JMenuBar menus = new JMenuBar();
        JMenu files = new JMenu("文件"); files.setMnemonic(KeyEvent.VK_F);
        Action newAction = addAction(files, "新建", "control N", () -> leave(() -> {
            model.load(""); file = null; binding.refresh(); area.requestFocusInWindow();
        }));
        Action openAction = addAction(files, "打开…", "control O", () -> leave(this::chooseOpen));
        Action saveAction = addAction(files, "保存", "control S", () -> save(false, () -> { }));
        addAction(files, "另存为…", "control shift S", () -> save(true, () -> { }));
        files.addSeparator(); addAction(files, "退出", "alt F4", () -> leave(this::dispose));
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
        themeAction = addAction(view, "深色模式", null, this::toggleTheme);
        zoomInAction = addAction(view, "放大", "control EQUALS", () -> setZoom(zoom + 10));
        zoomOutAction = addAction(view, "缩小", "control MINUS", () -> setZoom(zoom - 10));
        Action resetZoom = addAction(view, "重置缩放", "control 0", () -> setZoom(100));
        menus.add(files); menus.add(edit); menus.add(searchMenu); menus.add(view); setJMenuBar(menus);

        JPanel shell = new JPanel(new BorderLayout(0, 14));
        shell.setBorder(BorderFactory.createEmptyBorder(18, 20, 0, 20));
        JPanel heading = new JPanel(new BorderLayout(16, 0));
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
        toolbar.add(button(newAction, NEW, false)); toolbar.add(Box.createHorizontalStrut(6));
        toolbar.add(button(openAction, OPEN, false)); toolbar.add(Box.createHorizontalStrut(6));
        JButton save = button(saveAction, SAVE, false); save.putClientProperty("FlatLaf.styleClass", "primary");
        save.putClientProperty("JButton.buttonType", "roundRect");
        toolbar.add(save); toolbar.add(Box.createHorizontalStrut(12));
        JSeparator separator = new JSeparator(SwingConstants.VERTICAL);
        separator.setMaximumSize(UIScale.scale(new Dimension(1, 24))); toolbar.add(separator);
        toolbar.add(Box.createHorizontalStrut(12));
        toolbar.add(button(undoAction, UNDO, true)); toolbar.add(Box.createHorizontalStrut(4));
        toolbar.add(button(redoAction, REDO, true)); toolbar.add(Box.createHorizontalGlue());
        JButton searchButton = button(showReplace, SEARCH, false); searchButton.setText("查找 / 替换");
        toolbar.add(searchButton); top.add(toolbar, BorderLayout.SOUTH); shell.add(top, BorderLayout.NORTH);

        JPanel document = surface(new JPanel(new BorderLayout()));
        document.putClientProperty("FlatLaf.style", "border: 1,1,1,1,$Component.borderColor,1,12");
        JPanel documentTop = surface(new JPanel(new BorderLayout()));
        JPanel fileHeader = surface(new JPanel(new BorderLayout(16, 0)));
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
        searchPanel.setVisible(false); documentTop.add(searchPanel, BorderLayout.CENTER);
        document.add(documentTop, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(area) {
            @Override protected void processMouseWheelEvent(MouseWheelEvent event) {
                if (event.isControlDown()) { setZoom(zoom - event.getWheelRotation() * 10); event.consume(); }
                else super.processMouseWheelEvent(event);
            }
        };
        scroll.setBorder(BorderFactory.createEmptyBorder());
        document.add(scroll, BorderLayout.CENTER); shell.add(document, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(12, 0));
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
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { leave(EditorWindow.this::dispose); } });
        updateState();
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

    private JButton button(Action action, EditorIcon.Kind icon, boolean iconOnly) {
        JButton button = new JButton(action);
        if (icon != null) button.setIcon(new EditorIcon(icon));
        if (iconOnly) button.setText("");
        String name = action.getValue(Action.NAME).toString();
        Object key = action.getValue(Action.ACCELERATOR_KEY);
        button.setToolTipText(name + (key == null ? "" : "（" + key.toString().replace("pressed ", "").replace("control", "Ctrl") + "）"));
        button.getAccessibleContext().setAccessibleName(name);
        action.addPropertyChangeListener(event -> {
            if (Action.NAME.equals(event.getPropertyName())) {
                button.setToolTipText(event.getNewValue().toString());
                button.getAccessibleContext().setAccessibleName(event.getNewValue().toString());
            }
        });
        return button;
    }

    private static JPanel surface(JPanel panel) { panel.putClientProperty("FlatLaf.styleClass", "surface"); return panel; }
    private static JLabel muted(JLabel label) { label.putClientProperty("FlatLaf.styleClass", "muted"); return label; }

    private void showSearch(boolean replace) {
        searchPanel.setVisible(true); replaceRow.setVisible(replace);
        searchResult.setText("区分大小写 · 按字面查找 · 末尾自动回绕");
        revalidate(); (replace ? replacement : query).requestFocusInWindow();
        if (!replace) query.selectAll();
    }

    private void closeSearch() { searchPanel.setVisible(false); revalidate(); area.requestFocusInWindow(); }

    private void searchChanged() {
        searchResult.setText("区分大小写 · 按字面查找 · 末尾自动回绕");
        nextAction.setEnabled(!busy && !query.getText().isEmpty());
        replaceAction.setEnabled(!busy && !query.getText().isEmpty());
    }

    private void toggleTheme() {
        binding.finishInput(); dark = !dark; EditorTheme.install(dark); FlatLaf.updateUI();
        themeAction.putValue(Action.NAME, dark ? "浅色模式" : "深色模式");
        area.requestFocusInWindow();
    }

    private void setZoom(int value) {
        zoom = Math.max(70, Math.min(200, value));
        area.setFont(area.getFont().deriveFont(UIScale.scale(18f) * zoom / 100)); zoomLabel.setText(zoom + "%");
        zoomInAction.setEnabled(!busy && zoom < 200); zoomOutAction.setEnabled(!busy && zoom > 70);
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
            if (hit < 0) { showSearch(false); searchResult.setText("未找到：" + query.getText()); return; }
            model.setSelection(hit, hit + query.getText().length()); binding.syncSelection(); area.requestFocusInWindow();
            searchResult.setText("已找到匹配 · F3 查找下一处");
            updateState();
        } catch (IllegalArgumentException invalid) { showError(invalid.getMessage()); }
    }

    void replaceCurrent() {
        if (busy) return;
        binding.finishInput();
        String pattern = query.getText();
        if (pattern.isEmpty()) { showSearch(true); searchResult.setText("请输入查找内容"); return; }
        try {
            int start = model.getSelectionStart(), end = model.getSelectionEnd();
            if (!model.getText().substring(start, end).equals(pattern)) {
                start = model.findNext(pattern, end);
                if (start < 0) { showSearch(true); searchResult.setText("未找到：" + pattern); return; }
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

    private JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("UTF-8 文本 (*.txt)", "txt"));
        if (file != null) chooser.setSelectedFile(file.toFile());
        return chooser;
    }

    private void chooseOpen() {
        JFileChooser chooser = chooser();
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path chosen = chooser.getSelectedFile().toPath().toAbsolutePath();
        setBusy(true);
        new SwingWorker<String, Void>() {
            protected String doInBackground() throws Exception { return SafeFiles.read(chosen); }
            protected void done() {
                try { String text = get(); model.load(text); file = chosen; binding.refresh(); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); showError("打开被中断；原内容保留。"); }
                catch (ExecutionException failed) { showError(fileError(failed.getCause())); }
                finally { setBusy(false); area.requestFocusInWindow(); }
            }
        }.execute();
    }

    private void save(boolean saveAs, Runnable afterSuccess) {
        if (busy) return;
        binding.finishInput();
        Path target = file;
        if (saveAs || target == null) {
            JFileChooser chooser = chooser();
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            target = chooser.getSelectedFile().toPath().toAbsolutePath();
            if (Files.exists(target) && JOptionPane.showConfirmDialog(this, "覆盖已有文件？\n" + target,
                    "确认覆盖", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        }
        final Path chosen = target;
        final String snapshot = model.getText();
        setBusy(true);
        new SwingWorker<Void, Void>() {
            protected Void doInBackground() throws Exception { SafeFiles.write(chosen, snapshot); return null; }
            protected void done() {
                boolean success = false;
                try { get(); file = chosen; model.markSaved(snapshot); success = true; }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); showError("保存被中断；请检查目标文件后再次保存。"); }
                catch (ExecutionException failed) { showError(fileError(failed.getCause())); }
                finally { setBusy(false); }
                if (success) afterSuccess.run();
            }
        }.execute();
    }

    private String fileError(Throwable failure) {
        if (failure instanceof CharacterCodingException) return "文件不是有效 UTF-8；请先在其他工具中转换编码。当前内容保留。";
        return failure.getMessage() == null ? failure.toString() : failure.getMessage();
    }

    private void setBusy(boolean value) {
        busy = value; area.setEditable(!value);
        for (Action action : actions) action.setEnabled(!value);
        for (JComponent control : searchControls) control.setEnabled(!value);
        setCursor(Cursor.getPredefinedCursor(value ? Cursor.WAIT_CURSOR : Cursor.DEFAULT_CURSOR));
        updateState();
    }

    private void updateState() {
        String name = file == null ? "未命名" : file.getFileName().toString();
        setTitle((model.isModified() ? "* " : "") + name + " — 文本编辑器");
        documentName.setText(name);
        documentPath.setText(file == null ? "保存后将在这里显示文件位置" : file.toString());
        documentPath.setToolTipText(file == null ? "尚未保存到文件" : file.toString());
        savedState.setText(busy ? "文件处理中…" : model.isModified() ? "● 未保存" : file == null ? "新文档" : "已保存");
        status.setText((busy ? "文件处理中…  " : "") + model.getCodePointCount() + " 个码点  |  "
                + model.getLineCount() + " 行  |  UTF-8 / LF");
        try {
            int caret = area.getCaretPosition(), line = area.getLineOfOffset(caret);
            position.setText("第 " + (line + 1) + " 行 : " + (caret - area.getLineStartOffset(line) + 1) + " 列");
        } catch (javax.swing.text.BadLocationException invalid) { position.setText(" "); }
        if (undoAction != null) undoAction.setEnabled(!busy && model.canUndo());
        if (redoAction != null) redoAction.setEnabled(!busy && model.canRedo());
        if (nextAction != null) {
            nextAction.setEnabled(!busy && !query.getText().isEmpty());
            replaceAction.setEnabled(!busy && !query.getText().isEmpty());
            zoomInAction.setEnabled(!busy && zoom < 200); zoomOutAction.setEnabled(!busy && zoom > 70);
        }
    }

    private void showError(String message) { JOptionPane.showMessageDialog(this, message, "文本编辑器", JOptionPane.ERROR_MESSAGE); }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            EditorTheme.install(false);
            new EditorWindow().setVisible(true);
        });
    }
}

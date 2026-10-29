package txteditor.windows;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.ActionEvent;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.util.UIScale;
import javax.swing.text.DefaultEditorKit;
import java.nio.file.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

/** Runs against a real visible Swing window; does not require third-party UI tools. */
public final class GuiTest {
    private static EditorWindow window;
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("GUI test requires an interactive Windows desktop");
        Path directory = Files.createTempDirectory("txteditor-gui-");
        try {
            // 会话与偏好落到临时目录，避免覆盖真实用户数据；主题固定为浅色保证断言确定。
            Path data = directory.resolve("data");
            Files.createDirectories(data);
            System.setProperty("txteditor.data", data.toString());
            java.util.Properties prefs = new java.util.Properties();
            prefs.setProperty("theme", "light");
            prefs.setProperty("recent.0", directory.resolve("已删除.txt").toString());
            EditorStore.savePrefs(prefs);
            window = edt(() -> { EditorWindow.installSavedTheme(); EditorWindow frame = new EditorWindow(); frame.setVisible(true); return frame; });
            Robot robot = new Robot(); robot.setAutoDelay(50);
            modernInteractions();
            docking();
            edt(() -> { window.area.requestFocusInWindow(); window.area.replaceSelection("中文😀\n第二行\n中文"); return null; });
            waitFor(() -> edt(() -> window.model.getText().equals(SyntaxPainter.text(window.area))));
            edt(() -> {
                eq("中文😀\n第二行\n中文", window.model.getText()); check(window.getTitle().startsWith("*"));
                window.query.setText("中文"); window.model.setSelection(0, 0); window.binding.syncSelection(); window.find();
                eq(0, window.area.getSelectionStart()); eq(2, window.area.getSelectionEnd());
                window.replacement.setText("替换"); window.replaceCurrent(); eq("替换😀\n第二行\n中文", window.model.getText());
                menu("编辑", "撤销").doClick(); eq("中文😀\n第二行\n中文", SyntaxPainter.text(window.area));
                menu("编辑", "重做").doClick(); eq("替换😀\n第二行\n中文", SyntaxPainter.text(window.area));
                window.area.select(0, 2); window.area.copy(); window.area.setCaretPosition(SyntaxPainter.text(window.area).length()); window.area.paste();
                eq("替换😀\n第二行\n中文替换", window.model.getText());
                window.area.select(0, 2); menu("编辑", "剪切").doClick(); eq("😀\n第二行\n中文替换", window.model.getText());
                menu("编辑", "撤销").doClick(); eq("替换😀\n第二行\n中文替换", window.model.getText());
                menu("编辑", "全选").doClick(); eq(window.model.length(), window.area.getSelectionEnd());
                return null;
            });
            // Exercise Swing's real Backspace action; OS keyboard/IME still needs manual acceptance.
            edt(() -> { window.toFront(); window.requestFocus(); window.area.requestFocusInWindow(); window.area.setCaretPosition(4); return null; });
            waitFor(() -> edt(() -> window.area.isFocusOwner()));
            edt(() -> { window.area.getActionMap().get(DefaultEditorKit.deletePrevCharAction)
                    .actionPerformed(new ActionEvent(window.area, ActionEvent.ACTION_PERFORMED, "test")); return null; });
            robot.waitForIdle();
            edt(() -> { eq("替换\n第二行\n中文替换", window.model.getText()); menu("编辑", "撤销").doClick(); return null; });
            Path saved = directory.resolve("中文测试.txt");
            System.out.println("GuiTest: editing, clipboard, surrogate deletion checked; saving");
            invokeMenu("文件", "另存为…");
            choose(saved);
            waitFor(() -> Files.exists(saved) && edt(() -> !window.model.isModified() && window.area.isEditable()));
            eq("替换😀\n第二行\n中文替换", SafeFiles.read(saved));
            check(((JMenu) menu("文件", "最近文件")).getItem(0).isEnabled());
            System.out.println("GuiTest: saved UTF-8; checking leave protection");
            edt(() -> { insert(window, "未保存"); return null; });
            invokeMenu("文件", "新建", "纯文本（.txt）"); clickDialog("取消");
            edt(() -> { check(SyntaxPainter.text(window.area).endsWith("未保存")); check(window.model.isModified()); return null; });
            invokeMenu("文件", "新建", "纯文本（.txt）"); clickDialog("保存");
            waitFor(() -> edt(() -> window.model.getText().isEmpty() && !window.model.isModified() && window.area.isEditable()));
            check(SafeFiles.read(saved).endsWith("未保存"));
            invokeMenu("文件", "新建", "Markdown（.md）");
            waitFor(() -> edt(() -> window.language == txteditor.core.text.LanguageId.MARKDOWN
                    && window.getTitle().startsWith("未命名.md") && window.model.getText().isEmpty()));
            System.out.println("GuiTest: cancel/save leave checked; reopening");
            invokeMenu("文件", "打开…"); choose(saved);
            waitFor(() -> edt(() -> window.model.getText().endsWith("未保存") && window.area.isEditable()));
            Path invalid = directory.resolve("bad.txt"); Files.write(invalid, new byte[] {(byte) 0xff});
            String before = edt(() -> window.model.getText());
            invokeMenu("文件", "打开…"); choose(invalid); clickAnyError();
            edt(() -> { eq(before, window.model.getText()); check(!window.model.isModified()); return null; });
            invokeMenu("文件", "打开…"); choose(Paths.get("sample.txt").toAbsolutePath());
            waitFor(() -> edt(() -> window.getTitle().startsWith("sample.txt") && window.area.isEditable()));
            robot.waitForIdle();
            previews(robot);
            sourceHighlighting(directory, robot);
            markdownPreview(directory, robot);
            sessionRestore();
            System.out.println("GuiTest PASS: " + checks + " checks; scale=" + UIScale.getUserScaleFactor() + "; previews=build/preview-*.png");
        } finally {
            edt(() -> { for (Window frame : Window.getWindows()) frame.dispose(); return null; });
            Path data = directory.resolve("data");
            if (Files.exists(data)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(data)) { for (Path path : files) Files.deleteIfExists(path); }
                Files.delete(data);
            }
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) { for (Path path : files) Files.deleteIfExists(path); }
            Files.delete(directory);
        }
    }

    private static void modernInteractions() throws Exception {
        edt(() -> {
            check(UIManager.getLookAndFeel() instanceof FlatLaf);
            check(window.area.getFont().canDisplay(0x4e2d)); check(window.area.getFont().canDisplay(0x1f600));
            check(!window.searchPanel.isVisible());
            check(!menu("编辑", "撤销").isEnabled()); check(!menu("编辑", "重做").isEnabled());
            JMenu recent = (JMenu) menu("文件", "最近文件");
            check(recent.getItemCount() == 1); check(!recent.getItem(0).isEnabled()); // 缺失文件置灰
            check(((JMenu) menu("文件", "新建")).getItemCount() == txteditor.core.text.LanguageId.values().length);
            check(window.area.getInputMap().get(KeyStroke.getKeyStroke("control H")).equals("替换…"));
            key("control F"); check(window.searchPanel.isVisible());
            check(!menu("查找", "下一处").isEnabled());
            return null;
        });
        waitFor(() -> edt(() -> window.query.isFocusOwner()));
        edt(() -> { key("control H"); return null; });
        waitFor(() -> edt(() -> window.replacement.isFocusOwner()));
        edt(() -> {
            window.query.setText("中文"); window.replacement.setText("汉字");
            check(menu("查找", "下一处").isEnabled());
            window.validate();
            Rectangle expanded = window.area.getVisibleRect();
            eq("查找面板已展开", window.searchPanel.getAccessibleContext().getAccessibleDescription());
            button(window, "关闭查找").doClick(); window.validate();
            check(!window.searchPanel.isVisible()); check(window.area.getVisibleRect().width > expanded.width
                    || window.area.getVisibleRect().height > expanded.height);
            eq("查找面板已收起", window.searchPanel.getAccessibleContext().getAccessibleDescription());
            eq("中文", window.query.getText()); eq("汉字", window.replacement.getText()); return null;
        });
        waitFor(() -> edt(() -> window.area.isFocusOwner()));
        edt(() -> {
            float font = window.area.getFont().getSize2D(); key("control EQUALS");
            check(window.area.getFont().getSize2D() > font); key("control 0");
            eq(font, window.area.getFont().getSize2D());
            JScrollPane scroll = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, window.area);
            java.awt.event.MouseWheelEvent wheel = new java.awt.event.MouseWheelEvent(scroll,
                    java.awt.event.MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), java.awt.event.InputEvent.CTRL_DOWN_MASK,
                    10, 10, 0, false, java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, -1);
            int scrollValue = scroll.getVerticalScrollBar().getValue(); scroll.dispatchEvent(wheel);
            check(wheel.isConsumed()); check(window.area.getFont().getSize2D() > font);
            eq(scrollValue, scroll.getVerticalScrollBar().getValue()); key("control 0");
            for (int i = 0; i < 12; i++) menu("视图", "放大").doClick();
            check(!menu("视图", "放大").isEnabled()); key("control 0");
            for (int i = 0; i < 5; i++) menu("视图", "缩小").doClick();
            check(!menu("视图", "缩小").isEnabled()); key("control 0");
            eq("", window.model.getText()); check(!window.model.canUndo()); check(!window.model.isModified());
            window.area.replaceSelection("主题切换保留中文😀与选区"); window.area.select(0, 4);
            String text = window.model.getText(); int start = window.area.getSelectionStart(), end = window.area.getSelectionEnd();
            menu("视图", "深色").doClick();
            check(FlatLaf.isLafDark()); eq(text, SyntaxPainter.text(window.area));
            eq(start, window.area.getSelectionStart()); eq(end, window.area.getSelectionEnd()); check(window.model.canUndo());
            check(button(window, "主题：深色").getAccessibleContext().getAccessibleName().equals("主题：深色"));
            checkContrast(); menu("视图", "浅色").doClick(); check(!FlatLaf.isLafDark()); checkContrast();
            menu("编辑", "撤销").doClick(); eq("", SyntaxPainter.text(window.area));
            window.model.load(""); window.binding.refresh(); return null;
        });
        System.out.println("GuiTest: theme, zoom, search visibility/focus, shortcuts and shared actions checked");
    }

    /** W5：宽度充足时查找栏停靠右侧且不超过 320，窄窗口回顶部条。 */
    private static void docking() throws Exception {
        edt(() -> { window.setSize(UIScale.scale(new Dimension(1080, 780))); menu("查找", "查找…").doClick(); return null; });
        edt(() -> {
            window.validate();
            check(window.searchPanel.getParent() == window.searchDock);
            check(window.searchPanel.getWidth() <= UIScale.scale(320));
            check(window.area.getVisibleRect().height >= UIScale.scale(100));
            return null;
        });
        edt(() -> { window.setSize(window.getMinimumSize()); return null; });
        edt(() -> {
            window.validate();
            check(window.searchPanel.getParent() == window.documentTop);
            return null;
        });
        edt(() -> { window.setSize(UIScale.scale(new Dimension(1080, 780))); key("ESCAPE"); return null; });
        edt(() -> { window.validate(); return null; });
        System.out.println("GuiTest: docked search bar checked");
    }

    /** W1：编辑后防抖落盘，模拟进程被杀后新窗口恢复文本、光标与提示只出现一次。 */
    private static void sessionRestore() throws Exception {
        edt(() -> { window.area.requestFocusInWindow(); window.area.setCaretPosition(0); window.area.selectAll();
            window.area.replaceSelection("会话恢复😀\n第二行"); return null; });
        waitFor(() -> edt(() -> window.model.getText().startsWith("会话恢复😀\n第二行")));
        edt(() -> { key("control E"); return null; }); // 预览状态一起写进 v2 会话
        waitFor(() -> edt(() -> window.previewMode));
        Thread.sleep(1200);
        Path session = EditorStore.directory().resolve("session.dat");
        check(Files.exists(session));
        check(SafeFiles.read(session).contains("会话恢复😀\n第二行"));
        check(SafeFiles.read(session).startsWith("txteditor-session 2"));
        String expected = edt(() -> window.model.getText());
        int caret = edt(() -> window.model.getSelectionEnd());
        edt(() -> { window.setVisible(false); return null; });
        EditorWindow second = edt(() -> { EditorWindow frame = new EditorWindow(); frame.setVisible(true); return frame; });
        waitFor(() -> edt(() -> second.previewMode));
        edt(() -> {
            eq(expected, SyntaxPainter.text(second.area)); eq(caret, second.model.getSelectionEnd());
            check(second.status.getText().startsWith("已恢复上次会话"));
            check(second.previewMode); check(!second.area.isShowing()); // v2 会话恢复了预览模式
            return null;
        });
        EditorWindow first = window;
        window = second; // 复用菜单 / 选择器助手
        Path resaved = EditorStore.directory().getParent().resolve("恢复后保存.txt");
        invokeMenu("文件", "另存为…"); choose(resaved);
        waitFor(() -> Files.exists(resaved) && edt(() -> !window.model.isModified() && window.area.isEditable()));
        eq(expected, SafeFiles.read(resaved));
        window = first;
        edt(() -> {
            insert(second, "继续");
            check(!second.status.getText().startsWith("已恢复上次会话")); // 提示只出现一次
            return null;
        });
        edt(() -> { second.dispose(); window.setVisible(true); return null; });
        System.out.println("GuiTest: session draft and crash restore checked");
    }

    /** 3.0：打开 .java 后可视行被上色（关键字有前景色、注释斜体），语言写进状态栏。 */
    private static void sourceHighlighting(Path directory, Robot robot) throws Exception {
        Path java = directory.resolve("示例.java");
        String source = "public class 示例 {\n    // 中文注释\n    private int value = 42;\n}\n";
        Files.write(java, source.getBytes("UTF-8"));
        invokeMenu("文件", "打开…"); choose(java);
        waitFor(() -> edt(() -> window.getTitle().startsWith("示例.java") && window.area.isEditable()));
        waitFor(() -> edt(() -> attrs(window, 0).isDefined(javax.swing.text.StyleConstants.Foreground))); // 上色在 invokeLater 里
        edt(() -> {
            eq(txteditor.core.text.LanguageId.JAVA, window.language);
            check(window.status.getText().contains("Java"));
            check(attrs(window, 0).isDefined(javax.swing.text.StyleConstants.Foreground));       // public
            check(attrs(window, source.indexOf("class")).isDefined(javax.swing.text.StyleConstants.Foreground));
            check(!attrs(window, 6).isDefined(javax.swing.text.StyleConstants.Foreground));      // 空白不上色
            int comment = source.indexOf("//");
            check(attrs(window, comment).isDefined(javax.swing.text.StyleConstants.Foreground));
            check(javax.swing.text.StyleConstants.isItalic(attrs(window, comment)));
            check(!menu("视图", "预览 Markdown").isEnabled());
            return null;
        });
        robot.waitForIdle();
        render("preview-java.png");
        edt(() -> { menu("视图", "深色").doClick(); window.validate(); return null; });
        waitFor(() -> edt(() -> attrs(window, 0).isDefined(javax.swing.text.StyleConstants.Foreground)));
        robot.waitForIdle();
        render("preview-java-dark.png");
        edt(() -> { menu("视图", "浅色").doClick(); return null; });
        System.out.println("GuiTest: java syntax colours and language label checked");
    }

    private static javax.swing.text.AttributeSet attrs(EditorWindow target, int offset) {
        return target.area.getStyledDocument().getCharacterElement(offset).getAttributes();
    }

    /** 3.0：Markdown 识别、Ctrl+E 预览、预览下缩放/主题、手动改语言。 */
    private static void markdownPreview(Path directory, Robot robot) throws Exception {
        Path md = directory.resolve("说明.md");
        String source = "# 标题\n\n段落 **粗体** 与 `代码` 与 [链接](https://example.com)。\n\n"
                + "- 项目一\n- 项目二\n\n> 引用一行\n\n```java\nclass A { int value = 1; }\n```\n\n"
                + "| 列 | 值 |\n| --- | --- |\n| a | 1 |\n\n---\n";
        Files.write(md, source.getBytes("UTF-8"));
        invokeMenu("文件", "打开…"); choose(md);
        waitFor(() -> edt(() -> window.getTitle().startsWith("说明.md") && window.area.isEditable()));
        edt(() -> {
            eq(txteditor.core.text.LanguageId.MARKDOWN, window.language);
            check(window.status.getText().contains("Markdown"));
            check(menu("视图", "预览 Markdown").isEnabled());
            window.area.setCaretPosition(0); window.area.select(2, 4);
            return null;
        });
        waitFor(() -> edt(() -> window.model.getSelectionStart() == 2 && window.model.getSelectionEnd() == 4));
        String text = edt(() -> window.model.getText());
        edt(() -> { key("control E"); return null; });
        waitFor(() -> edt(() -> window.previewMode));
        edt(() -> {
            check(window.preview.pane().isShowing());
            check(window.preview.pane().getStyledDocument().getLength() > 0);
            eq("Markdown 预览区", window.preview.pane().getAccessibleContext().getAccessibleName());
            eq(text, window.model.getText());
            eq(2, window.model.getSelectionStart()); // 预览不动模型、不丢选区
            check(SyntaxPainter.text(window.area).startsWith("# 标题"));
            return null;
        });
        robot.waitForIdle();
        render("preview-markdown.png");
        edt(() -> { menu("视图", "深色").doClick(); key("control EQUALS"); window.validate(); return null; });
        robot.waitForIdle();
        render("preview-markdown-dark.png");
        edt(() -> {
            check(FlatLaf.isLafDark()); check(window.previewMode);
            check(window.preview.pane().getStyledDocument().getLength() > 0);
            menu("视图", "浅色").doClick(); key("control 0");
            return null;
        });
        edt(() -> {
            key("control E"); window.validate();
            return null;
        });
        waitFor(() -> edt(() -> !window.previewMode));
        edt(() -> {
            check(window.area.isShowing());
            eq(2, window.model.getSelectionStart()); eq(4, window.model.getSelectionEnd());
            eq(text, window.model.getText());
            check(!window.model.isModified());
            return null;
        });
        JMenu languages = (JMenu) menu("视图", "语言");
        edt(() -> {
            for (int i = 0; i < languages.getItemCount(); i++)
                if (languages.getItem(i).getText().equals("Java")) languages.getItem(i).doClick();
            return null;
        });
        edt(() -> {
            eq(txteditor.core.text.LanguageId.JAVA, window.language);
            check(!menu("视图", "预览 Markdown").isEnabled()); // 非 Markdown 无预览
            for (int i = 0; i < languages.getItemCount(); i++)
                if (languages.getItem(i).getText().equals("Markdown")) languages.getItem(i).doClick();
            return null;
        });
        edt(() -> { check(menu("视图", "预览 Markdown").isEnabled()); return null; });
        System.out.println("GuiTest: language detection, preview toggle, zoom and theme in preview checked");
    }

    private static void previews(Robot robot) throws Exception {
        String scale = UIScale.getUserScaleFactor() == 1.5f ? "-150" : "";
        edt(() -> { window.setSize(UIScale.scale(new Dimension(1080, 780))); window.validate(); checkLayout(window.getContentPane()); return null; });
        render("preview-light" + scale + ".png");
        edt(() -> {
            menu("查找", "替换…").doClick(); window.query.setText("中文"); window.replacement.setText("文字");
            window.area.setCaretPosition(0); window.find(); menu("视图", "深色").doClick(); window.validate();
            checkLayout(window.getContentPane()); return null;
        });
        render("preview-dark" + scale + ".png");
        edt(() -> { window.setSize(window.getMinimumSize()); window.validate(); checkLayout(window.getContentPane()); return null; });
        render("preview-dark-compact" + scale + ".png");
        edt(() -> { menu("视图", "浅色").doClick(); window.validate(); checkLayout(window.getContentPane()); return null; });
        render("preview-light-compact" + scale + ".png");
        edt(() -> { key("ESCAPE"); window.setSize(UIScale.scale(new Dimension(1080, 780))); window.validate(); return null; });
        render("windows-render.png");
        robot.waitForIdle();
        BufferedImage screen = robot.createScreenCapture(edt(() -> window.getBounds()));
        ImageIO.write(screen, "png", Paths.get("build", "windows-screen-attempt" + scale + ".png").toFile());
        int colored = 0;
        for (int y = 0; y < screen.getHeight(); y += 8) for (int x = 0; x < screen.getWidth(); x += 8)
            if ((screen.getRGB(x, y) & 0xffffff) != 0) colored++;
        System.out.println("GuiTest: Robot screen capture non-black samples=" + colored + "; component renders are separate evidence");
    }

    private static void checkLayout(Container parent) {
        for (Component c : parent.getComponents()) {
            if (!c.isVisible()) continue;
            if (c instanceof JButton || c instanceof JTextField) {
                check(c.getWidth() >= c.getMinimumSize().width);
                check(c.getHeight() >= c.getPreferredSize().height);
                check(c.getX() >= 0 && c.getX() + c.getWidth() <= parent.getWidth());
                if (c instanceof JButton && ((JButton) c).getAction() != null) {
                    JButton b = (JButton) c;
                    check(b.getToolTipText() != null); check(b.getAccessibleContext().getAccessibleName() != null);
                }
            }
            if (c instanceof JLabel) {
                // File paths intentionally use JLabel ellipsis; other labels must fit.
                JLabel label = (JLabel) c;
                if (label.getToolTipText() == null) check(c.getWidth() >= c.getPreferredSize().width);
            }
            if (c instanceof Container) checkLayout((Container) c);
        }
        check(window.area.getVisibleRect().height >= UIScale.scale(100));
    }

    private static void checkContrast() {
        check(contrast(window.area.getForeground(), window.area.getBackground()) >= 4.5);
        check(contrast(UIManager.getColor("Label.disabledForeground"), UIManager.getColor("Panel.background")) >= 4.5);
        check(contrast(Color.WHITE, new Color(0x2563EB)) >= 4.5);
    }

    private static double contrast(Color a, Color b) {
        double x = luminance(a), y = luminance(b); return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }

    private static double luminance(Color c) {
        double[] values = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < values.length; i++) values[i] = values[i] <= .04045 ? values[i] / 12.92 : Math.pow((values[i] + .055) / 1.055, 2.4);
        return .2126 * values[0] + .7152 * values[1] + .0722 * values[2];
    }

    private static void render(String name) throws Exception {
        BufferedImage render = edt(() -> {
            JRootPane root = window.getRootPane();
            BufferedImage image = new BufferedImage(root.getWidth(), root.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose(); return image;
        });
        ImageIO.write(render, "png", Paths.get("build", name).toFile());
    }

    /** JTextPane 没有 append；写文档才会经过 DocumentFilter 落进模型。 */
    private static void insert(EditorWindow target, String text) {
        try {
            javax.swing.text.Document document = target.area.getDocument();
            document.insertString(document.getLength(), text, null);
        } catch (javax.swing.text.BadLocationException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void key(String stroke) {
        Object name = window.area.getInputMap().get(KeyStroke.getKeyStroke(stroke));
        Action action = window.area.getActionMap().get(name); check(action != null);
        action.actionPerformed(new ActionEvent(window.area, ActionEvent.ACTION_PERFORMED, "test"));
    }

    private static JButton button(Container parent, String name) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton && name.equals(((JButton) c).getAccessibleContext().getAccessibleName())) return (JButton) c;
            if (c instanceof Container) { JButton found = button((Container) c, name); if (found != null) return found; }
        }
        return null;
    }

    /** 支持「文件 → 新建 → 纯文本（.txt）」这样的子菜单路径。 */
    private static JMenuItem menu(String... path) {
        JMenuBar bar = window.getJMenuBar();
        JMenu menu = null;
        for (int i = 0; i < bar.getMenuCount(); i++) if (bar.getMenu(i).getText().equals(path[0])) menu = bar.getMenu(i);
        if (menu == null) throw new AssertionError(path[0]);
        for (int i = 1; i < path.length; i++) {
            JMenuItem found = null;
            for (int j = 0; j < menu.getItemCount(); j++) if (menu.getItem(j) != null && menu.getItem(j).getText().equals(path[i])) found = menu.getItem(j);
            if (found == null) throw new AssertionError(path[i]);
            if (i == path.length - 1) return found;
            menu = (JMenu) found;
        }
        return menu;
    }
    private static void invokeMenu(String... path) { SwingUtilities.invokeLater(() -> menu(path).doClick()); }
    private static void choose(Path path) throws Exception {
        waitFor(() -> edt(() -> findShowing(JFileChooser.class) != null));
        edt(() -> { JFileChooser chooser = findShowing(JFileChooser.class); chooser.setSelectedFile(path.toFile()); chooser.approveSelection(); return null; });
    }
    private static void clickDialog(String name) throws Exception {
        waitFor(() -> edt(() -> findButton(name) != null));
        edt(() -> { findButton(name).doClick(); return null; });
    }
    private static void clickAnyError() throws Exception {
        waitFor(() -> edt(() -> findShowing(JOptionPane.class) != null));
        edt(() -> { JOptionPane pane = findShowing(JOptionPane.class); JButton button = find(pane, JButton.class); button.doClick(); return null; });
    }
    private static JButton findButton(String name) {
        for (Window frame : Window.getWindows()) if (frame instanceof JDialog && frame.isShowing()) {
            JButton button = findButtonIn(frame, name); if (button != null) return button;
        }
        return null;
    }
    private static JButton findButtonIn(Container parent, String name) {
        for (Component c : parent.getComponents()) {
            if (c instanceof JButton && name.equals(((JButton) c).getText())) return (JButton) c;
            if (c instanceof Container) { JButton found = findButtonIn((Container) c, name); if (found != null) return found; }
        }
        return null;
    }
    private static <T> T findShowing(Class<T> type) {
        for (Window frame : Window.getWindows()) if (frame.isShowing()) { T result = find(frame, type); if (result != null) return result; }
        return null;
    }
    private static <T> T find(Container parent, Class<T> type) {
        if (type.isInstance(parent)) return type.cast(parent);
        for (Component c : parent.getComponents()) if (c instanceof Container) { T result = find((Container) c, type); if (result != null) return result; }
        return null;
    }
    private static void waitFor(Callable<Boolean> ready) throws Exception {
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!ready.call()) { if (System.nanoTime() > deadline) throw new AssertionError("GUI wait timed out"); Thread.sleep(40); }
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static void check(boolean condition) { checks++; if (!condition) throw new AssertionError("check " + checks); }
    private static void eq(Object expected, Object actual) { checks++; if (!expected.equals(actual)) throw new AssertionError(escaped(expected) + " != " + escaped(actual)); }
    private static String escaped(Object value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toString().toCharArray()) out.append(String.format("\\u%04x", (int) c));
        return out.toString();
    }
}

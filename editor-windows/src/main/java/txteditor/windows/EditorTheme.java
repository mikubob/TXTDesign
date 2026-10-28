package txteditor.windows;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import java.util.HashMap;
import java.util.Map;
import javax.swing.UIManager;

/** One palette for the window, standard Swing controls and dialogs. */
final class EditorTheme {
    static void install(boolean dark) {
        Map<String, String> defaults = new HashMap<String, String>();
        defaults.put("@accentColor", dark ? "#60A5FA" : "#2563EB");
        defaults.put("@background", dark ? "#101722" : "#F1F5F9");
        defaults.put("@foreground", dark ? "#E5ECF6" : "#172033");
        defaults.put("@componentBackground", dark ? "#192231" : "#FFFFFF");
        defaults.put("Component.borderColor", dark ? "#334155" : "#DCE3ED");
        defaults.put("Label.disabledForeground", dark ? "#A3B1C6" : "#58677F");
        defaults.put("TextArea.background", "$TextField.background");
        defaults.put("TextArea.selectionBackground", dark ? "#26486F" : "#DBEAFE");
        defaults.put("TextArea.selectionForeground", "$TextArea.foreground");
        defaults.put("Button.arc", "10");
        defaults.put("Component.arc", "10");
        defaults.put("TextComponent.arc", "10");
        defaults.put("Component.focusWidth", "1");
        defaults.put("Button.margin", "7,12,7,12");
        defaults.put("TextField.margin", "7,10,7,10");
        defaults.put("ScrollBar.width", "10");
        defaults.put("ScrollBar.thumbArc", "999");
        defaults.put("ScrollBar.thumbInsets", "2,2,2,2");
        defaults.put("ScrollBar.showButtons", "false");
        defaults.put("TitlePane.menuBarEmbedded", "false");
        defaults.put("[style]Panel.surface", "background: $TextField.background");
        defaults.put("[style]Label.muted", "foreground: $Label.disabledForeground");
        defaults.put("[style]Label.badge", "foreground: $Component.accentColor");
        defaults.put("[style]Button.primary", "background: #2563EB; foreground: #FFFFFF; borderColor: #2563EB; focusedBorderColor: #2563EB; hoverBackground: #1D4ED8; pressedBackground: #1E40AF");
        FlatLaf.setGlobalExtraDefaults(defaults);
        FlatLaf.setUseNativeWindowDecorations(true);
        if (!(dark ? FlatDarkLaf.setup() : FlatLightLaf.setup())) {
            throw new IllegalStateException("无法初始化 FlatLaf 主题");
        }
        UIManager.put("defaultFont", javax.swing.text.StyleContext.getDefaultStyleContext()
                .getFont("Microsoft YaHei UI", java.awt.Font.PLAIN, 14));
    }

    private EditorTheme() { }
}

package txteditor.windows;

import com.formdev.flatlaf.icons.FlatAbstractIcon;
import java.awt.*;
import java.awt.geom.Path2D;
import javax.swing.UIManager;

/** Small scalable outline icons; text input and controls remain standard Swing. */
final class EditorIcon extends FlatAbstractIcon {
    enum Kind { NEW, OPEN, SAVE, UNDO, REDO, SEARCH, CLOSE, THEME }
    private final Kind kind;

    EditorIcon(Kind kind) { super(20, 20, null); this.kind = kind; }

    @Override protected void paintIcon(Component c, Graphics2D g) {
        g.setColor(c.isEnabled() ? c.getForeground() : UIManager.getColor("Label.disabledForeground"));
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case NEW:
                line(g, 11, 2, 5, 2, 5, 18, 16, 18, 16, 7, 11, 2, 11, 7, 16, 7);
                line(g, 8, 12, 13, 12); line(g, 10.5, 9.5, 10.5, 14.5); break;
            case OPEN:
                line(g, 2, 16, 2, 5, 8, 5, 10, 7, 18, 7, 18, 9);
                line(g, 2, 16, 5, 10, 19, 10, 16, 16, 2, 16); break;
            case SAVE:
                line(g, 3, 3, 14, 3, 17, 6, 17, 17, 3, 17, 3, 3);
                line(g, 6, 3, 6, 8, 13, 8, 13, 3); line(g, 6, 17, 6, 12, 14, 12, 14, 17); break;
            case REDO:
                g.translate(20, 0); g.scale(-1, 1);
                // Both history directions share the same path.
            case UNDO:
                line(g, 7, 4, 3, 8, 7, 12); line(g, 3, 8, 12, 8);
                g.drawArc(8, 8, 9, 8, -90, 180); line(g, 10, 16, 12, 16); break;
            case SEARCH:
                g.drawOval(3, 2, 11, 11); line(g, 12, 12, 18, 18); break;
            case CLOSE:
                line(g, 5, 5, 15, 15); line(g, 15, 5, 5, 15); break;
            case THEME:
                g.drawOval(4, 4, 12, 12); g.fillArc(4, 4, 12, 12, 90, 180); break;
            default: throw new AssertionError(kind);
        }
    }

    private static void line(Graphics2D g, double... points) {
        Path2D path = new Path2D.Double(); path.moveTo(points[0], points[1]);
        for (int i = 2; i < points.length; i += 2) path.lineTo(points[i], points[i + 1]);
        g.draw(path);
    }
}

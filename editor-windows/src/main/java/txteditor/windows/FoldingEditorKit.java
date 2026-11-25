package txteditor.windows;

import java.awt.Graphics;
import java.awt.Shape;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;

/** 让被折叠的段落在布局中占零高度，文档仍保留完整源码。 */
final class FoldingEditorKit extends StyledEditorKit {
    static final String HIDDEN = "txtdesign.folded";
    private final ViewFactory regular = super.getViewFactory();

    @Override public ViewFactory getViewFactory() {
        return element -> "paragraph".equals(element.getName()) ? new FoldableParagraph(element) : regular.create(element);
    }

    private static final class FoldableParagraph extends ParagraphView {
        FoldableParagraph(Element element) { super(element); }

        private boolean hidden() { return Boolean.TRUE.equals(getElement().getAttributes().getAttribute(HIDDEN)); }

        @Override public float getPreferredSpan(int axis) { return hidden() ? 0 : super.getPreferredSpan(axis); }
        @Override public float getMinimumSpan(int axis) { return hidden() ? 0 : super.getMinimumSpan(axis); }
        @Override public float getMaximumSpan(int axis) { return hidden() ? 0 : super.getMaximumSpan(axis); }
        @Override public void paint(Graphics g, Shape allocation) { if (!hidden()) super.paint(g, allocation); }
    }
}

package dxvfix.gui;

import java.awt.*;

/**
 * A FlowLayout that actually wraps: {@code FlowLayout} itself already wraps components onto new
 * rows when the container is narrower than one row needs, but its {@code preferredLayoutSize}/
 * {@code minimumLayoutSize} calculations assume a single row regardless, so a parent container
 * using those hints (e.g. {@code BorderLayout.NORTH}) never allocates the extra height the
 * wrapped rows actually need — the overflow rows just get clipped. This override computes the
 * preferred/minimum size for the width the container *actually has* (falling back to the nearest
 * ancestor's width, or unwrapped behavior before the component tree has any width yet).
 */
final class WrapLayout extends FlowLayout {

    WrapLayout(int align) {
        super(align);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return layoutSize(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension minimum = layoutSize(target, false);
        minimum.width -= (getHgap() + 1);
        return minimum;
    }

    private Dimension layoutSize(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = referenceWidth(target);
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }

            int hgap = getHgap();
            int vgap = getVgap();
            Insets insets = target.getInsets();
            int maxRowWidth = targetWidth - (insets.left + insets.right + hgap * 2);

            Dimension result = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;

            int componentCount = target.getComponentCount();
            for (int i = 0; i < componentCount; i++) {
                Component c = target.getComponent(i);
                if (!c.isVisible()) continue;

                Dimension d = preferred ? c.getPreferredSize() : c.getMinimumSize();
                if (rowWidth + d.width > maxRowWidth && rowWidth > 0) {
                    // start a new row
                    result.width = Math.max(result.width, rowWidth);
                    result.height += rowHeight + vgap;
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth > 0) rowWidth += hgap;
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            result.width = Math.max(result.width, rowWidth);
            result.height += rowHeight;

            result.width += insets.left + insets.right + hgap * 2;
            result.height += insets.top + insets.bottom + vgap * 2;
            return result;
        }
    }

    /** The width to wrap against: the target's own width if it's been laid out, else the nearest ancestor's. */
    private static int referenceWidth(Container target) {
        if (target.getWidth() > 0) {
            return target.getWidth();
        }
        Container p = target.getParent();
        while (p != null) {
            if (p.getWidth() > 0) return p.getWidth();
            p = p.getParent();
        }
        return 0;
    }
}

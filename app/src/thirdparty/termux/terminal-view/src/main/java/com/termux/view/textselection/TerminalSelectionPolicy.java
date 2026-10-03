package com.termux.view.textselection;

/**
 * Where a long press lands as a selection — the pure half of
 * {@link TextSelectionCursorController#setInitialTextSelectionPosition}.
 *
 * Upstream only expands the pressed cell to its word, and gives up entirely
 * when that cell is whitespace: the selection stays one cell wide, so a press
 * anywhere past the end of a line selected a single blank, drew no highlight
 * and {@code Copy} wrote an empty string. In the docked session pane that cell
 * is almost always blank — the pane is ~240 dp wide, the numbers a shell prints
 * sit in the first columns and the finger lands past them — which is why the
 * toolbar came up with nothing selected.
 *
 * The rules below keep the upstream word selection and add the two fallbacks
 * that make a press on empty space select something the user can see:
 *
 * <ol>
 *   <li>the word under the finger, when there is one (upstream);</li>
 *   <li>otherwise the pressed line's whole content, trimmed of its trailing
 *       blanks;</li>
 *   <li>otherwise — a completely blank line — the nearest line that does have
 *       content, searched downwards and then upwards, so the screen's last
 *       output is what a press on the blank tail of the buffer picks up;</li>
 *   <li>a wholly blank screen selects the first row, which is what the
 *       renderer then draws as the (empty) highlight.</li>
 * </ol>
 */
public final class TerminalSelectionPolicy {

    /** An inclusive column range on one row. */
    public static final class Span {
        public final int x1;
        public final int x2;

        public Span(int x1, int x2) {
            this.x1 = Math.min(x1, x2);
            this.x2 = Math.max(x1, x2);
        }
    }

    private TerminalSelectionPolicy() {
    }

    /** Whether the cell is part of a word — anything that is not a blank. */
    public static boolean isWordChar(char c) {
        return c != ' ' && c != '\t' && c != 0;
    }

    /** The word [line] has around [column], or `null` when that cell is blank. */
    public static Span wordAt(CharSequence line, int column) {
        if (line == null || line.length() == 0) return null;
        int col = clamp(column, 0, line.length() - 1);
        if (!isWordChar(line.charAt(col))) return null;
        int start = col;
        int end = col;
        while (start > 0 && isWordChar(line.charAt(start - 1))) start--;
        while (end < line.length() - 1 && isWordChar(line.charAt(end + 1))) end++;
        return new Span(start, end);
    }

    /** The line's content without its leading and trailing blanks, or `null`. */
    public static Span contentOf(CharSequence line) {
        if (line == null) return null;
        int start = 0;
        int end = line.length() - 1;
        while (start <= end && !isWordChar(line.charAt(start))) start++;
        while (end >= start && !isWordChar(line.charAt(end))) end--;
        if (start > end) return null;
        return new Span(start, end);
    }

    /**
     * The selection a long press at ([column], [row]) opens, over a screen of
     * [columns] x [rows] whose lines come from [lineAt] (rows outside the screen
     * read as blank).
     */
    public static Span initial(LineAt lineAt, int row, int column, int columns, int rows) {
        int safeRow = clamp(row, 0, Math.max(0, rows - 1));
        int safeColumn = clamp(column, 0, Math.max(0, columns - 1));
        Span span = wordAt(lineAt.at(safeRow), safeColumn);
        if (span != null) return span;
        span = contentOf(lineAt.at(safeRow));
        if (span != null) return span;
        for (int r = safeRow + 1; r < rows; r++) {
            span = contentOf(lineAt.at(r));
            if (span != null) return new Span(span.x1, span.x2);
        }
        for (int r = safeRow - 1; r >= 0; r--) {
            span = contentOf(lineAt.at(r));
            if (span != null) return new Span(span.x1, span.x2);
        }
        return new Span(0, Math.max(0, columns - 1));
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) return min;
        return value < min ? min : (value > max ? max : value);
    }

    /** One screen line, so the policy stays free of the buffer's own API. */
    public interface LineAt {
        CharSequence at(int row);
    }
}

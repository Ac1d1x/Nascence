package com.termux.terminal;

/**
 * <p>
 * Encodes effects, foreground and background colors into a 64 bit long, which are stored for each cell in a terminal
 * row in {@link TerminalRow#mStyle}.
 * </p>
 * <p>
 * The bit layout is:
 * </p>
 * - 16 flags (14 currently used).
 * - 24 for foreground color (only 9 first bits if a color index).
 * - 24 for background color (only 9 first bits if a color index).
 */
public final class TextStyle {

    public final static int CHARACTER_ATTRIBUTE_BOLD = 1;
    public final static int CHARACTER_ATTRIBUTE_ITALIC = 1 << 1;
    public final static int CHARACTER_ATTRIBUTE_UNDERLINE = 1 << 2;
    public final static int CHARACTER_ATTRIBUTE_BLINK = 1 << 3;
    public final static int CHARACTER_ATTRIBUTE_INVERSE = 1 << 4;
    public final static int CHARACTER_ATTRIBUTE_INVISIBLE = 1 << 5;
    public final static int CHARACTER_ATTRIBUTE_STRIKETHROUGH = 1 << 6;
    /**
     * The selective erase control functions (DECSED and DECSEL) can only erase characters defined as erasable.
     * <p>
     * This bit is set if DECSCA (Select Character Protection Attribute) has been used to define the characters that
     * come after it as erasable from the screen.
     * </p>
     */
    public final static int CHARACTER_ATTRIBUTE_PROTECTED = 1 << 7;
    /** Dim colors. Also known as faint or half intensity. */
    public final static int CHARACTER_ATTRIBUTE_DIM = 1 << 8;
    /** If true (24-bit) color is used for the cell for foreground. */
    private final static int CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND = 1 << 9;
    /** If true (24-bit) color is used for the cell for foreground. */
    private final static int CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND= 1 << 10;
    /** If true, character represents a {@link TerminalBitmap} slice, not text. */
    public final static int TERMINAL_BITMAP = 1 << 15;

    /** Underline style values, matching the kitty underline specification (SGR 4:x). */
    public final static int UNDERLINE_STYLE_NONE = 0;
    public final static int UNDERLINE_STYLE_SINGLE = 1;
    public final static int UNDERLINE_STYLE_DOUBLE = 2;
    public final static int UNDERLINE_STYLE_CURLY = 3;
    public final static int UNDERLINE_STYLE_DOTTED = 4;
    public final static int UNDERLINE_STYLE_DASHED = 5;

    /** The underline style occupies three otherwise unused effect bits (11-13). */
    private final static int UNDERLINE_STYLE_SHIFT = 11;
    private final static int UNDERLINE_STYLE_MASK = 0b111 << UNDERLINE_STYLE_SHIFT;

    /** All the effect bits that {@link #encode(int, int, int)} keeps. */
    private final static int EFFECT_MASK = 0b11111111111111;

    /** Extra per-cell style data: set if an explicit underline color is stored. */
    private final static long EXTRA_UNDERLINE_COLOR_PRESENT = 1L << 24;
    /** Extra per-cell style data: set if the underline color is an indexed palette entry. */
    private final static long EXTRA_UNDERLINE_COLOR_INDEXED = 1L << 25;
    /** Extra per-cell style data: shifted hyperlink table id (0 means none). */
    private final static int EXTRA_HYPERLINK_SHIFT = 32;
    private final static long EXTRA_HYPERLINK_MASK = 0xFFFFL << EXTRA_HYPERLINK_SHIFT;

    public final static int COLOR_INDEX_FOREGROUND = 256;
    public final static int COLOR_INDEX_BACKGROUND = 257;
    public final static int COLOR_INDEX_CURSOR = 258;

    /** The 256 standard color entries and the three special (foreground, background and cursor) ones. */
    public final static int NUM_INDEXED_COLORS = 259;

    /** Normal foreground and background colors and no effects. */
    final static long NORMAL = encode(COLOR_INDEX_FOREGROUND, COLOR_INDEX_BACKGROUND, 0);

    static long encode(int foreColor, int backColor, int effect) {
        long result = effect & EFFECT_MASK;
        if ((0xff000000 & foreColor) == 0xff000000) {
            // 24-bit color.
            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND | ((foreColor & 0x00ffffffL) << 40L);
        } else {
            // Indexed color.
            result |= (foreColor & 0b111111111L) << 40;
        }
        if ((0xff000000 & backColor) == 0xff000000) {
            // 24-bit color.
            result |= CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND | ((backColor & 0x00ffffffL) << 16L);
        } else {
            // Indexed color.
            result |= (backColor & 0b111111111L) << 16L;
        }

        return result;
    }

    public static int decodeForeColor(long style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_FOREGROUND) == 0) {
            return (int) ((style >>> 40) & 0b111111111L);
        } else {
            return 0xff000000 | (int) ((style >>> 40) & 0x00ffffffL);
        }

    }

    public static int decodeBackColor(long style) {
        if ((style & CHARACTER_ATTRIBUTE_TRUECOLOR_BACKGROUND) == 0) {
            return (int) ((style >>> 16) & 0b111111111L);
        } else {
            return 0xff000000 | (int) ((style >>> 16) & 0x00ffffffL);
        }
    }

    public static int decodeEffect(long style) {
        return (int) (style & (EFFECT_MASK & ~UNDERLINE_STYLE_MASK));
    }

    public static int decodeUnderlineStyle(long style) {
        return (int) ((style & UNDERLINE_STYLE_MASK) >>> UNDERLINE_STYLE_SHIFT);
    }

    public static long encodeUnderlineStyle(long style, int underlineStyle) {
        return (style & ~UNDERLINE_STYLE_MASK)
            | (((long) (underlineStyle & 0b111)) << UNDERLINE_STYLE_SHIFT);
    }

    /**
     * Encode the extended per-cell attributes that do not fit in the 64 bit style.
     *
     * @param underlineColor the explicit underline color as ARGB, or a negative value to use the
     *                       foreground color.
     * @param hyperlinkId    the id of an OSC 8 hyperlink, or 0 for none.
     */
    public static long encodeExtra(int underlineColor, int hyperlinkId) {
        long extra = 0;
        if (underlineColor != -1) {
            extra |= EXTRA_UNDERLINE_COLOR_PRESENT;
            if ((underlineColor & 0xff000000) == 0xff000000) {
                extra |= underlineColor & 0x00FFFFFFL;
            } else {
                extra |= EXTRA_UNDERLINE_COLOR_INDEXED | (underlineColor & 0b111111111L);
            }
        }
        if (hyperlinkId > 0) {
            extra |= ((long) (hyperlinkId & 0xFFFF)) << EXTRA_HYPERLINK_SHIFT;
        }
        return extra;
    }

    /**
     * @return the explicit underline color as ARGB, an indexed palette entry (0-258) if the high
     * alpha byte is not 0xff, or -1 to use the foreground color.
     */
    public static int decodeUnderlineColor(long extra) {
        if ((extra & EXTRA_UNDERLINE_COLOR_PRESENT) == 0) return -1;
        if ((extra & EXTRA_UNDERLINE_COLOR_INDEXED) != 0) return (int) (extra & 0b111111111L);
        return 0xff000000 | (int) (extra & 0x00FFFFFFL);
    }

    /** @return the OSC 8 hyperlink id, or 0 if the cell is not part of a hyperlink. */
    public static int decodeHyperlinkId(long extra) {
        return (int) ((extra & EXTRA_HYPERLINK_MASK) >>> EXTRA_HYPERLINK_SHIFT);
    }



    public static long encodeTerminalBitmap(int num, int x, int y) {
        return ((long) x << 48) | ((long) y << 32) | ((long) num << 16) | TERMINAL_BITMAP;
    }

    public static boolean isTerminalBitmap(long style) {
        return (style & TERMINAL_BITMAP) != 0;
    }

    /* The bitmap num, x or y could have value `0`, so only return value (especially `0`) if bitmap bit is set. */

    public static int getTerminalBitmapNum(long style) {
        return (style & TERMINAL_BITMAP) != 0 ? (int) (style & 0xffff0000L) >> 16 : -1;
    }

    public static int getTerminalBitmapX(long style) {
        return (style & TERMINAL_BITMAP) != 0 ? (int) ((style >> 48) & 0xfff) : -1;
    }

    public static int getTerminalBitmapY(long style) {
        return (style & TERMINAL_BITMAP) != 0 ? (int) ((style >> 32) & 0xfff) : -1;
    }

}

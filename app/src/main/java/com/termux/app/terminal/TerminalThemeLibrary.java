package com.termux.app.terminal;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.terminal.TerminalColors;
import com.termux.terminal.TextStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in terminal color themes. A theme is applied on top of the colors loaded from
 * {@code ~/.termux/colors.properties}; choosing "Follow colors.properties" removes the override.
 */
public final class TerminalThemeLibrary {

    private static final String PREFS_NAME = "nascence_terminal";
    private static final String PREF_THEME_ID = "theme_id";

    public static final class Theme {
        public final String id;
        public final String name;
        public final int background;
        public final int foreground;
        public final int cursor;
        /** The 16 ANSI colors, in order. */
        public final int[] ansi;

        Theme(String id, String name, int background, int foreground, int cursor, int[] ansi) {
            this.id = id;
            this.name = name;
            this.background = background;
            this.foreground = foreground;
            this.cursor = cursor;
            this.ansi = ansi;
        }
    }

    private static final List<Theme> THEMES;

    static {
        List<Theme> themes = new ArrayList<>();
        themes.add(new Theme("nascence_dark", "Nascence Dark", 0xFF0D0F14, 0xFFE6E6E6, 0xFF7AA2F7,
            new int[]{0xFF15161B, 0xFFF07178, 0xFFC3E88D, 0xFFFFCB6B, 0xFF82AAFF, 0xFFC792EA, 0xFF89DDFF, 0xFFD0D0D0,
                0xFF4A4E5A, 0xFFFF8B92, 0xFFD7FFA0, 0xFFFFE082, 0xFF9CC4FF, 0xFFDDB6FF, 0xFFA6E3FF, 0xFFFFFFFF}));
        themes.add(new Theme("tokyo_night", "Tokyo Night", 0xFF1A1B26, 0xFFC0CAF5, 0xFF7AA2F7,
            new int[]{0xFF15161E, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFA9B1D6,
                0xFF414868, 0xFFF7768E, 0xFF9ECE6A, 0xFFE0AF68, 0xFF7AA2F7, 0xFFBB9AF7, 0xFF7DCFFF, 0xFFC0CAF5}));
        themes.add(new Theme("catppuccin_mocha", "Catppuccin Mocha", 0xFF1E1E2E, 0xFFCDD6F4, 0xFFF5E0DC,
            new int[]{0xFF45475A, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFBAC2DE,
                0xFF585B70, 0xFFF38BA8, 0xFFA6E3A1, 0xFFF9E2AF, 0xFF89B4FA, 0xFFF5C2E7, 0xFF94E2D5, 0xFFA6ADC8}));
        themes.add(new Theme("gruvbox_dark", "Gruvbox Dark", 0xFF282828, 0xFFEBDBB2, 0xFFFABD2F,
            new int[]{0xFF282828, 0xFFCC241D, 0xFF98971A, 0xFFD79921, 0xFF458588, 0xFFB16286, 0xFF689D6A, 0xFFA89984,
                0xFF928374, 0xFFFB4934, 0xFFB8BB26, 0xFFFABD2F, 0xFF83A598, 0xFFD3869B, 0xFF8EC07C, 0xFFEBDBB2}));
        themes.add(new Theme("nord", "Nord", 0xFF2E3440, 0xFFD8DEE9, 0xFF88C0D0,
            new int[]{0xFF3B4252, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF88C0D0, 0xFFE5E9F0,
                0xFF4C566A, 0xFFBF616A, 0xFFA3BE8C, 0xFFEBCB8B, 0xFF81A1C1, 0xFFB48EAD, 0xFF8FBCBB, 0xFFECEFF4}));
        themes.add(new Theme("dracula", "Dracula", 0xFF282A36, 0xFFF8F8F2, 0xFFFF79C6,
            new int[]{0xFF21222C, 0xFFFF5555, 0xFF50FA7B, 0xFFF1FA8C, 0xFFBD93F9, 0xFFFF79C6, 0xFF8BE9FD, 0xFFF8F8F2,
                0xFF6272A4, 0xFFFF6E6E, 0xFF69FF94, 0xFFFFFFA5, 0xFFD6ACFF, 0xFFFF92DF, 0xFFA4FFFF, 0xFFFFFFFF}));
        themes.add(new Theme("one_dark", "One Dark", 0xFF282C34, 0xFFABB2BF, 0xFF528BFF,
            new int[]{0xFF282C34, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFABB2BF,
                0xFF5C6370, 0xFFE06C75, 0xFF98C379, 0xFFE5C07B, 0xFF61AFEF, 0xFFC678DD, 0xFF56B6C2, 0xFFFFFFFF}));
        themes.add(new Theme("solarized_dark", "Solarized Dark", 0xFF002B36, 0xFF839496, 0xFF93A1A1,
            new int[]{0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3}));
        themes.add(new Theme("solarized_light", "Solarized Light", 0xFFFDF6E3, 0xFF657B83, 0xFF586E75,
            new int[]{0xFF073642, 0xFFDC322F, 0xFF859900, 0xFFB58900, 0xFF268BD2, 0xFFD33682, 0xFF2AA198, 0xFFEEE8D5,
                0xFF002B36, 0xFFCB4B16, 0xFF586E75, 0xFF657B83, 0xFF839496, 0xFF6C71C4, 0xFF93A1A1, 0xFFFDF6E3}));
        themes.add(new Theme("rose_pine", "Rosé Pine", 0xFF191724, 0xFFE0DEF4, 0xFFEBBCBA,
            new int[]{0xFF26233A, 0xFFEB6F92, 0xFF9CCFD8, 0xFFF6C177, 0xFF31748F, 0xFFC4A7E7, 0xFF9CCFD8, 0xFFE0DEF4,
                0xFF6E6A86, 0xFFEB6F92, 0xFF9CCFD8, 0xFFF6C177, 0xFF31748F, 0xFFC4A7E7, 0xFF9CCFD8, 0xFFE0DEF4}));
        themes.add(new Theme("everforest", "Everforest", 0xFF2D353B, 0xFFD3C6AA, 0xFFA7C080,
            new int[]{0xFF343F44, 0xFFE67E80, 0xFFA7C080, 0xFFDBBC7F, 0xFF7FBBB3, 0xFFD699B6, 0xFF83C092, 0xFFD3C6AA,
                0xFF7A8478, 0xFFE67E80, 0xFFA7C080, 0xFFDBBC7F, 0xFF7FBBB3, 0xFFD699B6, 0xFF83C092, 0xFFD3C6AA}));
        themes.add(new Theme("kanagawa", "Kanagawa", 0xFF1F1F28, 0xFFDCD7BA, 0xFF7E9CD8,
            new int[]{0xFF16161D, 0xFFC34043, 0xFF76946A, 0xFFC0A36E, 0xFF7E9CD8, 0xFF957FB8, 0xFF6A9589, 0xFFC8C093,
                0xFF727169, 0xFFE82424, 0xFF98BB6C, 0xFFE6C384, 0xFF7FB4CA, 0xFF938AA9, 0xFF7AA89F, 0xFFDCD7BA}));
        THEMES = Collections.unmodifiableList(themes);
    }

    private TerminalThemeLibrary() {
    }

    public static List<Theme> getThemes() {
        return THEMES;
    }

    public static Theme findById(String id) {
        if (id == null) return null;
        for (Theme theme : THEMES) if (theme.id.equals(id)) return theme;
        return null;
    }

    public static String getSelectedThemeId(Context context) {
        return prefs(context).getString(PREF_THEME_ID, "");
    }

    public static void setSelectedThemeId(Context context, String id) {
        prefs(context).edit().putString(PREF_THEME_ID, id == null ? "" : id).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Apply the persisted theme to the given colors, if one is selected. */
    public static boolean applySelectedTheme(Context context, TerminalColors colors) {
        Theme theme = findById(getSelectedThemeId(context));
        if (theme == null) return false;
        apply(theme, colors);
        return true;
    }

    public static void apply(Theme theme, TerminalColors colors) {
        if (colors == null) return;
        for (int i = 0; i < 16; i++) colors.mCurrentColors[i] = theme.ansi[i];

        // xterm 6x6x6 color cube.
        int[] levels = {0, 95, 135, 175, 215, 255};
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int index = 16 + 36 * r + 6 * g + b;
                    colors.mCurrentColors[index] = 0xFF000000
                        | levels[r] << 16 | levels[g] << 8 | levels[b];
                }
            }
        }
        // Grayscale ramp.
        for (int i = 0; i < 24; i++) {
            int value = 8 + i * 10;
            colors.mCurrentColors[232 + i] = 0xFF000000 | value << 16 | value << 8 | value;
        }

        colors.mCurrentColors[TextStyle.COLOR_INDEX_FOREGROUND] = theme.foreground;
        colors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = theme.background;
        colors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] = theme.cursor;
    }

    /** Restore the colors loaded from colors.properties. */
    public static void resetToDefaults(TerminalColors colors) {
        if (colors != null) colors.reset();
    }
}

package com.termux.app.terminal;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.content.res.ResourcesCompat;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.view.ViewUtils;

import java.io.File;

/**
 * Holds the typeface used by both the terminal and the app UI.
 *
 * <p>If the user provides {@code ~/.termux/font.ttf} it is used as the primary face, otherwise the
 * bundled JetBrains Mono is used. Both are paired with the bundled Noto Sans CJK fallback so that
 * Chinese text is rendered without tofu.
 */
public final class AppTypeface {

    private static final String LOG_TAG = "AppTypeface";

    private static Typeface sRegular;
    private static Typeface sBold;
    private static File sCustomFont;
    private static long sFontLength;
    private static long sFontLastModified;
    private static boolean sLoaded;

    private AppTypeface() {}

    /**
     * Reload the typefaces if the custom font file changed. This also publishes the typeface so
     * that dialogs created by {@code termux-shared} can use it.
     */
    public static synchronized void reload(@NonNull Context context) {
        Context appContext = context.getApplicationContext();

        File fontFile = TermuxConstants.TERMUX_FONT_FILE;
        boolean hasCustomFont = fontFile.isFile() && fontFile.length() > 0;
        long length = hasCustomFont ? fontFile.length() : 0;
        long lastModified = hasCustomFont ? fontFile.lastModified() : 0;

        if (sLoaded && hasCustomFont == (sCustomFont != null)
            && length == sFontLength && lastModified == sFontLastModified) {
            publishOverride();
            return;
        }

        Typeface regular = null;
        Typeface bold = null;
        if (hasCustomFont) {
            regular = buildCustomTypeface(appContext, fontFile, false);
            bold = buildCustomTypeface(appContext, fontFile, true);
            if (regular == null) {
                Logger.logWarn(LOG_TAG, "Failed to load custom font, falling back to bundled font: " + fontFile);
                hasCustomFont = false;
            }
        }

        if (!hasCustomFont) {
            regular = buildBundledTypeface(appContext, false);
            bold = buildBundledTypeface(appContext, true);
        }

        if (regular == null) regular = Typeface.MONOSPACE;
        if (bold == null) bold = regular;

        sRegular = regular;
        sBold = bold;
        sCustomFont = hasCustomFont ? fontFile : null;
        sFontLength = length;
        sFontLastModified = lastModified;
        sLoaded = true;

        publishOverride();
    }

    public static synchronized boolean hasCustomFont() {
        return sCustomFont != null;
    }

    @NonNull
    public static synchronized Typeface getRegular(@NonNull Context context) {
        if (!sLoaded) reload(context);
        return sRegular != null ? sRegular : Typeface.MONOSPACE;
    }

    @NonNull
    public static synchronized Typeface getBold(@NonNull Context context) {
        if (!sLoaded) reload(context);
        return sBold != null ? sBold : getRegular(context);
    }

    /**
     * Apply the app typeface to every {@link android.widget.TextView} below {@code root}. Safe to
     * call repeatedly; it keeps the bold style of each individual view.
     */
    public static void applyToViewTree(@Nullable View root) {
        if (root == null) return;
        if (!sLoaded) reload(root.getContext());
        ViewUtils.applyOverrideTypeface(root);
    }

    private static void publishOverride() {
        ViewUtils.setOverrideTypeface(sRegular, sBold);
    }

    @Nullable
    private static Typeface buildBundledTypeface(@NonNull Context context, boolean bold) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                AssetManager assets = context.getAssets();
                Font primary = new Font.Builder(assets,
                    "fonts/JetBrainsMono-" + (bold ? "Bold" : "Regular") + ".ttf").build();
                Font fallback = new Font.Builder(assets,
                    "fonts/NotoSansCJKsc-" + (bold ? "Bold" : "Regular") + ".otf").build();
                return buildWithFallback(primary, fallback);
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to build bundled terminal typeface", t);
            }
        }

        return ResourcesCompat.getFont(context,
            bold ? R.font.jetbrains_mono_bold : R.font.jetbrains_mono_regular);
    }

    @Nullable
    private static Typeface buildCustomTypeface(@NonNull Context context, @NonNull File fontFile,
                                                boolean bold) {
        Typeface typeface = null;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                Font primary = new Font.Builder(fontFile).build();
                Font fallback = new Font.Builder(context.getAssets(),
                    "fonts/NotoSansCJKsc-" + (bold ? "Bold" : "Regular") + ".otf").build();
                typeface = buildWithFallback(primary, fallback);
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG,
                    "Failed to build custom terminal typeface with CJK fallback", t);
            }
        }

        if (typeface == null) {
            try {
                typeface = Typeface.createFromFile(fontFile);
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load custom terminal typeface", t);
                return null;
            }
        }

        if (bold) typeface = Typeface.create(typeface, Typeface.BOLD);
        return typeface;
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    @NonNull
    private static Typeface buildWithFallback(@NonNull Font primary, @NonNull Font fallback) {
        return new Typeface.CustomFallbackBuilder(new FontFamily.Builder(primary).build())
            .addCustomFallback(new FontFamily.Builder(fallback).build())
            .setSystemFallback("sans-serif")
            .build();
    }
}

package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    /** The typeface used for bold runs, or {@link #mTypeface} itself if the font has no separate bold face. */
    final Typeface mBoldTypeface;
    private final Paint mTextPaint = new Paint();
    /** Paint used for the custom underline styles, so it does not disturb the text paint. */
    private final Paint mUnderlinePaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /**
     * Cursor glide animation, in the spirit of Neovide: when the cursor moves by a cell or two,
     * it is interpolated to its new position instead of jumping there.
     */
    // Neovide cursor: the cursor is a quad whose four corners are animated by damped springs.
    private static final float CURSOR_ANIM_LENGTH_S = 0.10f;
    private static final float CURSOR_SHORT_ANIM_LENGTH_S = 0.09f;
    /** Maximum uniform scale applied to CJK glyphs so they don't look oversized. */
    private static final float CJK_MAX_SCALE = 1.1f;
    /** 0 = no stretch, 1 = leading corners snap instantly so the cursor smears behind the movement. */
    private static final float CURSOR_TRAIL_SIZE = 1.0f;

    private final CursorCorner[] mCursorCorners = {
        new CursorCorner(-0.5f, -0.5f),
        new CursorCorner(0.5f, -0.5f),
        new CursorCorner(0.5f, 0.5f),
        new CursorCorner(-0.5f, 0.5f)
    };
    private boolean mCursorInitialized;
    private boolean mCursorAnimating;
    private long mCursorLastFrameTimeMs;
    private int mCursorLastTopRow = Integer.MIN_VALUE;
    private float mCursorDestCenterX;
    private float mCursorDestCenterY;
    private final Path mCursorPath = new Path();

    private static final int MAX_CURSOR_PARTICLES = 96;
    private final ArrayList<CursorParticle> mCursorParticles = new ArrayList<>();
    private final Random mCursorParticleRandom = new Random();

    /** Search highlight spans: {externalRow, startColumn, endColumnInclusive}. */
    private List<int[]> mSearchHighlights;
    private int mCurrentSearchHighlight = -1;

    public TerminalRenderer(int textSize, Typeface typeface) {
        this(textSize, typeface, typeface);
    }

    public TerminalRenderer(int textSize, Typeface typeface, Typeface boldTypeface) {
        mTextSize = textSize;
        mTypeface = typeface;
        mBoldTypeface = boldTypeface != null ? boldTypeface : typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // JetBrains Mono and most programming fonts ship their ligatures in calt/liga.
            mTextPaint.setFontFeatureSettings("'calt' on, 'liga' on, 'clig' on");
        }
        mUnderlinePaint.setAntiAlias(true);
        mUnderlinePaint.setStyle(Paint.Style.FILL);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        render(mEmulator, canvas, topRow, 0f, selectionY1, selectionY2, selectionX1, selectionX2);
    }

    /**
     * Render the terminal with a fractional row offset for smooth scrolling.
     *
     * @param topRow the anchor row (the row partially visible at the top when {@code topRowOffset} is non-zero)
     * @param topRowOffset how far the view is scrolled between {@code topRow} and the next row, in the range 0..1
     */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow, float topRowOffset,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int extraRow = topRowOffset > 0f ? 1 : 0;
        final int endRow = topRow + mEmulator.mRows + extraRow;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        // The cursor is always drawn as an overlay, never inside the text runs, so its shape can deform while gliding.
        float cursorWidth = mFontWidth;
        float cursorHeight = mFontLineSpacing;
        float cursorTop = (cursorRow - topRow + 1) * (float) mFontLineSpacing + mFontAscent;
        if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) {
            cursorTop += mFontLineSpacing * 0.75f;
            cursorHeight = mFontLineSpacing / 4f;
        } else if (cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
            cursorWidth = mFontWidth / 4f;
        }
        boolean forceCursorVisible = updateCursorAnimation(cursorCol * mFontWidth + cursorWidth / 2f,
            cursorTop + cursorHeight / 2f, cursorWidth, cursorHeight, cursorVisible,
            mEmulator.isCursorEnabled(), topRow, palette[TextStyle.COLOR_INDEX_CURSOR]);
        if (forceCursorVisible) {
            // The cursor moved while it was hidden by its blink phase: show it again so the glide is visible.
            mEmulator.setCursorBlinkState(true);
        }

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        if (topRowOffset > 0f) {
            canvas.save();
            canvas.translate(0f, -topRowOffset * mFontLineSpacing);
        }

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            long lastRunExtraStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final long style = lineObject.getStyle(column);
                final long extraStyle = lineObject.getExtraStyle(column);
                if (TextStyle.isTerminalBitmap(style)) {
                    Bitmap bitmap = mEmulator.getScreen().getSixelBitmap(style);
                    if (bitmap != null) {
                        float left = column * mFontWidth;
                        float top = heightOffset - mFontLineSpacing;
                        Rect bitmapSrcRect = mEmulator.getScreen().getSixelRect(style);
                        RectF bitmapDestRect = new RectF(left, top, left + mFontWidth, top + mFontLineSpacing);
                        canvas.drawBitmap(bitmap, bitmapSrcRect, bitmapDestRect, null);
                    }
                    column += 1;
                    measuredWidthForRun = 0.f;
                    lastRunStyle = 0;
                    lastRunExtraStyle = 0;
                    lastRunInsideCursor = false;
                    lastRunStartColumn = column + 1;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = false;
                    currentCharIndex += charsForCodePoint;
                    continue;
                }
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || extraStyle != lastRunExtraStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0 || column == lastRunStartColumn) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, row, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, lastRunExtraStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunExtraStyle = extraStyle;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, row, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, lastRunExtraStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }

        if (cursorVisible || forceCursorVisible) {
            drawCursorOverlay(canvas, screen, palette, topRow, cursorShape, columns, mEmulator.mRows);
        }
        if (!mCursorParticles.isEmpty()) {
            drawCursorParticles(canvas);
        }

        if (topRowOffset > 0f) {
            canvas.restore();
        }

        drawProgressBar(canvas, mEmulator, columns);
    }

    /** Draw the OSC 9;4 progress indicator as a slim bar on top of the terminal. */
    private void drawProgressBar(Canvas canvas, TerminalEmulator emulator, int columns) {
        int state = emulator.getProgressState();
        if (state <= 0) return;

        float width = columns * mFontWidth;
        float height = Math.max(2f, mFontLineSpacing * 0.07f);
        int color;
        switch (state) {
            case 2: color = 0xFFEF5350; break;
            case 4: color = 0xFFFFB300; break;
            default: color = 0xFF4CAF50; break;
        }
        mUnderlinePaint.setStyle(Paint.Style.FILL);

        if (state == 3) {
            // Indeterminate: a short segment sliding from left to right.
            float segment = width * 0.25f;
            float travel = width + segment;
            float x = (SystemClock.uptimeMillis() % 1400L) / 1400f * travel - segment;
            mUnderlinePaint.setColor(color);
            canvas.drawRect(Math.max(0f, x), 0f, Math.min(width, x + segment), height, mUnderlinePaint);
        } else {
            int progress = Math.max(0, Math.min(100, emulator.getProgressValue()));
            mUnderlinePaint.setColor((color & 0x00FFFFFF) | (0x33 << 24));
            canvas.drawRect(0f, 0f, width, height, mUnderlinePaint);
            mUnderlinePaint.setColor(color);
            canvas.drawRect(0f, 0f, width * progress / 100f, height, mUnderlinePaint);
        }
    }

    /** Advance the Neovide-style cursor quad animation and its typing particles. */
    private boolean updateCursorAnimation(float centerX, float centerY, float width, float height,
                                          boolean visible, boolean enabled, int topRow, int cursorColor) {
        long now = SystemClock.uptimeMillis();
        float dt = Math.min((now - mCursorLastFrameTimeMs) / 1000f, 1f / 30f);
        mCursorLastFrameTimeMs = now;

        if (!mCursorInitialized) {
            for (CursorCorner corner : mCursorCorners) {
                corner.setPosition(centerX, centerY, width, height);
            }
            mCursorInitialized = true;
            mCursorLastTopRow = topRow;
            mCursorDestCenterX = centerX;
            mCursorDestCenterY = centerY;
            mCursorParticles.clear();
            mCursorAnimating = false;
            return false;
        }

        boolean scrolled = topRow != mCursorLastTopRow;
        boolean jumped = centerX != mCursorDestCenterX || centerY != mCursorDestCenterY;
        boolean shortJump = false;
        if (jumped) {
            float oldCenterX = mCursorDestCenterX;
            float oldCenterY = mCursorDestCenterY;
            float jumpX = (centerX - oldCenterX) / width;
            float jumpY = (centerY - oldCenterY) / height;
            // A move of at most two cells on a single line is a quick, unstretched glide.
            shortJump = Math.abs(jumpX) <= 2.001f && Math.abs(jumpY) <= 0.001f;
            mCursorDestCenterX = centerX;
            mCursorDestCenterY = centerY;
            if (!scrolled) {
                spawnCursorParticles(oldCenterX, oldCenterY, centerX, centerY, width, height, jumpX, jumpY, cursorColor);
            }
        }

        boolean forceVisible = false;
        boolean animating = false;
        if (!enabled || scrolled) {
            // Truly hidden by the application or the viewport scrolled: snap instead of gliding.
            for (CursorCorner corner : mCursorCorners) {
                corner.setPosition(centerX, centerY, width, height);
            }
            if (scrolled) mCursorParticles.clear();
        } else {
            // A blink-hidden cursor still animates: if it moved, force it visible so the glide can be seen.
            if (jumped) {
                applyCursorJump(centerX, centerY, width, height, shortJump);
                if (!visible) forceVisible = true;
            }
            for (CursorCorner corner : mCursorCorners) {
                animating |= corner.update(centerX, centerY, width, height, dt);
            }
            animating |= jumped;
        }

        if (!mCursorParticles.isEmpty()) {
            for (int i = mCursorParticles.size() - 1; i >= 0; i--) {
                CursorParticle particle = mCursorParticles.get(i);
                if (now - particle.bornMs >= particle.lifeMs) {
                    mCursorParticles.remove(i);
                    continue;
                }
                particle.x += particle.vx * dt;
                particle.y += particle.vy * dt;
                float damping = Math.max(0f, 1f - 2.5f * dt);
                particle.vx *= damping;
                particle.vy *= damping;
            }
            animating = true;
        }

        mCursorLastTopRow = topRow;
        mCursorAnimating = animating;
        return forceVisible;
    }

    /** Spawn a small burst of dust particles along the path the cursor just travelled. */
    private void spawnCursorParticles(float fromCenterX, float fromCenterY, float toCenterX, float toCenterY,
                                      float width, float height, float jumpX, float jumpY, int cursorColor) {
        float distance = (float) Math.hypot(jumpX, jumpY);
        int count = Math.min(24, 5 + (int) (distance * 2f));
        long now = SystemClock.uptimeMillis();

        float directionX = toCenterX - fromCenterX;
        float directionY = toCenterY - fromCenterY;
        float directionLength = (float) Math.hypot(directionX, directionY);
        if (directionLength > 0.01f) {
            directionX /= directionLength;
            directionY /= directionLength;
        } else {
            directionX = 1f;
            directionY = 0f;
        }

        for (int i = 0; i < count; i++) {
            if (mCursorParticles.size() >= MAX_CURSOR_PARTICLES) break;

            CursorParticle particle = new CursorParticle();
            float along = mCursorParticleRandom.nextFloat() * 0.8f;
            particle.x = fromCenterX + (toCenterX - fromCenterX) * along
                + (mCursorParticleRandom.nextFloat() - 0.5f) * width;
            particle.y = fromCenterY + (toCenterY - fromCenterY) * along
                + (mCursorParticleRandom.nextFloat() - 0.5f) * height * 0.6f;

            float speed = (40f + mCursorParticleRandom.nextFloat() * 110f)
                * (0.7f + 0.3f * Math.min(distance, 6f));
            float spread = speed * (0.7f + 0.6f * mCursorParticleRandom.nextFloat());
            particle.vx = -directionX * speed + (mCursorParticleRandom.nextFloat() - 0.5f) * spread;
            particle.vy = -directionY * speed + (mCursorParticleRandom.nextFloat() - 0.5f) * spread
                - mCursorParticleRandom.nextFloat() * 30f;

            particle.radius = mFontWidth * (0.05f + mCursorParticleRandom.nextFloat() * 0.07f);
            particle.bornMs = now;
            particle.lifeMs = 260L + mCursorParticleRandom.nextInt(260);
            particle.color = cursorColor;
            mCursorParticles.add(particle);
        }
    }

    private void drawCursorParticles(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        mTextPaint.setStyle(Paint.Style.FILL);
        for (CursorParticle particle : mCursorParticles) {
            float progress = (now - particle.bornMs) / (float) particle.lifeMs;
            if (progress >= 1f) continue;
            int alpha = (int) ((1f - progress) * 220f);
            int rgb = particle.color & 0x00FFFFFF;
            float radius = particle.radius * (1f - progress * 0.4f);

            mTextPaint.setColor(((alpha / 4) << 24) | rgb);
            canvas.drawCircle(particle.x, particle.y, radius * 2.2f, mTextPaint);
            mTextPaint.setColor((alpha << 24) | rgb);
            canvas.drawCircle(particle.x, particle.y, radius, mTextPaint);
        }
    }

    /**
     * Neovide's cursor smear: corners pointing towards the travel direction get a shorter animation
     * and snap ahead, while the trailing corners lag behind and stretch the cursor.
     */
    private void applyCursorJump(float centerX, float centerY, float width, float height, boolean shortJump) {
        float[] alignments = new float[mCursorCorners.length];
        for (int i = 0; i < mCursorCorners.length; i++) {
            CursorCorner corner = mCursorCorners[i];
            float destX = centerX + corner.relX * width;
            float destY = centerY + corner.relY * height;
            float travelX = destX - corner.currentX;
            float travelY = destY - corner.currentY;
            float travelLength = (float) Math.hypot(travelX, travelY);
            if (travelLength > 0f) {
                travelX /= travelLength;
                travelY /= travelLength;
            }
            float cornerLength = (float) Math.hypot(corner.relX, corner.relY);
            float cornerDirX = cornerLength > 0f ? corner.relX / cornerLength : 0f;
            float cornerDirY = cornerLength > 0f ? corner.relY / cornerLength : 0f;
            alignments[i] = travelX * cornerDirX + travelY * cornerDirY;
        }

        int[] ranks = new int[mCursorCorners.length];
        for (int i = 0; i < mCursorCorners.length; i++) {
            int rank = 0;
            for (int j = 0; j < mCursorCorners.length; j++) {
                if (alignments[j] < alignments[i] || (alignments[j] == alignments[i] && j < i)) rank++;
            }
            ranks[i] = rank;
        }

        float leading = CURSOR_ANIM_LENGTH_S * (1f - CURSOR_TRAIL_SIZE);
        float trailing = CURSOR_ANIM_LENGTH_S;
        for (int i = 0; i < mCursorCorners.length; i++) {
            CursorCorner corner = mCursorCorners[i];
            if (shortJump) {
                corner.animationLength = CURSOR_SHORT_ANIM_LENGTH_S;
            } else if (ranks[i] >= 2) {
                corner.animationLength = leading;
            } else if (ranks[i] == 1) {
                corner.animationLength = (leading + trailing) / 2f;
            } else {
                corner.animationLength = trailing;
            }
            corner.animX.reset();
            corner.animY.reset();
        }
    }

    /** Draw the cursor quad with a soft glow and keep the glyphs inside it readable. */
    private void drawCursorOverlay(Canvas canvas, TerminalBuffer screen, int[] palette, int topRow,
                                   int cursorStyle, int columns, int rows) {
        mCursorPath.reset();
        mCursorPath.moveTo(mCursorCorners[0].currentX, mCursorCorners[0].currentY);
        for (int i = 1; i < mCursorCorners.length; i++) {
            mCursorPath.lineTo(mCursorCorners[i].currentX, mCursorCorners[i].currentY);
        }
        mCursorPath.close();

        int cursorColor = palette[TextStyle.COLOR_INDEX_CURSOR];
        int rgb = cursorColor & 0x00FFFFFF;

        // Soft glow, approximating Neovide's cursor shadow.
        mTextPaint.setStyle(Paint.Style.STROKE);
        mTextPaint.setStrokeJoin(Paint.Join.ROUND);
        float glowWidth = Math.max(2f, mFontWidth * 0.16f);
        mTextPaint.setColor((0x1A << 24) | rgb);
        mTextPaint.setStrokeWidth(glowWidth * 2f);
        canvas.drawPath(mCursorPath, mTextPaint);
        mTextPaint.setColor((0x33 << 24) | rgb);
        mTextPaint.setStrokeWidth(glowWidth);
        canvas.drawPath(mCursorPath, mTextPaint);

        mTextPaint.setStyle(Paint.Style.FILL);
        mTextPaint.setColor(cursorColor);
        canvas.drawPath(mCursorPath, mTextPaint);

        if (cursorStyle != TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) return;

        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (CursorCorner corner : mCursorCorners) {
            minX = Math.min(minX, corner.currentX);
            minY = Math.min(minY, corner.currentY);
            maxX = Math.max(maxX, corner.currentX);
            maxY = Math.max(maxY, corner.currentY);
        }

        canvas.save();
        canvas.clipPath(mCursorPath);
        drawCursorGlyphs(canvas, screen, palette, topRow, columns, rows, minX, minY, maxX, maxY);
        canvas.restore();
    }

    /** Redraw the glyphs under the cursor with the cell background color, so they stay readable. */
    private void drawCursorGlyphs(Canvas canvas, TerminalBuffer screen, int[] palette, int topRow,
                                  int columns, int rows, float left, float top, float right, float bottom) {
        int firstColumn = Math.max(0, (int) Math.floor(left / mFontWidth));
        int lastColumn = Math.min(columns - 1, (int) Math.floor((right - 0.01f) / mFontWidth));
        int firstScreenRow = Math.max(0, (int) Math.floor(top / mFontLineSpacing) - 1);
        int lastScreenRow = Math.min(rows - 1, (int) Math.floor(bottom / mFontLineSpacing) + 1);

        for (int screenRow = firstScreenRow; screenRow <= lastScreenRow; screenRow++) {
            TerminalRow line = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(topRow + screenRow));
            final char[] text = line.mText;
            final int charsUsed = line.getSpaceUsed();
            final float baseline = (screenRow + 1) * (float) mFontLineSpacing;

            int charIndex = 0;
            int column = 0;
            while (charIndex < charsUsed && column <= lastColumn) {
                final char c = text[charIndex];
                final boolean highSurrogate = Character.isHighSurrogate(c);
                final int charsForCodePoint = highSurrogate ? 2 : 1;
                final int codePoint = highSurrogate ? Character.toCodePoint(c, text[charIndex + 1]) : c;
                final int width = WcWidth.width(codePoint);
                if (width <= 0) {
                    charIndex += charsForCodePoint;
                    continue;
                }

                if (column + width > firstColumn) {
                    int runEnd = charIndex + charsForCodePoint;
                    while (runEnd < charsUsed && WcWidth.width(text, runEnd) <= 0) {
                        runEnd += Character.isHighSurrogate(text[runEnd]) ? 2 : 1;
                    }

                    long style = line.getStyle(column);
                    int effect = TextStyle.decodeEffect(style);
                    boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
                    boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
                    int backColor = TextStyle.decodeBackColor(style);
                    if ((backColor & 0xff000000) != 0xff000000) backColor = palette[backColor];

                    mTextPaint.setColor(backColor);
                    mTextPaint.setTypeface(bold ? mBoldTypeface : mTypeface);
                    mTextPaint.setFakeBoldText(bold && mBoldTypeface == mTypeface);
                    mTextPaint.setTextSkewX(italic ? -0.35f : 0f);
                    mTextPaint.setUnderlineText(false);
                    mTextPaint.setStrikeThruText(false);

                    canvas.drawText(text, charIndex, runEnd - charIndex, column * mFontWidth, baseline, mTextPaint);
                    charIndex = runEnd;
                } else {
                    charIndex += charsForCodePoint;
                }
                column += width;
            }
        }
    }

    /** Whether the cursor is currently gliding and the view needs another frame. */
    public boolean isCursorAnimating() {
        return mCursorAnimating;
    }

    /** Neovide's damped spring, operating on the remaining distance to the destination. */
    private static final class DampedSpring {
        float position;
        float velocity;

        boolean update(float dt, float animationLength) {
            if (animationLength <= dt || position == 0f) {
                reset();
                return false;
            }
            float omega = 4f / animationLength;
            float a = position;
            float b = position * omega + velocity;
            float c = (float) Math.exp(-omega * dt);
            position = (a + b * dt) * c;
            velocity = c * (-a * omega - b * dt * omega + b);
            if (Math.abs(position) < 0.01f) {
                reset();
                return false;
            }
            return true;
        }

        void reset() {
            position = 0f;
            velocity = 0f;
        }
    }

    /** One corner of the cursor quad, with independent X/Y spring animations. */
    private static final class CursorCorner {
        final float relX;
        final float relY;
        float currentX;
        float currentY;
        float prevDestX = -10000f;
        float prevDestY = -10000f;
        final DampedSpring animX = new DampedSpring();
        final DampedSpring animY = new DampedSpring();
        float animationLength = CURSOR_ANIM_LENGTH_S;

        CursorCorner(float relX, float relY) {
            this.relX = relX;
            this.relY = relY;
        }

        void setPosition(float centerX, float centerY, float width, float height) {
            currentX = centerX + relX * width;
            currentY = centerY + relY * height;
            prevDestX = currentX;
            prevDestY = currentY;
            animX.reset();
            animY.reset();
        }

        boolean update(float centerX, float centerY, float width, float height, float dt) {
            float destX = centerX + relX * width;
            float destY = centerY + relY * height;
            if (destX != prevDestX || destY != prevDestY) {
                animX.position = destX - currentX;
                animY.position = destY - currentY;
                prevDestX = destX;
                prevDestY = destY;
            }
            boolean animatingX = animX.update(dt, animationLength);
            boolean animatingY = animY.update(dt, animationLength);
            currentX = destX - animX.position;
            currentY = destY - animY.position;
            return animatingX || animatingY;
        }
    }

    /** A short-lived dust particle emitted when the cursor moves. */
    private static final class CursorParticle {
        float x;
        float y;
        float vx;
        float vy;
        float radius;
        long bornMs;
        long lifeMs;
        int color;
    }

    private void drawTextRun(Canvas canvas, int row, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, long extraStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;
        final int hyperlinkId = TextStyle.decodeHyperlinkId(extraStyle);
        final int rawUnderlineColor = TextStyle.decodeUnderlineColor(extraStyle);

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        boolean wideRun = containsWideCharacter(text, startCharIndex, runWidthChars);

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (!wideRun && Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.f;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= (((right - left) * 3) / 4.f);
            float cursorRadius = Math.min(right - left, cursorHeight) * 0.18f;
            canvas.drawRoundRect(left, y - cursorHeight, right, y, cursorRadius, cursorRadius, mTextPaint);
        }

        drawSearchHighlights(canvas, row, startColumn, runWidthColumns, left, right, y);

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setTypeface(bold ? mBoldTypeface : mTypeface);
            // Only fall back to synthetic bold if there is no real bold typeface.
            mTextPaint.setFakeBoldText(bold && mBoldTypeface == mTypeface);
            mTextPaint.setUnderlineText(false);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            float baseline = y - mFontLineSpacingAndAscent;

            // Give bold text a soft glow in a brightened shade of its own color.
            int textLuminance = (0xFF & (foreColor >> 16)) * 299 + (0xFF & (foreColor >> 8)) * 587 + (0xFF & foreColor) * 114;
            boolean useGlow = bold && textLuminance >= 128 * 1000;
            if (useGlow) {
                int glowRed = Math.min(255, ((foreColor >> 16) & 0xFF) + 130);
                int glowGreen = Math.min(255, ((foreColor >> 8) & 0xFF) + 130);
                int glowBlue = Math.min(255, (foreColor & 0xFF) + 130);
                int glowColor = (0xCC << 24) | (glowRed << 16) | (glowGreen << 8) | glowBlue;
                mTextPaint.setShadowLayer(Math.max(3f, mFontLineSpacing * 0.22f), 0f, 0f, glowColor);
            }

            if (wideRun) {
                drawWideTextRun(canvas, text, startCharIndex, runWidthChars, startColumn, baseline);
            } else {
                drawTextAt(canvas, text, startCharIndex, runWidthChars, left, baseline);
            }

            if (useGlow) {
                mTextPaint.clearShadowLayer();
            }

            if (underline || hyperlinkId != 0) {
                int underlineColor;
                if (rawUnderlineColor == -1) {
                    underlineColor = foreColor;
                } else if ((rawUnderlineColor & 0xff000000) == 0xff000000) {
                    // 24-bit color: the alpha byte is 0xff. Note that this value is a negative int,
                    // so it must be tested before the indexed range check.
                    underlineColor = rawUnderlineColor;
                } else if (rawUnderlineColor < palette.length) {
                    // Indexed palette entry (0-258).
                    underlineColor = palette[rawUnderlineColor];
                } else {
                    underlineColor = foreColor;
                }
                if (underline) {
                    int underlineStyle = TextStyle.decodeUnderlineStyle(textStyle);
                    if (underlineStyle == TextStyle.UNDERLINE_STYLE_NONE)
                        underlineStyle = TextStyle.UNDERLINE_STYLE_SINGLE;
                    drawUnderline(canvas, left, right, baseline, underlineStyle, underlineColor);
                } else {
                    // OSC 8 hyperlink without an explicit underline: a subtle dotted hint.
                    drawUnderline(canvas, left, right, baseline, TextStyle.UNDERLINE_STYLE_DOTTED,
                        (underlineColor & 0x00FFFFFF) | (0x99 << 24));
                }
            }
        }

        if (savedMatrix) canvas.restore();
    }

    private void drawSearchHighlights(Canvas canvas, int row, int startColumn, int runWidthColumns,
                                      float left, float right, float y) {
        List<int[]> highlights = mSearchHighlights;
        if (highlights == null || highlights.isEmpty()) return;

        int runEndColumn = startColumn + runWidthColumns - 1;
        float cellWidth = runWidthColumns > 0 ? (right - left) / runWidthColumns : mFontWidth;
        float top = y - mFontLineSpacingAndAscent + mFontAscent;
        for (int i = 0; i < highlights.size(); i++) {
            int[] highlight = highlights.get(i);
            if (highlight[0] != row || highlight[2] < startColumn || highlight[1] > runEndColumn) continue;

            int firstColumn = Math.max(highlight[1], startColumn);
            int lastColumn = Math.min(highlight[2], runEndColumn);
            float x1 = left + (firstColumn - startColumn) * cellWidth;
            float x2 = left + (lastColumn - startColumn + 1) * cellWidth;

            mUnderlinePaint.setStyle(Paint.Style.FILL);
            mUnderlinePaint.setColor(i == mCurrentSearchHighlight ? 0x66FFB300 : 0x40FFD54F);
            canvas.drawRect(x1, top, x2, y, mUnderlinePaint);

            if (i == mCurrentSearchHighlight) {
                mUnderlinePaint.setStyle(Paint.Style.STROKE);
                mUnderlinePaint.setStrokeWidth(Math.max(1.2f, mFontWidth * 0.06f));
                mUnderlinePaint.setColor(0xAAFF9800);
                canvas.drawRect(x1, top, x2, y, mUnderlinePaint);
            }
        }
    }

    /**
     * Draw the kitty underline styles (single, double, curly, dotted and dashed) ourselves, since
     * Android's Paint only knows a plain single underline.
     */
    private void drawUnderline(Canvas canvas, float left, float right, float baseline, int style, int color) {
        float thickness = Math.max(1.4f, mTextSize * 0.055f);
        // A row ends at the font descent. Drawing the decoration there (with a tiny overlap into
        // the inter-row gap) keeps descenders such as g, j, p and y clear of it.
        float descent = Math.max(mFontLineSpacing + mFontAscent, mTextSize * 0.17f);
        float bottom = baseline + descent + thickness * 0.6f;
        float amplitude = Math.max(1.2f, thickness * 0.95f);
        mUnderlinePaint.setColor(color);
        mUnderlinePaint.setShadowLayer(0f, 0f, 0f, 0);

        switch (style) {
            case TextStyle.UNDERLINE_STYLE_DOUBLE: {
                mUnderlinePaint.setStyle(Paint.Style.FILL);
                float top = bottom - thickness * 3.1f;
                canvas.drawRect(left, top, right, top + thickness, mUnderlinePaint);
                canvas.drawRect(left, top + thickness * 2.1f, right, bottom, mUnderlinePaint);
                break;
            }
            case TextStyle.UNDERLINE_STYLE_CURLY: {
                mUnderlinePaint.setStyle(Paint.Style.STROKE);
                mUnderlinePaint.setStrokeWidth(thickness);
                Path path = new Path();
                float wavelength = Math.max(mFontWidth * 0.7f, thickness * 4f);
                float center = bottom - amplitude;
                boolean first = true;
                for (float x = left; x <= right; x += Math.max(1f, wavelength / 8f)) {
                    float wy = center + amplitude * (float) Math.sin((x - left) / wavelength * Math.PI * 2f);
                    if (first) {
                        path.moveTo(x, wy);
                        first = false;
                    } else {
                        path.lineTo(x, wy);
                    }
                }
                canvas.drawPath(path, mUnderlinePaint);
                break;
            }
            case TextStyle.UNDERLINE_STYLE_DOTTED: {
                mUnderlinePaint.setStyle(Paint.Style.FILL);
                float radius = thickness * 0.7f;
                float spacing = Math.max(mFontWidth * 0.32f, radius * 2.6f);
                float center = bottom - radius;
                for (float x = left + spacing / 2f; x < right; x += spacing) {
                    canvas.drawCircle(x, center, radius, mUnderlinePaint);
                }
                break;
            }
            case TextStyle.UNDERLINE_STYLE_DASHED: {
                mUnderlinePaint.setStyle(Paint.Style.FILL);
                float top = bottom - thickness;
                float dash = Math.max(mFontWidth * 0.45f, thickness * 3f);
                float gap = Math.max(mFontWidth * 0.28f, thickness * 2f);
                for (float x = left; x < right; x += dash + gap) {
                    canvas.drawRect(x, top, Math.min(x + dash, right), bottom, mUnderlinePaint);
                }
                break;
            }
            case TextStyle.UNDERLINE_STYLE_SINGLE:
            default:
                mUnderlinePaint.setStyle(Paint.Style.FILL);
                canvas.drawRect(left, bottom - thickness, right, bottom, mUnderlinePaint);
                break;
        }
    }

    private void drawTextAt(Canvas canvas, char[] text, int startCharIndex, int runWidthChars, float left, float baseline) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, baseline, false, mTextPaint);
        } else {
            canvas.drawText(text, startCharIndex, runWidthChars, left, baseline, mTextPaint);
        }
    }

    /** Whether a run contains any wide (usually CJK) code point. */
    private boolean containsWideCharacter(char[] text, int startCharIndex, int runWidthChars) {
        int end = startCharIndex + runWidthChars;
        for (int i = startCharIndex; i < end; ) {
            char c = text[i];
            boolean highSurrogate = Character.isHighSurrogate(c);
            int charsForCodePoint = highSurrogate ? 2 : 1;
            int codePoint = highSurrogate ? Character.toCodePoint(c, text[i + 1]) : c;
            if (WcWidth.width(codePoint) >= 2) return true;
            i += charsForCodePoint;
        }
        return false;
    }

    /**
     * Draw wide characters one at a time, each centered in its cell and scaled uniformly by at
     * most {@link #CJK_MAX_SCALE}, so CJK is not stretched horizontally or blown up to fill 2 cells.
     */
    private void drawWideTextRun(Canvas canvas, char[] text, int startCharIndex, int runWidthChars,
                                 int startColumn, float baseline) {
        int end = startCharIndex + runWidthChars;
        int column = startColumn;
        int i = startCharIndex;
        while (i < end) {
            char c = text[i];
            boolean highSurrogate = Character.isHighSurrogate(c);
            int charsForCodePoint = highSurrogate ? 2 : 1;
            int codePoint = highSurrogate ? Character.toCodePoint(c, text[i + 1]) : c;
            int codePointWidth = WcWidth.width(codePoint);
            if (codePointWidth <= 0) {
                i += charsForCodePoint;
                continue;
            }

            int runEnd = i + charsForCodePoint;
            while (runEnd < end && WcWidth.width(text, runEnd) <= 0) {
                runEnd += Character.isHighSurrogate(text[runEnd]) ? 2 : 1;
            }

            float slotWidth = codePointWidth * mFontWidth;
            float measuredWidth = mTextPaint.measureText(text, i, runEnd - i);
            float scale = measuredWidth > 0f ? Math.min(CJK_MAX_SCALE, slotWidth / measuredWidth) : 1f;
            float centerX = column * mFontWidth + slotWidth / 2f;
            float drawX = centerX - measuredWidth / 2f;

            boolean scaled = Math.abs(scale - 1f) > 0.001f;
            if (scaled) {
                canvas.save();
                canvas.scale(scale, scale, centerX, baseline);
            }
            drawTextAt(canvas, text, i, runEnd - i, drawX, baseline);
            if (scaled) {
                canvas.restore();
            }

            column += codePointWidth;
            i = runEnd;
        }
    }



    public float getFontWidth() {
        return mFontWidth;
    }

    /** Set the scrollback search highlights, or null to clear them. */
    public void setSearchHighlights(List<int[]> highlights, int currentIndex) {
        mSearchHighlights = highlights;
        mCurrentSearchHighlight = currentIndex;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}

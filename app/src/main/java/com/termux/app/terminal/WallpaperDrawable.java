package com.termux.app.terminal;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Draws a wallpaper bitmap center-cropped to the drawable bounds, with a uniform dim overlay. */
public final class WallpaperDrawable extends Drawable {

    /** Dim level applied to the wallpaper so terminal text stays readable. */
    private static final int DIM_COLOR = 0x66000000;

    private final Bitmap mBitmap;
    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint mDimPaint = new Paint();
    private final RectF mDestination = new RectF();

    public WallpaperDrawable(Bitmap bitmap) {
        mBitmap = bitmap;
        mDimPaint.setColor(DIM_COLOR);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        if (mBitmap == null || bounds.isEmpty()) return;

        float scale = Math.max(bounds.width() / (float) mBitmap.getWidth(),
            bounds.height() / (float) mBitmap.getHeight());
        float width = mBitmap.getWidth() * scale;
        float height = mBitmap.getHeight() * scale;
        float left = bounds.left + (bounds.width() - width) / 2f;
        float top = bounds.top + (bounds.height() - height) / 2f;

        mDestination.set(left, top, left + width, top + height);
        canvas.drawBitmap(mBitmap, null, mDestination, mPaint);
        canvas.drawRect(bounds, mDimPaint);
    }

    public void recycle() {
        if (!mBitmap.isRecycled()) mBitmap.recycle();
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        mPaint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}

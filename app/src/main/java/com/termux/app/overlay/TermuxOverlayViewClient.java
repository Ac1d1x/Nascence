package com.termux.app.overlay;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

/** Minimal {@link TerminalViewClient} for the floating terminal, without any activity references. */
final class TermuxOverlayViewClient implements TerminalViewClient {

    private static final String LOG_TAG = "TermuxOverlayViewClient";

    private final Context mContext;
    private final TermuxOverlayManager mManager;

    private boolean mCtrlDown;
    private boolean mAltDown;

    TermuxOverlayViewClient(@NonNull Context context, @NonNull TermuxOverlayManager manager) {
        mContext = context;
        mManager = manager;
    }

    void toggleCtrl() {
        mCtrlDown = !mCtrlDown;
    }

    void toggleAlt() {
        mAltDown = !mAltDown;
    }

    void resetModifiers() {
        mCtrlDown = false;
        mAltDown = false;
    }

    boolean isCtrlDown() {
        return mCtrlDown;
    }

    boolean isAltDown() {
        return mAltDown;
    }

    /** Modifier mask for the compact extra keys row. */
    int currentModifiers() {
        int modifiers = 0;
        if (mCtrlDown) modifiers |= com.termux.terminal.KeyHandler.KEYMOD_CTRL;
        if (mAltDown) modifiers |= com.termux.terminal.KeyHandler.KEYMOD_ALT;
        return modifiers;
    }

    @Override
    public float onScale(float scale) {
        if (scale < 0.9f || scale > 1.1f) {
            mManager.changeFontSize(scale > 1.f);
            return 1.0f;
        }
        return scale;
    }

    @Override
    public void onSingleTapUp(MotionEvent e) {
        if (mManager.handleTap(e)) return;
        mManager.showKeyboard();
    }

    @Override
    public boolean shouldBackButtonBeMappedToEscape() {
        return false;
    }

    @Override
    public boolean shouldEnforceCharBasedInput() {
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        return properties != null && properties.isEnforcingCharBasedInput();
    }

    @Override
    public boolean shouldUseCtrlSpaceWorkaround() {
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        return properties != null && properties.isUsingCtrlSpaceWorkaround();
    }

    @Override
    public boolean isTerminalViewSelected() {
        return true;
    }

    @Override
    public void copyModeChanged(boolean copyMode) {
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
        return false;
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return false;
    }

    @Override
    public boolean onLongPress(MotionEvent event) {
        return false;
    }

    @Override
    public boolean readControlKey() {
        return mCtrlDown || mManager.readExtraKeysSpecialButton(SpecialButton.CTRL);
    }

    @Override
    public boolean readAltKey() {
        return mAltDown || mManager.readExtraKeysSpecialButton(SpecialButton.ALT);
    }

    @Override
    public boolean readShiftKey() {
        return mManager.readExtraKeysSpecialButton(SpecialButton.SHIFT);
    }

    @Override
    public boolean readFnKey() {
        return mManager.readExtraKeysSpecialButton(SpecialButton.FN);
    }

    @Override
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        return false;
    }

    @Override
    public void onEmulatorSet() {
        mManager.onEmulatorSet();
    }

    @Override
    public void logError(String tag, String message) {
        Logger.logError(tag != null ? tag : LOG_TAG, message);
    }

    @Override
    public void logWarn(String tag, String message) {
        Logger.logWarn(tag != null ? tag : LOG_TAG, message);
    }

    @Override
    public void logInfo(String tag, String message) {
        Logger.logInfo(tag != null ? tag : LOG_TAG, message);
    }

    @Override
    public void logDebug(String tag, String message) {
        Logger.logDebug(tag != null ? tag : LOG_TAG, message);
    }

    @Override
    public void logVerbose(String tag, String message) {
        Logger.logVerbose(tag != null ? tag : LOG_TAG, message);
    }

    @Override
    public void logStackTraceWithMessage(String tag, String message, Exception e) {
        Logger.logStackTraceWithMessage(tag != null ? tag : LOG_TAG, message, e);
    }

    @Override
    public void logStackTrace(String tag, Exception e) {
        Logger.logStackTrace(tag != null ? tag : LOG_TAG, e);
    }
}

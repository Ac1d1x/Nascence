package com.termux.app.overlay;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.terminal.AppTypeface;
import com.termux.app.terminal.TerminalThemeLibrary;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.interact.ShareUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.shell.command.ExecutionCommand.Runner;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.data.TermuxUrlUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TextStyle;
import com.termux.view.TerminalView;

import java.util.LinkedHashSet;
import java.util.function.Consumer;

/**
 * Owns the floating (overlay) terminal: its dedicated shell session, the top-anchored sheet, the
 * drag handle used to expand/collapse it and the optional background blur.
 *
 * <p>The session is deliberately kept out of {@link TermuxShellManager#mTermuxSessions}, so the
 * main activity can never attach a second {@link TerminalView} to it. Two views with different
 * sizes would keep resizing the same PTY and the display would flicker.
 */
public final class TermuxOverlayManager {

    /** Intent action handled by {@link TermuxService} to open or hide the floating terminal. */
    public static final String ACTION_TOGGLE_OVERLAY = "com.termux.app.action.TOGGLE_OVERLAY";

    private static final String LOG_TAG = "TermuxOverlayManager";
    private static final String PREFS_NAME = "nascence_overlay";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_HEIGHT_FRACTION = "height_fraction";

    private static final float DEFAULT_HEIGHT_FRACTION = 0.5f;
    private static final float MIN_HEIGHT_FRACTION = 0.08f;
    private static final float MAX_HEIGHT_FRACTION = 0.92f;
    private static final int PEEK_HEIGHT_DP = 28;
    /** The collapsed state only accepts gestures on a small area around the grab handle. */
    private static final int COLLAPSED_HANDLE_WIDTH_DP = 56;
    private static final int COLLAPSED_HANDLE_HEIGHT_DP = 16;
    private static final int BLUR_RADIUS_DP = 18;

    /** Mirrors {@link #mVisible} for {@link TermuxOverlayTileService}, which runs in this process. */
    private static volatile boolean sVisible;
    /** Mirrors {@code mVisible && !mCollapsed} so other components know the sheet is open. */
    private static volatile boolean sExpanded;

    private final TermuxService mService;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SharedPreferences mPreferences;
    private final OverlaySessionClient mSessionClient = new OverlaySessionClient();
    private final TermuxOverlayViewClient mViewClient;

    private TermuxSession mTermuxSession;
    private TerminalSession mSession;
    /** Session currently owned by the activity after a drawer takeover. */
    @Nullable
    private TerminalSession mHandedOverSession;

    @Nullable
    private View mOverlayView;
    @Nullable
    private View mSheetContent;
    @Nullable
    private Dialog mOverlayDialog;
    @Nullable
    private GradientDrawable mWindowBackground;
    @Nullable
    private TerminalView mTerminalView;
    @Nullable
    private View mGripView;
    @Nullable
    private View mHandleView;
    @Nullable
    private WindowManager.LayoutParams mHandleParams;
    @Nullable
    private WindowManager.LayoutParams mOverlayParams;
    @Nullable
    private ValueAnimator mHeightAnimator;

    private boolean mVisible;
    private boolean mCollapsed;
    private boolean mDestroyed;
    private int mFontSize;
    private float mHeightFraction;

    @Nullable
    private Consumer<Boolean> mBlurListener;
    private boolean mBlurEnabled;
    /** True after the IME has been seen open, used to restore non-focusable mode when it closes. */
    private boolean mImeVisible;
    /** Last radius pushed to the window, so layout updates do not re-issue the same call. */
    private int mAppliedBlurRadius = -1;
    /** RGB of the panel behind the terminal, taken from the active palette. */
    private int mPanelRgb = 0x0B0D12;
    /** Cache key for {@link #applyOverlayChrome(int)} so drags do not rebuild every key drawable. */
    private int mChromeKey = -1;

    public TermuxOverlayManager(@NonNull TermuxService service) {
        mService = service;
        mWindowManager = (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
        mPreferences = service.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mViewClient = new TermuxOverlayViewClient(service, this);

        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(service);
        mFontSize = preferences == null ? 14 : preferences.getFontSize();
        mHeightFraction = clamp(mPreferences.getFloat(KEY_HEIGHT_FRACTION, DEFAULT_HEIGHT_FRACTION),
            MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mWindowManager != null) {
            registerBlurListener();
        }
    }

    public static boolean isOverlayVisible() {
        return sVisible;
    }

    /** True only when the sheet is pulled down, not when it is rolled up to the top handle. */
    public static boolean isOverlayExpanded() {
        return sVisible && sExpanded;
    }

    public boolean isVisible() {
        return mVisible;
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void registerBlurListener() {
        try {
            mBlurEnabled = mWindowManager.isCrossWindowBlurEnabled();
            mBlurListener = enabled -> mHandler.post(() -> {
                mBlurEnabled = enabled;
                applyWindowBackgroundBlur();
                updateOverlayBackground();
            });
            mWindowManager.addCrossWindowBlurEnabledListener(mBlurListener);
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to register cross-window blur listener", t);
        }
    }

    /** True while the floating shell exists or its collapsed handle is on screen. */
    public boolean isActive() {
        return mVisible || (mSession != null && mSession.isRunning());
    }

    /** True while the sheet is rolled up to the top handle. */
    public boolean isCollapsed() {
        return mVisible && mCollapsed;
    }

    public void toggle() {
        mHandler.post(() -> {
            if (!mVisible) {
                show();
            } else if (mCollapsed) {
                expand();
            } else {
                collapse();
            }
        });
    }

    /** Rolls the sheet up to the top handle with an animation. */
    public void collapse() {
        if (!mVisible || mOverlayParams == null) return;
        animateHeightTo(peekHeight(), 0f, this::finishCollapse);
    }

    /** Pulls the sheet back down to its expanded height with an animation. */
    public void expand() {
        if (mDestroyed) return;
        if (!mVisible) {
            show();
            return;
        }
        if (!ensureSession()) {
            toast(R.string.overlay_window_failed);
            return;
        }
        if (mTerminalView != null) mTerminalView.attachSession(mSession);
        if (mOverlayParams == null) return;
        cancelHeightAnimation();
        removeCollapsedHandle();
        setWindowTouchable(true);
        animateHeightTo(expandedHeight(), 0f, null);
    }

    /** Called when the display bounds change, for example on rotation or a fold. */
    public void onConfigurationChanged() {
        mHandler.post(() -> {
            if (mDestroyed) return;
            if (mHandleView != null) {
                // The collapsed state is a standalone window; recenter it for the new width.
                if (mHandleParams != null) {
                    int width = dp(COLLAPSED_HANDLE_WIDTH_DP);
                    mHandleParams.x = Math.max(0, (screenWidth() - width) / 2);
                    try {
                        mWindowManager.updateViewLayout(mHandleView, mHandleParams);
                    } catch (Throwable ignored) {
                    }
                }
                // The collapsed handle is a separate touch window; the sheet itself stays at peek.
                setSheetHeight(peekHeight());
                return;
            }
            if (mVisible) {
                cancelHeightAnimation();
                setSheetContentHeight(expandedHeight());
                setSheetHeight(expandedHeight());
            }
        });
    }

    public void show() {
        if (mDestroyed) return;
        if (!hasOverlayPermission()) {
            toast(R.string.overlay_permission_needed);
            requestOverlayPermission();
            return;
        }
        if (!ensureSession()) {
            toast(R.string.overlay_window_failed);
            return;
        }

        if (mOverlayView == null) {
            createOverlayView();
            if (mOverlayView == null) return;
        } else {
            // A previous session may have exited while the window was hidden.
            mTerminalView.attachSession(mSession);
        }

        if (mVisible) {
            if (mCollapsed) expand();
            return;
        }

        // Pick up font size and theme changes made in the main app since the window was last open.
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mService);
        if (preferences != null && preferences.getFontSize() != mFontSize) {
            mFontSize = preferences.getFontSize();
            if (mTerminalView != null) mTerminalView.setTextSize(mFontSize);
        }
        if (mTerminalView != null && mTerminalView.mRenderer != null)
            mTerminalView.setTypeface(AppTypeface.getRegular(mService), AppTypeface.getBold(mService));
        applyOverlayPalette();

        // Start from the collapsed handle and animate down, so opening the sheet is animated too.
        mCollapsed = true;
        // Never reopen holding input focus from a previous session.
        releaseKeyboardFocus();
        removeCollapsedHandle();
        setSheetContentHeight(expandedHeight());
        setSheetHeight(peekHeight());
        // A previous collapsed session may have left the sheet non-touchable.
        setWindowTouchable(true);
        try {
            mOverlayDialog.show();
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show overlay terminal window", t);
            toast(R.string.overlay_window_failed);
            return;
        }
        // Dialog.show() attaches the window; force the peek size and re-apply the blur.
        mAppliedBlurRadius = -1;
        applyOverlayWindowAttributes();

        mVisible = true;
        sVisible = true;
        sExpanded = false;
        updateCursorBlinker(true);
        mPreferences.edit().putBoolean(KEY_ENABLED, true).apply();
        updateOverlayBackground();
        mService.updateNotificationFromOverlay();
        refreshTile();

        mOverlayView.post(() -> {
            if (!mVisible) return;
            // The emulator (and the colors.properties / theme palette) is only available once the
            // view has been laid out, so apply the panel palette again before animating open.
            applyOverlayPalette();
            expand();
        });
    }

    /** Rolls the sheet up to the top handle. The shell keeps running. */
    public void hide() {
        collapse();
    }

    /**
     * Runs when the collapse animation has finished. The sheet window stays exactly where it is
     * (peek height, transparent panel, grab handle visible, terminal view not drawn) and only
     * stops taking touches; a tiny transparent window on top limits the open gesture to the handle
     * area. Because nothing visible is added, removed or resized, there is no flicker.
     */
    private void finishCollapse() {
        if (mDestroyed || !mVisible) return;
        mCollapsed = true;
        sExpanded = false;

        // The sheet window stays on screen at peek height (transparent panel, grab handle
        // visible). Only its touchability changes, plus this invisible 56x16dp window on top
        // decides where the open gesture works. Nothing is swapped visually, so there is no
        // flicker and no resize of a live window.
        if (showCollapsedHandle()) setWindowTouchable(false);
        mService.updateNotificationFromOverlay();
        refreshTile();
    }

    private boolean showCollapsedHandle() {
        if (mDestroyed || mHandleView != null) return mHandleView != null;
        if (!hasOverlayPermission()) return false;

        View handle = LayoutInflater.from(mService).inflate(R.layout.overlay_handle, null);
        mHandleView = handle;

        final int width = dp(COLLAPSED_HANDLE_WIDTH_DP);
        mHandleParams = new WindowManager.LayoutParams(
            width, dp(COLLAPSED_HANDLE_HEIGHT_DP), overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT);
        mHandleParams.gravity = Gravity.TOP | Gravity.START;
        mHandleParams.x = Math.max(0, (screenWidth() - width) / 2);
        mHandleParams.y = 0;
        mHandleParams.setTitle("Nascence floating terminal handle");

        final int touchSlop = ViewConfiguration.get(mService).getScaledTouchSlop();
        handle.setOnTouchListener(new android.view.View.OnTouchListener() {
            private float downRawY;
            private boolean expanded;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawY = event.getRawY();
                        expanded = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        // A downward pull opens the sheet immediately; a tap opens it on release.
                        if (!expanded && event.getRawY() - downRawY > touchSlop) {
                            expanded = true;
                            // Defer so the window is not removed inside its own touch dispatch.
                            mHandler.post(TermuxOverlayManager.this::expand);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!expanded) {
                            expanded = true;
                            mHandler.post(TermuxOverlayManager.this::expand);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });

        try {
            mWindowManager.addView(handle, mHandleParams);
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to show the collapsed overlay handle", t);
            mHandleView = null;
            mHandleParams = null;
        }
        return mHandleView != null;
    }

    private void removeCollapsedHandle() {
        if (mHandleView == null) return;
        try {
            mWindowManager.removeViewImmediate(mHandleView);
        } catch (Throwable ignored) {
        }
        mHandleView = null;
        mHandleParams = null;
    }

    /** Removes the window but keeps the shell alive. Used when the session has ended. */
    private void dismissWindow() {
        cancelHeightAnimation();
        hideKeyboard();
        updateCursorBlinker(false);
        removeCollapsedHandle();
        if (mOverlayDialog != null) {
            try {
                if (mOverlayDialog.isShowing()) mOverlayDialog.dismiss();
            } catch (Throwable t) {
                Logger.logStackTraceWithMessage(LOG_TAG, "Failed to dismiss overlay terminal window", t);
            }
        }

        mVisible = false;
        mCollapsed = false;
        sVisible = false;
        sExpanded = false;
        resetModifiers();
        mService.updateNotificationFromOverlay();
        refreshTile();
    }

    private void resetModifiers() {
        mViewClient.resetModifiers();
    }

    /** Remove every window and finish the floating shell. Called from {@link TermuxService#onDestroy}. */
    public void destroy() {
        if (mDestroyed) return;
        mDestroyed = true;

        cancelHeightAnimation();
        hideKeyboard();
        updateCursorBlinker(false);
        removeCollapsedHandle();
        if (mOverlayDialog != null) {
            try {
                mOverlayDialog.dismiss();
            } catch (Throwable ignored) {
            }
            mOverlayDialog = null;
            mOverlayView = null;
            mTerminalView = null;
            mGripView = null;
            mWindowBackground = null;
        }

        mVisible = false;
        mCollapsed = false;
        sVisible = false;
        sExpanded = false;

        if (mSession != null) {
            mSession.finishIfRunning();
            mSession = null;
            mTermuxSession = null;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && mWindowManager != null && mBlurListener != null) {
            removeBlurListener();
        }
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void removeBlurListener() {
        try {
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
        } catch (Throwable ignored) {
        }
        mBlurListener = null;
    }

    /**
     * Gives the window input focus only when the user actually wants to type. An overlay that is
     * focusable all the time would keep the IME and key events even after another app is opened.
     */
    private void setWindowFocusable(boolean focusable) {
        if (mOverlayParams == null) return;
        boolean currentlyFocusable =
            (mOverlayParams.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
        if (currentlyFocusable == focusable) return;
        if (focusable) {
            mOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            mOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        applyOverlayWindowAttributes();
    }

    /**
     * Makes the sheet pass touches through when it is only the collapsed handle, so the invisible
     * strip around the grab handle does not swallow input meant for the app behind.
     */
    private void setWindowTouchable(boolean touchable) {
        if (mOverlayParams == null) return;
        boolean currentlyTouchable =
            (mOverlayParams.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        if (currentlyTouchable == touchable) return;
        if (touchable) {
            mOverlayParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            mOverlayParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        applyOverlayWindowAttributes();
    }

    /** Drops focus and the IME, handing input back to whatever is behind the sheet. */
    private void releaseKeyboardFocus() {
        hideKeyboard();
        if (mTerminalView != null) mTerminalView.clearFocus();
        mImeVisible = false;
        setWindowFocusable(false);
    }

    void showKeyboard() {
        if (mTerminalView == null || mCollapsed) return;
        setWindowFocusable(true);
        mImeVisible = false;
        mTerminalView.requestFocus();
        // The window only becomes focusable once the new layout params are applied, so ask for the
        // IME after the window manager has processed them.
        mTerminalView.postDelayed(() -> {
            if (mTerminalView == null || mDestroyed || mCollapsed) return;
            InputMethodManager imm = (InputMethodManager) mService.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(mTerminalView, InputMethodManager.SHOW_IMPLICIT);
        }, 60);
    }

    /**
     * Open an OSC 8 hyperlink or a guessed URL under the finger.
     *
     * @return {@code true} if the tap was consumed by a link and the keyboard should stay hidden.
     */
    boolean handleTap(@NonNull MotionEvent event) {
        if (mTerminalView == null || mTerminalView.mEmulator == null) return false;
        TerminalEmulator term = mTerminalView.mEmulator;
        int[] columnAndRow = mTerminalView.getColumnAndRow(event, true);

        String hyperlink = term.getHyperlinkUriAt(columnAndRow[1], columnAndRow[0]);
        if (hyperlink != null && !hyperlink.isEmpty()) {
            openOverlayUrl(hyperlink);
            return true;
        }

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        if (properties != null && properties.shouldOpenTerminalTranscriptURLOnClick()) {
            String wordAtTap = term.getScreen().getWordAtLocation(columnAndRow[0], columnAndRow[1]);
            LinkedHashSet<CharSequence> urlSet = TermuxUrlUtils.extractUrls(wordAtTap);
            if (!urlSet.isEmpty()) {
                openOverlayUrl((String) urlSet.iterator().next());
                return true;
            }
        }

        return false;
    }

    /** Open a link from a {@link TermuxService} context, which needs {@code NEW_TASK}. */
    private void openOverlayUrl(@NonNull String url) {
        try {
            mService.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open overlay link: " + url, t);
            toast(R.string.overlay_window_failed);
        }
    }

    void hideKeyboard() {
        if (mTerminalView == null) return;
        android.os.IBinder token = mTerminalView.getWindowToken();
        if (token == null) return;
        InputMethodManager imm = (InputMethodManager) mService.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(token, 0);
    }

    void toggleKeyboard() {
        if (mTerminalView == null) return;
        InputMethodManager imm = (InputMethodManager) mService.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        if (imm.isActive(mTerminalView)) {
            releaseKeyboardFocus();
        } else {
            showKeyboard();
        }
    }

    void changeFontSize(boolean increase) {
        // Use the same pixel-based font size and limits as the main app. The previous 8..32 clamp
        // did not match the app's unit (12dp * density), so the default size was already outside
        // the range and the first pinch jumped the font to 32.
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mService);
        if (preferences == null) return;
        preferences.setFontSize(preferences.getFontSize() + (increase ? 2 : -2));
        mFontSize = preferences.getFontSize();
        if (mTerminalView != null) {
            mTerminalView.setTextSize(mFontSize);
            mTerminalView.invalidate();
        }
    }

    boolean hasOverlayPermission() {
        return PermissionUtils.checkDisplayOverOtherAppsPermission(mService);
    }

    /**
     * Ask the system to re-read the Quick Settings tile state. The API 24 class must not even be
     * loaded on older releases, so the version check lives outside {@link TermuxOverlayTileService}.
     */
    private void refreshTile() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            TermuxOverlayTileService.requestRefresh(mService);
    }

    private void requestOverlayPermission() {
        try {
            PermissionUtils.requestDisplayOverOtherAppsPermission(mService);
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to request overlay permission", t);
        }
    }

    private void toast(int resId) {
        Toast.makeText(mService, resId, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------------------------------
    // Session
    // ---------------------------------------------------------------------------------------

    private boolean ensureSession() {
        if (mSession != null && mSession.isRunning()) return true;

        if (mHandedOverSession != null) {
            TerminalSession handedOver = mHandedOverSession;
            if (handedOver.isRunning()) {
                // Take the shell back from the activity: detach its view and restore our client.
                mHandedOverSession = null;
                mService.detachSessionFromActivity(handedOver);
                handedOver.updateTerminalSessionClient(mSessionClient);
                mSession = handedOver;
                return true;
            }
            mHandedOverSession = null;
            if (mTermuxSession != null && mTermuxSession.getTerminalSession() == handedOver) {
                mService.removeFinishedOverlaySession(mTermuxSession);
                mTermuxSession = null;
            }
        }

        ExecutionCommand command = new ExecutionCommand(TermuxShellManager.getNextShellId(),
            null, null, null, TermuxConstants.TERMUX_HOME_DIR_PATH,
            Runner.TERMINAL_SESSION.getName(), false);
        command.shellName = mService.getString(R.string.overlay_session_name);
        command.setShellCommandShellEnvironment = true;
        command.terminalTranscriptRows = 2000;

        TermuxSession termuxSession = TermuxSession.execute(mService, command, mSessionClient,
            mSessionClient, new TermuxShellEnvironment(), null, false);
        if (termuxSession == null || termuxSession.getTerminalSession() == null) {
            Logger.logError(LOG_TAG, "Failed to start the floating terminal session");
            return false;
        }

        mTermuxSession = termuxSession;
        mSession = termuxSession.getTerminalSession();
        // Make the floating shell visible in the app's session list, where tapping it hands the
        // session over to the activity.
        mService.registerOverlayTermuxSession(termuxSession);
        return true;
    }

    /** The terminal session owned by the floating terminal, or null if there is none. */
    @Nullable
    public TerminalSession getTerminalSession() {
        return mSession;
    }

    /**
     * Hands the floating terminal's session over to the activity. The overlay view is detached and
     * the sheet is rolled up to its handle; the overlay keeps the session so that summoning it
     * again restores the same shell instead of starting a new one.
     *
     * @return true if the given session was the floating terminal's.
     */
    public boolean releaseSession(@Nullable TerminalSession session) {
        if (session == null || session != mSession) return false;

        if (mTerminalView != null) mTerminalView.attachSession(null);
        mHandedOverSession = session;
        mSession = null;

        // Roll the sheet into its handle instead of tearing it down, so the same view can be
        // reattached when the floating terminal is summoned again.
        collapse();
        return true;
    }

    private void createOverlayView() {
        Context themed = new ContextThemeWrapper(mService, R.style.Theme_TermuxApp_DayNight_NoActionBar);
        View root = LayoutInflater.from(themed).inflate(R.layout.overlay_terminal, null);

        mOverlayView = root;
        mSheetContent = root.findViewById(R.id.overlay_sheet_content);
        mTerminalView = root.findViewById(R.id.overlay_terminal_view);
        mGripView = root.findViewById(R.id.overlay_grip);
        mChromeKey = -1;

        // Once the IME closes, stop owning input so the app behind gets focus back immediately.
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            if (imeVisible) {
                mImeVisible = true;
            } else if (mImeVisible) {
                mImeVisible = false;
                mHandler.post(this::releaseKeyboardFocus);
            }
            return insets;
        });

        AppTypeface.applyToViewTree(root);

        // The panel background lives on the dialog window so Android's background blur can follow
        // its rounded outline. The content view only keeps an invisible rounded outline so
        // clipToOutline still trims the terminal to the same shape.
        GradientDrawable clipOutline = new GradientDrawable();
        clipOutline.setCornerRadius(dp(18));
        clipOutline.setColor(Color.TRANSPARENT);
        root.setBackground(clipOutline);

        mTerminalView.setTerminalViewClient(mViewClient);
        mTerminalView.setTextSize(mFontSize);
        mTerminalView.setTypeface(AppTypeface.getRegular(themed), AppTypeface.getBold(themed));
        mTerminalView.setBackgroundColor(Color.TRANSPARENT);
        TermuxAppSharedPreferences screenPreferences = TermuxAppSharedPreferences.build(mService);
        mTerminalView.setKeepScreenOn(screenPreferences == null || screenPreferences.shouldKeepScreenOn());
        mTerminalView.attachSession(mSession);
        setSheetContentHeight(expandedHeight());
        mTerminalView.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) hide();
                return true;
            }
            return false;
        });

        setupDragHandle(root.findViewById(R.id.overlay_drag_handle));

        mOverlayParams = createOverlayParams();

        // A Dialog is used instead of WindowManager.addView because only a real Window supports
        // Window.setBackgroundBlurRadius(), which blurs just within the window bounds.
        // LayoutParams.setBlurBehindRadius() / FLAG_BLUR_BEHIND always blurs the whole screen
        // behind the window, which is what made the entire display blurry.
        mOverlayDialog = new Dialog(mService, R.style.Theme_Nascence_OverlayDialog);
        mOverlayDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        mOverlayDialog.setCanceledOnTouchOutside(false);
        mOverlayDialog.setCancelable(false);
        mOverlayDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.getAction() == KeyEvent.ACTION_UP) hide();
                return true;
            }
            return false;
        });
        mOverlayDialog.setContentView(root);
        prepareOverlayWindow();
    }

    private void prepareOverlayWindow() {
        if (mOverlayDialog == null || mOverlayParams == null) return;
        Window window = mOverlayDialog.getWindow();
        if (window == null) return;

        mWindowBackground = (GradientDrawable) mService
            .getDrawable(R.drawable.overlay_terminal_background).mutate();
        window.setBackgroundDrawable(mWindowBackground);
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setDimAmount(0f);
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        window.setGravity(Gravity.TOP | Gravity.START);
        window.setAttributes(mOverlayParams);
        // Floating dialog windows may add their own decor insets; the sheet should sit flush.
        window.getDecorView().setPadding(0, 0, 0, 0);
        applyWindowBackgroundBlur();
    }

    @TargetApi(Build.VERSION_CODES.S)
    private void applyWindowBackgroundBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || mOverlayDialog == null) return;
        Window window = mOverlayDialog.getWindow();
        if (window == null) return;
        // No blur while the sheet is just the grab handle: there is no panel for it to sit behind.
        int radius = mCollapsed ? 0 : dp(BLUR_RADIUS_DP);
        if (radius == mAppliedBlurRadius) return;
        try {
            window.setBackgroundBlurRadius(radius);
            mAppliedBlurRadius = radius;
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to set the background blur radius", t);
        }
    }

    private void handleSessionFinished() {
        if (mDestroyed || mSession == null) return;

        mSession = null;
        if (mTermuxSession != null) {
            // Completes the execution command state and triggers onTermuxSessionExited().
            mService.removeFinishedOverlaySession(mTermuxSession);
            mTermuxSession.finish();
            mTermuxSession = null;
        }

        dismissWindow();
        mService.updateNotificationFromOverlay();
        toast(R.string.overlay_session_exited);
    }

    // ---------------------------------------------------------------------------------------
    // Window geometry
    // ---------------------------------------------------------------------------------------

    private WindowManager.LayoutParams createOverlayParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            peekHeight(),
            overlayWindowType(),
            // Never take focus on its own: a focusable overlay above every app would keep
            // swallowing the IME and key events even after another app is opened. Focus is granted
            // only when the user taps the terminal to type.
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        // Dialog windows are laid out inside the content area already, so 0 is directly under
        // the status bar. Adding statusBarHeight() here used to push the sheet too far down.
        params.y = 0;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        params.setTitle("Nascence floating terminal");

        return params;
    }

    private int peekHeight() {
        return dp(PEEK_HEIGHT_DP);
    }

    private int expandedHeight() {
        return Math.round(usableHeight() * mHeightFraction);
    }

    /** Largest height the sheet can be dragged to, as a fraction of the usable screen. */
    private int maxSheetHeight() {
        return Math.round(usableHeight() * MAX_HEIGHT_FRACTION);
    }

    private void setSheetHeight(int height) {
        if (mOverlayParams == null) return;
        int clamped = Math.max(peekHeight(), Math.min(maxSheetHeight(), height));
        mOverlayParams.height = clamped;
        applyOverlayWindowAttributes();
        applyPanelFade(clamped);

        boolean collapsed = clamped <= peekHeight() + dp(2);
        if (collapsed != mCollapsed) {
            mCollapsed = collapsed;
            sExpanded = mVisible && !collapsed;
            if (collapsed) releaseKeyboardFocus();
            // The blur only makes sense behind a visible panel, not behind the bare grab handle.
            applyWindowBackgroundBlur();
            updateCursorBlinker(!collapsed);
            if (mTerminalView != null) {
                if (collapsed) {
                    mTerminalView.setKeepScreenOn(false);
                } else {
                    TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(mService);
                    mTerminalView.setKeepScreenOn(preferences == null || preferences.shouldKeepScreenOn());
                }
            }
            // Keep the notification action label and the Quick Settings tile in sync.
            if (mVisible) {
                mService.updateNotificationFromOverlay();
                refreshTile();
            }
        }
    }

    /**
     * Fades the border and the terminal out as the sheet rolls into its grab handle, so the
     * collapsed state is just the bare handle floating over whatever is behind it. At zero
     * opacity the content is made INVISIBLE: it keeps its layout size (and therefore the PTY
     * size) but stops being drawn at all. The handle is a separate view and is not affected.
     */
    private void applyPanelFade(int height) {
        float t = clamp((height - peekHeight()) / (float) dp(56), 0f, 1f);
        if (mWindowBackground != null) mWindowBackground.setAlpha(Math.round(255 * t));

        // Slide the grip towards the screen edge as the panel dissolves, so the collapsed handle
        // sits close to the top instead of floating in the middle of the 28dp strip.
        if (mGripView != null) mGripView.setTranslationY(-dp(8) * (1f - t));

        if (mSheetContent == null) return;
        mSheetContent.setAlpha(t);
        int wanted = t <= 0f ? View.INVISIBLE : View.VISIBLE;
        if (mSheetContent.getVisibility() != wanted) {
            mSheetContent.setVisibility(wanted);
            // Catch up on anything that arrived while the terminal was not being drawn.
            if (wanted == View.VISIBLE && mTerminalView != null) mTerminalView.onScreenUpdated();
        }
    }

    /**
     * Sizes the sheet content to its resting height (the sheet height minus the grab handle). It is
     * changed once per gesture/animation, not per frame, so the terminal is clipped (not reflowed,
     * which would spam the PTY with SIGWINCH) while the window height animates.
     */
    private void setSheetContentHeight(int sheetHeight) {
        if (mSheetContent == null) return;
        int height = Math.max(0, sheetHeight - peekHeight());
        android.view.ViewGroup.LayoutParams params = mSheetContent.getLayoutParams();
        if (params != null && params.height != height) {
            params.height = height;
            mSheetContent.setLayoutParams(params);
        }
    }

    private void cancelHeightAnimation() {
        if (mHeightAnimator != null) {
            mHeightAnimator.cancel();
            mHeightAnimator = null;
        }
    }

    /**
     * Animates the sheet height with a decelerating curve. A non-zero fling velocity shortens or
     * lengthens the animation so the sheet keeps the momentum of the user's drag.
     */
    private void animateHeightTo(int target, float velocityY, @Nullable Runnable onEnd) {
        if (mOverlayParams == null) return;
        cancelHeightAnimation();

        int start = mOverlayParams.height;
        if (start == target) {
            setSheetHeight(target);
            if (target > peekHeight()) setSheetContentHeight(target);
            if (onEnd != null) mHandler.post(onEnd);
            return;
        }

        // Collapsing keeps the terminal layout untouched; expanding reflows it once, up front.
        if (target > peekHeight()) setSheetContentHeight(target);

        int distance = Math.abs(target - start);
        int duration;
        if (Math.abs(velocityY) > 0) {
            // Time = distance / speed, clamped to a pleasant range, so a fast fling snaps quickly.
            duration = (int) (distance * 1000f / Math.abs(velocityY));
            duration = Math.max(120, Math.min(360, duration));
        } else {
            duration = Math.max(180, Math.min(320, distance));
        }

        ValueAnimator animator = ValueAnimator.ofInt(start, target);
        animator.setDuration(duration);
        animator.setInterpolator(new DecelerateInterpolator(1.8f));
        animator.addUpdateListener(a -> setSheetHeight((int) a.getAnimatedValue()));
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                if (mHeightAnimator == animation) mHeightAnimator = null;
                setSheetHeight(target);
                if (onEnd != null) onEnd.run();
            }
        });
        mHeightAnimator = animator;
        animator.start();
    }

    /** Pushes the current size/position to the dialog window (resize and rotation). */
    private void applyOverlayWindowAttributes() {
        if (mOverlayDialog == null || mOverlayParams == null) return;
        Window window = mOverlayDialog.getWindow();
        if (window == null) return;
        try {
            window.setAttributes(mOverlayParams);
            // setAttributes() replaces the window's layout params, which can drop the background
            // blur radius, so re-apply it after every layout change.
            applyWindowBackgroundBlur();
        } catch (Throwable ignored) {
        }
    }

    private void setupDragHandle(View handle) {
        if (handle == null) return;
        final int touchSlop = ViewConfiguration.get(mService).getScaledTouchSlop();
        // A slightly higher bar than the platform minimum so a small jitter does not fling.
        final int minFling = Math.max(ViewConfiguration.get(mService).getScaledMinimumFlingVelocity(),
            dp(120));

        handle.setOnTouchListener(new View.OnTouchListener() {
            private float downRawY;
            private int startHeight;
            private boolean dragging;
            private VelocityTracker tracker;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mOverlayParams == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        cancelHeightAnimation();
                        downRawY = event.getRawY();
                        startHeight = mOverlayParams.height;
                        dragging = false;
                        tracker = VelocityTracker.obtain();
                        tracker.addMovement(event);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        if (tracker != null) tracker.addMovement(event);
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.abs(dy) > touchSlop) dragging = true;
                        if (!dragging) return true;
                        // Dragging down grows the sheet, dragging up rolls it towards the handle.
                        setSheetHeight(startHeight + Math.round(dy));
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float velocityY = 0f;
                        if (tracker != null) {
                            tracker.addMovement(event);
                            tracker.computeCurrentVelocity(1000);
                            velocityY = tracker.getYVelocity();
                            tracker.recycle();
                            tracker = null;
                        }

                        if (!dragging) {
                            // A tap on the handle toggles the sheet.
                            if (mCollapsed) animateHeightTo(expandedHeight(), 0f, null);
                            else animateHeightTo(peekHeight(), 0f,
                                TermuxOverlayManager.this::finishCollapse);
                            return true;
                        }

                        int peek = peekHeight();
                        int max = maxSheetHeight();
                        // Collapse only when the sheet was dragged all the way up to the handle,
                        // not anywhere in the lower half of the screen.
                        int collapseThreshold = peek + dp(64);
                        int current = mOverlayParams.height;

                        int target;
                        if (velocityY < -minFling) {
                            // Fling up: roll the sheet all the way into the top handle.
                            target = peek;
                        } else if (velocityY > minFling) {
                            // Fling down: inertia carries it to the largest allowed size.
                            target = max;
                        } else if (current <= collapseThreshold) {
                            target = peek;
                        } else {
                            // Settle where the finger left it and remember that size.
                            target = current;
                        }

                        if (target > peek) {
                            mHeightFraction = clamp(target / (float) usableHeight(),
                                MIN_HEIGHT_FRACTION, MAX_HEIGHT_FRACTION);
                            mPreferences.edit().putFloat(KEY_HEIGHT_FRACTION, mHeightFraction).apply();
                        }
                        dragging = false;
                        animateHeightTo(target, velocityY,
                            target == peek ? TermuxOverlayManager.this::finishCollapse : null);
                        return true;
                    }
                    default:
                        return false;
                }
            }
        });
    }

    private int usableHeight() {
        int height = screenHeight();
        return Math.max(dp(160), height - statusBarHeight() - navigationBarHeight());
    }

    private int screenHeight() {
        return realDisplaySize().y;
    }

    private int screenWidth() {
        return realDisplaySize().x;
    }

    /**
     * A {@link android.app.Service} context is not a visual context, so
     * {@code WindowManager.getCurrentWindowMetrics()} is not available here. Reading the default
     * display directly works on every supported release.
     */
    @SuppressWarnings("deprecation")
    private Point realDisplaySize() {
        Point size = new Point();
        android.hardware.display.DisplayManager displayManager =
            (android.hardware.display.DisplayManager) mService.getSystemService(Context.DISPLAY_SERVICE);
        android.view.Display display = displayManager == null
            ? null : displayManager.getDisplay(android.view.Display.DEFAULT_DISPLAY);
        if (display != null) {
            display.getRealSize(size);
        } else {
            size.x = mService.getResources().getDisplayMetrics().widthPixels;
            size.y = mService.getResources().getDisplayMetrics().heightPixels;
        }
        return size;
    }

    private int navigationBarHeight() {
        int id = mService.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? mService.getResources().getDimensionPixelSize(id) : 0;
    }

    private int statusBarHeight() {
        int id = mService.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? mService.getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int dp(int value) {
        return Math.round(value * mService.getResources().getDisplayMetrics().density);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int overlayWindowType() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            : WindowManager.LayoutParams.TYPE_PHONE;
    }

    private void updateOverlayBackground() {
        if (mWindowBackground == null) return;
        // No solid fill while the system blurs what is behind the window: the panel is just the
        // blur plus the 1dp border. Keep a dim fallback only when cross-window blur is unavailable,
        // otherwise the text would float straight over the app behind.
        mWindowBackground.setColor(mBlurEnabled ? Color.TRANSPARENT : 0xB0000000);
    }

    /**
     * Tints the grab handle so the sheet stays legible on both dark and light color themes.
     */
    private void applyOverlayChrome(int backgroundColor) {
        if (mOverlayView == null) return;
        mPanelRgb = backgroundColor & 0xFFFFFF;
        boolean light = TerminalColors.getPerceivedBrightnessOfColor(0xFF000000 | mPanelRgb) > 150;
        int chromeKey = mPanelRgb | (light ? 0x1000000 : 0);
        if (chromeKey == mChromeKey) {
            updateOverlayBackground();
            return;
        }
        mChromeKey = chromeKey;

        View grip = mOverlayView.findViewById(R.id.overlay_grip);
        if (grip != null) {
            Drawable gripBackground = grip.getBackground();
            if (gripBackground instanceof GradientDrawable) {
                ((GradientDrawable) gripBackground.mutate())
                    .setColor(light ? 0x66000000 : 0x66FFFFFF);
            }
        }

        if (mWindowBackground != null) {
            mWindowBackground.setStroke(dp(1), light ? 0x33000000 : 0x33FFFFFF);
        }

        updateOverlayBackground();
    }

    // ---------------------------------------------------------------------------------------
    // Session client
    // ---------------------------------------------------------------------------------------

    private final class OverlaySessionClient extends TermuxTerminalSessionClientBase
        implements TermuxSession.TermuxSessionClient {

        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {
            // Nothing is drawn while the sheet is rolled up; the view catches up when it reopens.
            if (mSheetContent != null && mSheetContent.getVisibility() != View.VISIBLE) return;
            if (mTerminalView != null && changedSession == mSession)
                mTerminalView.onScreenUpdated();
        }

        @Override
        public void onTitleChanged(@NonNull TerminalSession updatedSession) {
            // The sheet has no title bar anymore; the shell title is still used by the
            // notification, which reads it from the session directly.
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            mHandler.post(() -> {
                if (finishedSession == mSession) handleSessionFinished();
            });
        }

        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
            ShareUtils.copyTextToClipboard(mService, text);
        }

        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
            String text = ShareUtils.getTextStringFromClipboardIfSet(mService, true);
            if (text != null && mSession != null && mSession.getEmulator() != null)
                mSession.getEmulator().paste(text);
        }

        @Override
        public void onColorsChanged(@NonNull TerminalSession changedSession) {
            mHandler.post(() -> {
                if (changedSession != mSession) return;
                keepOverlayBackgroundTransparent();
            });
        }

        @Override
        public void onTerminalCursorStateChange(boolean state) {
            if (mTerminalView == null) return;
            mTerminalView.setTerminalCursorBlinkerState(state && mVisible, false);
        }

        @Override
        public void onTermuxSessionExited(TermuxSession termuxSession) {
            mHandler.post(() -> {
                if (termuxSession != mTermuxSession) return;
                if (mSession == null) {
                    // Handed over to the activity; only clean up the list entry.
                    mService.removeFinishedOverlaySession(termuxSession);
                    mTermuxSession = null;
                    if (mHandedOverSession != null && !mHandedOverSession.isRunning())
                        mHandedOverSession = null;
                    mService.updateNotificationFromOverlay();
                    return;
                }
                handleSessionFinished();
            });
        }
    }

    /**
     * Load {@code ~/.termux/colors.properties}, reset the palette to it and overlay the selected
     * built-in theme. Used when the floating window is opened, so it follows the main app's look
     * even when the overlay session was started from the Quick Settings tile.
     */
    void applyOverlayPalette() {
        if (mTerminalView == null || mTerminalView.mEmulator == null) return;

        try {
            java.util.Properties props = new java.util.Properties();
            java.io.File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            if (colorsFile.isFile()) {
                try (java.io.InputStream in = new java.io.FileInputStream(colorsFile)) {
                    props.load(in);
                }
            }
            TerminalColors.COLOR_SCHEME.updateWith(props);
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to load colors.properties for the overlay", t);
        }

        mTerminalView.mEmulator.mColors.reset();
        TerminalThemeLibrary.applySelectedTheme(mService, mTerminalView.mEmulator.mColors);
        applyOverlayChrome(mTerminalView.mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        keepOverlayBackgroundTransparent();
    }

    void applyOverlayTheme() {
        if (mTerminalView == null || mTerminalView.mEmulator == null) return;
        // The emulator background is forced to transparent for the panel, so it reads as 0 after
        // the first application. Reset to the scheme (colors.properties + selected theme) before
        // reading it again, otherwise every size change would repaint the panel black.
        mTerminalView.mEmulator.mColors.reset();
        TerminalThemeLibrary.applySelectedTheme(mService, mTerminalView.mEmulator.mColors);
        applyOverlayChrome(mTerminalView.mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND]);
        keepOverlayBackgroundTransparent();
    }

    private void keepOverlayBackgroundTransparent() {
        if (mTerminalView == null || mTerminalView.mEmulator == null) return;
        // The floating window provides its own translucent background, so the default cell
        // background must stay transparent for the blur (or the dim) to show through.
        mTerminalView.mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_BACKGROUND] = 0;
        mTerminalView.invalidate();
    }

    @SuppressLint("ClickableViewAccessibility")
    void onEmulatorSet() {
        applyOverlayTheme();
        updateCursorBlinker(true);
    }

    private void updateCursorBlinker(boolean start) {
        if (mTerminalView == null) return;
        if (start) {
            TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
            int rate = properties == null ? 0 : properties.getTerminalCursorBlinkRate();
            if (mTerminalView.setTerminalCursorBlinkerRate(rate))
                mTerminalView.setTerminalCursorBlinkerState(true, true);
        } else {
            mTerminalView.setTerminalCursorBlinkerState(false, true);
        }
    }
}

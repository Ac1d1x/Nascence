package com.termux.app.overlay;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.android.PermissionUtils;

/**
 * Invisible trampoline used by the persistent notification.
 *
 * <p>Notification actions cannot launch a service that then starts an activity (trampoline
 * restrictions since Android 12), so this activity requests the overlay permission itself when
 * needed and otherwise forwards the toggle action to {@link TermuxService}.
 */
public class TermuxOverlayLauncherActivity extends Activity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
            PermissionUtils.requestDisplayOverOtherAppsPermission(this);
            finish();
            return;
        }

        try {
            ContextCompat.startForegroundService(this, new Intent(this, TermuxService.class)
                .setAction(TermuxOverlayManager.ACTION_TOGGLE_OVERLAY));
        } catch (Throwable t) {
            Toast.makeText(this, R.string.overlay_window_failed, Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}

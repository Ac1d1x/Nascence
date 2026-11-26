package com.termux.app.overlay;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.widget.Toast;

import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.logger.Logger;

/** Quick Settings tile that opens or hides the floating terminal. */
@RequiresApi(api = Build.VERSION_CODES.N)
public class TermuxOverlayTileService extends TileService {

    private static final String LOG_TAG = "TermuxOverlayTileService";

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();

        if (!PermissionUtils.checkDisplayOverOtherAppsPermission(this)) {
            requestOverlayPermission();
            return;
        }

        try {
            ContextCompat.startForegroundService(this, new Intent(this, TermuxService.class)
                .setAction(TermuxOverlayManager.ACTION_TOGGLE_OVERLAY));
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to start TermuxService from the tile", t);
            Toast.makeText(this, R.string.overlay_window_failed, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) return;

        tile.setLabel(getString(R.string.overlay_terminal_title));
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_qs_overlay));
        tile.setState(TermuxOverlayManager.isOverlayExpanded()
            ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    @SuppressWarnings("deprecation")
    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getPackageName()));
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                startActivityAndCollapse(pendingIntent);
            } else {
                startActivityAndCollapse(intent);
            }
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to open the overlay permission settings", t);
        }
    }

    /** Ask the system to call {@link #onStartListening()} again so the tile state stays in sync. */
    static void requestRefresh(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return;
        try {
            TileService.requestListeningState(context,
                new ComponentName(context, TermuxOverlayTileService.class));
        } catch (Throwable t) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Failed to refresh the tile state", t);
        }
    }
}

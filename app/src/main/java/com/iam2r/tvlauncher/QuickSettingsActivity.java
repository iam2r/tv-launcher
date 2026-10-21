package com.iam2r.tvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * The panel the remote's Settings button opens when {@code settings_key_panel} is on (HomeKeyService):
 * a few glass rows under the Settings pill, over Home or whatever app is playing. Translucent and in a
 * task of its own, so the app behind keeps running and Back returns to it. Pressing Settings again
 * closes the panel.
 */
public final class QuickSettingsActivity extends Activity {

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_quick_settings);
        findViewById(R.id.scrim).setOnClickListener(v -> finish());
        LinearLayout rows = findViewById(R.id.rows);
        // The launcher's own choices first: they are what the Settings pill is pressed for most often.
        addToggleRow(rows, R.drawable.ic_swap_vert, R.string.quick_time_format, timeFormatLabel(),
                () -> setClockHour24(!isClockHour24()));
        addToggleRow(rows, R.drawable.ic_apps, R.string.quick_language, languageLabel(),
                () -> {
                    Lang.next(this);
                    // Rebuild, so every row (and the panel itself) reads in the new language.
                    recreate();
                });
        // Then the two settings screens, named for what they open.
        addRow(rows, R.drawable.ic_home_settings, R.string.launcher_settings, 0,
                new Intent(this, MainActivity.class).setAction(MainActivity.ACTION_LAUNCHER_SETTINGS));
        addRow(rows, R.drawable.ic_settings, R.string.quick_system_settings, 0,
                new Intent(Settings.ACTION_SETTINGS));
        addRow(rows, R.drawable.ic_picture, R.string.quick_picture, 0, new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        addRow(rows, R.drawable.ic_wifi, R.string.quick_network, 0, new Intent(Settings.ACTION_WIFI_SETTINGS));
        addRow(rows, R.drawable.ic_apps, R.string.quick_apps, R.string.quick_apps_hint,
                new Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS));
        rows.getChildAt(0).requestFocus();
    }

    /** Settings pressed again while the panel is up. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        finish();
    }

    /** Anything else taking the screen (Home, an app, a screensaver) closes the panel. */
    @Override
    protected void onPause() {
        super.onPause();
        finish();
        overridePendingTransition(0, 0);
    }

    private boolean isClockHour24() {
        return getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .getBoolean(MainActivity.CLOCK_24H, true);
    }

    private void setClockHour24(boolean hour24) {
        getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE)
                .edit().putBoolean(MainActivity.CLOCK_24H, hour24).apply();
    }

    /** The choice plus the time it gives right now, so the row shows its own effect before the panel closes. */
    private String timeFormatLabel() {
        boolean hour24 = isClockHour24();
        String pattern = hour24 ? "HH:mm" : "h:mm a";
        String now = new SimpleDateFormat(pattern, Lang.localeOf(this)).format(new Date());
        return getString(hour24 ? R.string.quick_time_24 : R.string.quick_time_12) + " \u00b7 " + now;
    }

    private String languageLabel() {
        String chosen = Lang.chosen(this);
        if (Lang.ZH.equals(chosen)) return getString(R.string.quick_lang_zh);
        if (Lang.EN.equals(chosen)) return getString(R.string.quick_lang_en);
        return getString(R.string.quick_lang_system);
    }

    /** A row that changes a choice in place: pressing it runs the action, then the status text is redrawn. */
    private void addToggleRow(LinearLayout rows, int icon, int title, String status, Runnable action) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        hint.setText(status);
        row.setOnClickListener(v -> {
            action.run();
            // The row's own text is refreshed by whoever ran the action (a recreate for the language).
            hint.setText(title == R.string.quick_time_format ? timeFormatLabel() : languageLabel());
        });
        rows.addView(row);
    }

    private void addRow(LinearLayout rows, int icon, int title, int status, Intent target) {
        View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.findViewById(R.id.dot).setVisibility(View.GONE);
        TextView hint = row.findViewById(R.id.status);
        if (status != 0) hint.setText(status);
        else hint.setVisibility(View.GONE);
        row.setOnClickListener(v -> {
            try {
                startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
            finish();
        });
        rows.addView(row);
    }
}

package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(Lang.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.reportChoice).setOnClickListener(v -> showReport());
        findViewById(R.id.defaultHomeChoice).requestFocus();
    }

    /**
     * What this launcher could work out about the TV it is running on: the parts that differ between brands
     * and that only config.xml can fix. Read-only, and it never changes anything - it names the config key
     * behind each line that came back empty, so a TV this was not tuned for can be adapted without a build.
     */
    private void showReport() {
        Config config = Config.load(this);
        StringBuilder text = new StringBuilder();
        text.append(getString(R.string.report_device, Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE))
                .append('\n').append(getString(R.string.report_config,
                        getString(config.overlayApplied ? R.string.report_config_overlay : R.string.report_config_defaults)));

        List<String> slots = Slots.load(this, config.slotCount, config.dockApps);
        int filled = 0;
        for (String pkg : slots) if (!pkg.isEmpty()) filled++;
        text.append('\n').append(getString(R.string.report_home,
                getString(config.shelfMode ? R.string.report_yes : R.string.report_no), filled, config.slotCount));

        text.append('\n').append(getString(R.string.report_live,
                config.liveTvPackage.isEmpty() ? getString(R.string.report_not_set) : liveTvState(config)));

        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        int inputs = 0;
        StringBuilder names = new StringBuilder();
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (!info.isPassthroughInput() || info.isHidden(this)) continue;
                    if (inputs++ < 3) names.append(inputs == 1 ? ": " : ", ").append(info.loadLabel(this));
                }
            } catch (RuntimeException e) {
                names.append(": ").append(e.getClass().getSimpleName());
            }
        }
        text.append('\n').append(getString(R.string.report_inputs, inputs, names));

        boolean settings = getPackageManager().resolveActivity(new Intent(Settings.ACTION_SETTINGS), 0) != null;
        text.append('\n').append(getString(R.string.report_settings,
                getString(settings ? R.string.report_yes : R.string.report_no)));

        text.append('\n').append(getString(R.string.report_keys, config.settingsKeycodes.length == 0
                ? getString(R.string.report_none) : Arrays.toString(config.settingsKeycodes)));
        text.append('\n').append(getString(R.string.report_hint));

        TextView body = new TextView(this);
        body.setText(text);
        body.setTextSize(16);
        body.setPadding(48, 24, 48, 8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle(R.string.report_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Whether the configured Live TV package actually answers: its leanback entry, or the configured action. */
    private String liveTvState(Config config) {
        if (getPackageManager().getLeanbackLaunchIntentForPackage(config.liveTvPackage) != null) {
            return config.liveTvPackage + " \u00b7 " + getString(R.string.report_yes);
        }
        if (!config.liveTvAction.isEmpty()) {
            Intent byAction = new Intent(config.liveTvAction).setPackage(config.liveTvPackage);
            boolean ok = getPackageManager().resolveActivity(byAction, 0) != null;
            return config.liveTvPackage + " \u00b7 " + getString(ok ? R.string.report_yes : R.string.report_no);
        }
        return config.liveTvPackage + " \u00b7 " + getString(R.string.report_no);
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

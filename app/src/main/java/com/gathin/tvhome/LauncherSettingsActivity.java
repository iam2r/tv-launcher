package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

/** Remote-friendly settings owned by the launcher; system settings stay one row away. */
public final class LauncherSettingsActivity extends Activity {
    private SharedPreferences choices;
    private final TextView[] columnChoices = new TextView[4];
    private TextView animationsChoice;
    private TextView advancedChoice;
    private TextView followThemeChoice;
    private int columns;
    private boolean animations;
    private boolean advancedEffects;

    @Override

    protected void attachBaseContext(android.content.Context base) {

        super.attachBaseContext(Lang.wrap(base));

    }


    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_launcher_settings);
        choices = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        Config config = Config.load(this);
        columns = config.columns;
        animations = config.animations;
        advancedEffects = config.animations && config.advancedEffects;

        int[] ids = {R.id.columns4, R.id.columns5, R.id.columns6, R.id.columns7};
        for (int i = 0; i < ids.length; i++) {
            final int value = i + 4;
            columnChoices[i] = findViewById(ids[i]);
            columnChoices[i].setOnClickListener(v -> {
                if (columns == value) return;
                columns = value;
                choices.edit().putInt("columns", value).apply();
                setResult(RESULT_OK);
                refresh();
            });
        }
        // Keep the numeric choices increasing in the Hebrew reading direction. Reorder the
        // actual views so D-pad focus follows their positions without changing the saved values.
        if (getResources().getBoolean(R.bool.rtl_app_grid)) {
            LinearLayout row = (LinearLayout) columnChoices[0].getParent();
            row.removeAllViews();
            for (int i = columnChoices.length - 1; i >= 0; i--) row.addView(columnChoices[i]);
        }
        animationsChoice = findViewById(R.id.animationsChoice);
        animationsChoice.setOnClickListener(v -> {
            animations = !animations;
            if (!animations) advancedEffects = false;
            choices.edit().putBoolean("animations", animations)
                    .putBoolean("advanced_effects", advancedEffects).apply();
            setResult(RESULT_OK);
            refresh();
        });
        advancedChoice = findViewById(R.id.advancedChoice);
        advancedChoice.setOnClickListener(v -> {
            if (advancedEffects) {
                setAdvanced(false);
            } else {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.advanced_effects)
                        .setMessage(R.string.advanced_effects_warning)
                        .setPositiveButton(R.string.enable, (dialog, which) -> setAdvanced(true))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        followThemeChoice = findViewById(R.id.followThemeChoice);
        followThemeChoice.setOnClickListener(v -> {
            if (!hasOnTvChoices()) return;
            choices.edit().remove("columns").remove("animations").remove("advanced_effects").commit();
            Config current = Config.load(this);
            columns = current.columns;
            animations = current.animations;
            advancedEffects = current.animations && current.advancedEffects;
            setResult(RESULT_OK);
            refresh();
        });
        findViewById(R.id.defaultHomeChoice).setOnClickListener(v -> {
            Toast.makeText(this, R.string.home_settings_help, Toast.LENGTH_LONG).show();
            open(new Intent(Settings.ACTION_HOME_SETTINGS));
        });
        findViewById(R.id.tvSettingsChoice).setOnClickListener(v ->
                open(new Intent(Settings.ACTION_SETTINGS)));
        findViewById(R.id.reportChoice).setOnClickListener(v -> showReport());
        refresh();
        columnChoices[Math.max(0, Math.min(columns - 4, 3))].requestFocus();
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

    private void setAdvanced(boolean enabled) {
        advancedEffects = enabled;
        choices.edit().putBoolean("advanced_effects", enabled).apply();
        setResult(RESULT_OK);
        refresh();
    }

    private void refresh() {
        for (int i = 0; i < columnChoices.length; i++) {
            columnChoices[i].setSelected(columns == i + 4);
        }
        animationsChoice.setText(getString(R.string.animations_state,
                getString(animations ? R.string.on : R.string.off)));
        advancedChoice.setText(getString(R.string.advanced_effects_state,
                getString(advancedEffects ? R.string.on : R.string.off)));
        advancedChoice.setEnabled(animations);
        advancedChoice.setAlpha(animations ? 1f : 0.5f);
        followThemeChoice.setSelected(!hasOnTvChoices());
    }

    private boolean hasOnTvChoices() {
        return choices.contains("columns") || choices.contains("animations")
                || choices.contains("advanced_effects");
    }

    private void open(Intent intent) {
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_LONG).show();
        }
    }
}

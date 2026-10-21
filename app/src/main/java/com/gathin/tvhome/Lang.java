package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.Locale;

/**
 * The launcher's own language choice, independent of the TV's system language: "follow the TV", English, or
 * Simplified Chinese. Everything the launcher shows is translated in these two, so a TV set to a language
 * neither covers (or one whose translation reads badly) can still get a screen its owner understands.
 *
 * <p>Applied by wrapping each activity's base context, which is why every activity overrides
 * {@code attachBaseContext}: without AppCompat there is no per-app locale API below Android 13.
 */
final class Lang {
    static final String PREF = "lang";
    static final String SYSTEM = "";
    static final String ZH = "zh";
    static final String EN = "en";

    private Lang() { }

    static String chosen(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF, SYSTEM);
    }

    static void set(Context context, String value) {
        context.getSharedPreferences(Config.SETTINGS_NAME, Context.MODE_PRIVATE)
                .edit().putString(PREF, value).apply();
    }

    /** Cycles follow-the-TV -> 中文 -> English -> follow-the-TV; returns the new choice. */
    static String next(Context context) {
        String current = chosen(context);
        String next = SYSTEM.equals(current) ? ZH : ZH.equals(current) ? EN : SYSTEM;
        set(context, next);
        return next;
    }

    /** A base context that reads in the chosen language, or the original one when following the TV. */
    static Context wrap(Context base) {
        String tag = chosen(base);
        if (SYSTEM.equals(tag)) return base;
        Locale locale = new Locale(tag);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /** The language an activity was built in, so it can rebuild itself when the choice changes. */
    static Locale localeOf(Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }
}

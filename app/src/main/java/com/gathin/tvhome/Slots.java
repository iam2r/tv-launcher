package com.gathin.tvhome;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What sits in each Home slot when the launcher runs in shelf mode.
 *
 * <p>A shelf Home shows a fixed number of slots instead of every app; this file is the owner's choice of what
 * each one holds. One package per line, in slot order, an empty line for an empty slot, so the file stays
 * readable and a slot can be cleared without shifting the others. Written atomically; an unreadable file is
 * never replaced by an empty one, the configured defaults are used for that run instead.
 */
final class Slots {
    private static final String TAG = "TvHome.Slots";
    static final String FILE_NAME = "home_slots.txt";
    /** Upper bound on shelved slots; a wider Home would not fit a 1080p screen in one row anyway. */
    static final int MAX_SLOTS = 8;
    private static final int MAX_BYTES = MAX_SLOTS * 256;

    private Slots() { }

    private static AtomicFile store(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE_NAME));
    }

    /**
     * The slots to show: the owner's saved choices, padded or trimmed to {@code count}, with
     * {@code defaults} (config dock_apps) standing in where nothing was ever saved.
     */
    static List<String> load(Context context, int count, List<String> defaults) {
        count = Math.max(1, Math.min(MAX_SLOTS, count));
        List<String> saved = null;
        try {
            saved = readStrict(store(context));
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the Home slots, using the configured ones", e);
        }
        List<String> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String pkg = saved != null && i < saved.size() ? saved.get(i)
                    : defaults != null && i < defaults.size() ? defaults.get(i) : "";
            result.add(validOrEmpty(pkg));
        }
        return result;
    }

    private static String validOrEmpty(String pkg) {
        return LibraryApps.validPackage(pkg) ? pkg : "";
    }

    private static List<String> readStrict(AtomicFile file) throws IOException {
        if (!file.getBaseFile().exists() && !new File(file.getBaseFile() + ".bak").exists())
            return null;
        try (FileInputStream input = file.openRead()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[512];
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (bytes.size() + read > MAX_BYTES) throw new IOException("Slot file too large");
                bytes.write(buffer, 0, read);
            }
            return decode(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
    }

    /** Stores these slots; {@code null} or unknown packages become empty slots. */
    static boolean save(Context context, List<String> slots) {
        AtomicFile file = store(context);
        FileOutputStream output = null;
        try {
            byte[] bytes = encode(slots).getBytes(StandardCharsets.UTF_8);
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
            return true;
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            Log.w(TAG, "Could not save the Home slots", e);
            return false;
        }
    }

    static String encode(List<String> slots) {
        if (slots.size() > MAX_SLOTS) throw new IllegalArgumentException("Too many slots");
        StringBuilder text = new StringBuilder();
        for (String pkg : slots) text.append(validOrEmpty(pkg)).append('\n');
        return text.toString();
    }

    static List<String> decode(String text) {
        if (text.length() > MAX_BYTES) throw new IllegalArgumentException("Slot file too large");
        List<String> slots = new ArrayList<>();
        int start = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != '\n') continue;
            if (i == start && i == text.length()) break;
            String pkg = text.substring(start, i);
            if (!pkg.isEmpty() && !LibraryApps.validPackage(pkg))
                throw new IllegalArgumentException("Invalid package in the slot file");
            slots.add(pkg);
            if (slots.size() > MAX_SLOTS) throw new IllegalArgumentException("Too many slots");
            start = i + 1;
        }
        return slots;
    }

    /**
     * The same slots with {@code pkg} in {@code index}. {@code pkg} already in another slot moves here
     * rather than showing twice; an empty or unknown package clears the slot.
     */
    static List<String> set(List<String> slots, int index, String pkg) {
        if (index < 0 || index >= slots.size()) return slots;
        String value = validOrEmpty(pkg);
        List<String> next = new ArrayList<>(slots);
        if (!value.isEmpty()) {
            for (int i = 0; i < next.size(); i++) {
                if (i != index && value.equals(next.get(i))) next.set(i, "");
            }
        }
        next.set(index, value);
        return next;
    }

    static List<String> empty(int count) {
        return new ArrayList<>(Collections.nCopies(Math.max(1, Math.min(MAX_SLOTS, count)), ""));
    }
}

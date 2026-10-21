package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.File;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 快传: shows a QR code, serves a small upload page on the LAN, and installs whatever a phone sends.
 *
 * <p>The server lives only while this screen does. Nothing is left running in the background, so the TV does
 * not hold a socket (or a notification) after the user walks away.
 */
public class FastShareActivity extends Activity implements FastShare.Listener {
    private static final String AUTHORITY = "com.gathin.tvhome.files";

    /** Older uploads are dropped once this many are on the TV; the newest are kept. */
    private static final int KEEP_FILES = 20;

    private FastShare share;
    private ImageView qr;
    private TextView urlView;
    private TextView status;
    private TextView filesTitle;
    private View filesScroll;
    private LinearLayout files;

    @Override

    protected void attachBaseContext(android.content.Context base) {

        super.attachBaseContext(Lang.wrap(base));

    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fast_share);
        qr = findViewById(R.id.shareQr);
        urlView = findViewById(R.id.shareUrl);
        status = findViewById(R.id.shareStatus);
        filesTitle = findViewById(R.id.shareFilesTitle);
        filesScroll = findViewById(R.id.shareFilesScroll);
        files = findViewById(R.id.shareFiles);
        setTitle(R.string.fast_share);
        showFiles();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (share == null) {
            File inbox = new File(getFilesDir(), "inbox");
            prune(inbox);
            showFiles();
            share = new FastShare(inbox, this);
            if (share.start()) {
                String url = share.url();
                urlView.setText(url);
                qr.setImageBitmap(qrBitmap(url));
            }
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (share != null) {
            share.stop();
            share = null;
        }
    }

    /** The link as a QR code, black on white, no quiet-zone tricks: the frame in the layout is the border. */
    private Bitmap qrBitmap(String text) {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
        hints.put(EncodeHintType.MARGIN, 0);
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 600, 600, hints);
            int size = matrix.getWidth();
            int[] pixels = new int[size * size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    pixels[y * size + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** Keeps the inbox bounded: the newest {@link #KEEP_FILES} stay, older ones are dropped. */
    private static void prune(File inbox) {
        File[] all = inbox.listFiles((dir, name) -> name.toLowerCase().endsWith(".apk"));
        if (all == null || all.length <= KEEP_FILES) return;
        Arrays.sort(all, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < all.length - KEEP_FILES; i++) {
            //noinspection ResultOfMethodCallIgnored
            all[i].delete();
        }
    }

    /** The files already on the TV, newest first: OK installs the focused one, MENU deletes it. */
    private void showFiles() {
        if (files == null) return;
        files.removeAllViews();
        List<File> received = received();
        boolean empty = received.isEmpty();
        filesTitle.setVisibility(empty ? View.GONE : View.VISIBLE);
        filesScroll.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) return;
        DateFormat when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        for (File file : received) {
            TextView row = new TextView(this);
            String size = String.format(java.util.Locale.US, "%.1f MB", file.length() / 1048576f);
            row.setText(getString(R.string.fast_share_file_row, file.getName(), size));
            row.setTextColor(0xFFFFFFFF);
            row.setTextSize(17f);
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setBackgroundResource(R.drawable.settings_choice_bg);
            row.setFocusable(true);
            row.setClickable(true);
            row.setContentDescription(file.getName() + ", " + when.format(new Date(file.lastModified())));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
            lp.topMargin = dp(6);
            row.setLayoutParams(lp);
            row.setOnClickListener(v -> install(file));
            row.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_DEL) {
                    confirmDelete(file, row);
                    return true;
                }
                return false;
            });
            row.setOnLongClickListener(v -> {
                confirmDelete(file, row);
                return true;
            });
            files.addView(row);
        }
        files.post(() -> {
            if (files.getChildCount() > 0 && getCurrentFocus() == null) files.getChildAt(0).requestFocus();
        });
    }

    private List<File> received() {
        File[] all = new File(getFilesDir(), "inbox")
                .listFiles((dir, name) -> name.toLowerCase().endsWith(".apk"));
        List<File> list = all == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(all));
        list.sort((a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return list;
    }

    /**
     * MENU on a received file asks first: 删除 / 取消, the same shape as the drawer's uninstall sheet, so a
     * hurried key press cannot throw away an upload.
     */
    private void confirmDelete(File file, View row) {
        ViewGroup root = findViewById(R.id.shareRoot);
        final View sheet = getLayoutInflater().inflate(R.layout.view_action_sheet, root, false);
        TextView remove = sheet.findViewById(R.id.actionPrimary);
        remove.setText(R.string.fast_share_delete);
        remove.setOnClickListener(v -> {
            closeSheet(sheet, null);
            delete(file);
        });
        View cancel = sheet.findViewById(R.id.actionCancel);
        cancel.setOnClickListener(v -> closeSheet(sheet, row));
        sheet.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                closeSheet(sheet, row);
                return true;
            }
            return false;
        });
        root.addView(sheet, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        remove.requestFocus();
    }

    private void closeSheet(View sheet, View focusBack) {
        if (sheet.getParent() instanceof ViewGroup) ((ViewGroup) sheet.getParent()).removeView(sheet);
        if (focusBack != null) focusBack.requestFocus();
    }

    private void delete(File file) {
        String name = file.getName();
        boolean gone = file.delete();
        Toast.makeText(this, gone ? getString(R.string.fast_share_deleted, name)
                : getString(R.string.fast_share_failed), Toast.LENGTH_SHORT).show();
        showFiles();
        if (files != null && files.getChildCount() > 0) files.getChildAt(0).requestFocus();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ---- FastShare.Listener (main thread) ---------------------------------------------------------

    @Override
    public void onReceived(File apk, String name) {
        status.setText(getString(R.string.fast_share_received, name));
        showFiles();
        // The file stays in the inbox, so the list below is the record of what was sent.
        install(apk);
    }

    @Override
    public void onStatus(String state) {
        if ("waiting".equals(state)) status.setText(R.string.fast_share_waiting);
    }

    @Override
    public void onFailed(String reason) {
        int message;
        switch (reason) {
            case "no-network":
                message = R.string.fast_share_no_network;
                break;
            case "not-apk":
                message = R.string.fast_share_not_apk;
                break;
            case "too-large":
                message = R.string.fast_share_too_large;
                break;
            case "incomplete":
                message = R.string.fast_share_incomplete;
                break;
            default:
                message = R.string.fast_share_failed;
                break;
        }
        status.setText(message);
    }

    /** Hands the APK to the system installer; a TV asks once for "install unknown apps" first. */
    private void install(File apk) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            status.setText(R.string.fast_share_allow_install);
            Toast.makeText(this, R.string.fast_share_allow_install, Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
                return;
            } catch (ActivityNotFoundException ignored) {
            }
        }
        Uri uri = FileProvider.getUriForFile(this, AUTHORITY, apk);
        Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, R.string.fast_share_no_installer, Toast.LENGTH_LONG).show();
        }
    }
}

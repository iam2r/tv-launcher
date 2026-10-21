package com.iam2r.tvlauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.graphics.Outline;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The full app list of a shelf Home.
 *
 * <p>One view, two uses: Down opens it to start an app, MENU opens it to pin an app to a nest slot. Pinning
 * reuses the very same list - the only difference is that OK assigns instead of launching, and a clear-slot
 * card sits in front. System apps are never mixed with ordinary ones: they live in one "system apps" nest at
 * the end, and a system app is pinned by opening that nest first. The nest itself cannot be pinned.
 *
 * <p>Cheap on purpose: one full-screen view with a solid colour, one RecyclerView, icons loaded once per
 * opening. No blur, no bitmap of Home underneath, no polling, and the fade is skipped when animations are off.
 */
final class AppDrawer {
    interface Pick {
        /** A package to put in the slot, or "" to empty it. */
        void onPicked(String pkg);
    }

    private static final int COLUMNS = 5;

    static final class Item {
        static final int APP = 0, FOLDER = 1, CLEAR = 2;

        final int kind;
        final String label;
        final String pkg;
        final ComponentName component;
        final Drawable icon;
        final boolean leanback;
        final ApplicationInfo info;

        Item(int kind, String label, String pkg, ComponentName component, Drawable icon, boolean leanback) {
            this(kind, label, pkg, component, icon, leanback, null);
        }

        Item(int kind, String label, String pkg, ComponentName component, Drawable icon, boolean leanback,
             ApplicationInfo info) {
            this.kind = kind;
            this.label = label;
            this.pkg = pkg;
            this.component = component;
            this.icon = icon;
            this.leanback = leanback;
            this.info = info;
        }
    }

    private final Activity activity;
    private final boolean animations;
    private final boolean reducedMotion;
    private final boolean pickMode;
    private final Set<String> hidden;
    private final List<String> homeSlots;
    private final Pick picker;
    private final Runnable onClosed;
    private final float density;
    private final List<Item> apps = new ArrayList<>();
    private final List<Item> systemApps = new ArrayList<>();
    private final DrawerAdapter adapter = new DrawerAdapter();
    private View root;
    private RecyclerView grid;
    private TextView title;
    private TextView hintView;
    private boolean inFolder;
    private boolean closed;
    /** The MENU sheet: the app it acts on, and the view that holds it. */
    private Item actionItem;
    private View actionSheet;

    AppDrawer(Activity activity, boolean animations, boolean reducedMotion, Set<String> hidden,
              List<String> homeSlots, boolean pickMode, Pick picker, Runnable onClosed) {
        this.activity = activity;
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.hidden = hidden == null ? Collections.emptySet() : new HashSet<>(hidden);
        this.homeSlots = homeSlots == null ? Collections.emptyList() : new ArrayList<>(homeSlots);
        this.pickMode = pickMode;
        this.picker = picker;
        this.onClosed = onClosed;
        this.density = activity.getResources().getDisplayMetrics().density;
    }

    boolean isShowing() {
        return root != null && !closed;
    }

    void show() {
        load();
        ViewGroup content = activity.findViewById(android.R.id.content);
        root = LayoutInflater.from(activity).inflate(R.layout.view_drawer, content, false);
        grid = root.findViewById(R.id.drawerGrid);
        title = root.findViewById(R.id.drawerTitle);
        hintView = root.findViewById(R.id.drawerHint);
        grid.setLayoutManager(new GridLayoutManager(activity, COLUMNS));
        grid.setHasFixedSize(true);
        grid.setItemAnimator(null);
        grid.setAdapter(adapter);
        render();
        content.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.setAlpha(animations ? 0f : 1f);
        if (animations) {
            root.animate().alpha(1f).setDuration(reducedMotion ? 90 : 150)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        grid.post(() -> {
            if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
        });
    }

    /** Back: out of the system-apps nest first, then out of the drawer. Always consumed. */
    boolean back() {
        if (closed) return true;
        if (inFolder) {
            inFolder = false;
            render();
            grid.post(this::focusFolderCard);
            return true;
        }
        close();
        return true;
    }

    private void focusFolderCard() {
        for (int i = 0; i < adapter.items.size(); i++) {
            if (adapter.items.get(i).kind == Item.FOLDER) {
                RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(i);
                if (vh != null) {
                    vh.itemView.requestFocus();
                    return;
                }
            }
        }
        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
    }

    void close() {
        if (closed) return;
        hideActions();
        closed = true;
        if (root == null) {
            if (onClosed != null) onClosed.run();
            return;
        }
        final View dying = root;
        root = null;
        if (!animations) {
            detach(dying);
            return;
        }
        dying.animate().alpha(0f).setDuration(reducedMotion ? 80 : 130).withEndAction(() -> detach(dying)).start();
    }

    private void hideActions() {
        if (actionSheet != null && actionSheet.getParent() instanceof ViewGroup) {
            ((ViewGroup) actionSheet.getParent()).removeView(actionSheet);
        }
        actionSheet = null;
        actionItem = null;
    }

    private void detach(View view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup) view.getParent()).removeView(view);
        if (onClosed != null) onClosed.run();
    }

    // ---- the list --------------------------------------------------------------------------------

    private void load() {
        PackageManager pm = activity.getPackageManager();
        Intent leanback = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Intent plain = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Map<String, ResolveInfo> best = new LinkedHashMap<>();
        Set<String> tv = new HashSet<>();
        for (ResolveInfo ri : pm.queryIntentActivities(leanback, 0)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            best.put(pkg, ri);
            tv.add(pkg);
        }
        for (ResolveInfo ri : pm.queryIntentActivities(plain, 0)) {
            if (ri.activityInfo == null || !ri.activityInfo.exported) continue;
            String pkg = ri.activityInfo.packageName;
            if (!best.containsKey(pkg)) best.put(pkg, ri);
        }
        for (Map.Entry<String, ResolveInfo> e : best.entrySet()) {
            String pkg = e.getKey();
            ResolveInfo ri = e.getValue();
            if (pkg.equals(activity.getPackageName()) || hidden.contains(pkg)) continue;
            ApplicationInfo info = ri.activityInfo.applicationInfo;
            if (info == null) continue;
            String label = Cards.cleanLabel(String.valueOf(ri.loadLabel(pm)), pkg);
            ComponentName component = new ComponentName(pkg, ri.activityInfo.name);
            Item item = new Item(Item.APP, label, pkg, component, ri.loadIcon(pm), tv.contains(pkg), info);
            boolean system = (info.flags & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
            (system ? systemApps : apps).add(item);
        }
        Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Collections.sort(systemApps, (a, b) -> a.label.compareToIgnoreCase(b.label));
    }

    private List<Item> itemsForView() {
        List<Item> items = new ArrayList<>();
        if (inFolder) {
            items.addAll(systemApps);
            return items;
        }
        if (pickMode) items.add(new Item(Item.CLEAR, activity.getString(R.string.slot_clear), "", null, null, false));
        items.addAll(apps);
        if (!systemApps.isEmpty()) items.add(new Item(Item.FOLDER, activity.getString(R.string.system_apps), "", null, null, false));
        return items;
    }

    private void render() {
        adapter.submit(itemsForView());
        if (inFolder) {
            title.setText(R.string.system_apps);
            hintView.setText(activity.getString(R.string.apps_count, systemApps.size()));
        } else if (pickMode) {
            title.setText(R.string.slot_choose_app);
            hintView.setText(R.string.drawer_close_hint);
        } else {
            title.setText(R.string.all_apps);
            hintView.setText(activity.getString(R.string.apps_count, apps.size() + systemApps.size()));
        }
    }

    /**
     * MENU on an app: its actions. Only apps the owner installed get "Uninstall" - a system app cannot be
     * removed, so the entry is not offered at all rather than shown and failing.
     */
    private boolean showActions(int position) {
        if (pickMode || position < 0 || position >= adapter.items.size()) return false;
        Item item = adapter.items.get(position);
        if (item.kind != Item.APP) return false;
        boolean system = item.info != null && (item.info.flags
                & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        if (system) {
            Toast.makeText(activity, R.string.uninstall_locked, Toast.LENGTH_SHORT).show();
            return true;
        }
        hideActions();
        View sheet = LayoutInflater.from(activity).inflate(R.layout.view_action_sheet, (ViewGroup) root, false);
        TextView uninstall = sheet.findViewById(R.id.actionPrimary);
        uninstall.setText(R.string.uninstall);
        View cancel = sheet.findViewById(R.id.actionCancel);
        uninstall.setOnClickListener(v -> {
            hideActions();
            uninstall(item);
        });
        cancel.setOnClickListener(v -> {
            hideActions();
            RecyclerView.ViewHolder back = grid.findViewHolderForAdapterPosition(position);
            if (back != null) back.itemView.requestFocus();
        });
        sheet.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                hideActions();
                RecyclerView.ViewHolder back = grid.findViewHolderForAdapterPosition(position);
                if (back != null) back.itemView.requestFocus();
                return true;
            }
            return false;
        });
        ((ViewGroup) root).addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        actionSheet = sheet;
        actionItem = item;
        uninstall.requestFocus();
        return true;
    }

    /** The system uninstall dialog; the launcher refreshes when the package actually goes. */
    private void uninstall(Item item) {
        try {
            // The drawer would be stale the moment the app disappears, so close it and let Home rebuild.
            close();
            activity.startActivity(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + item.pkg)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, item.label),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---- adapter ---------------------------------------------------------------------------------

    private final class DrawerAdapter extends RecyclerView.Adapter<DrawerHolder> {
        private final List<Item> items = new ArrayList<>();

        void submit(List<Item> next) {
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
            if (grid != null) grid.scrollToPosition(0);
        }

        @NonNull
        @Override
        public DrawerHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new DrawerHolder(parent);
        }

        @Override
        public void onBindViewHolder(@NonNull DrawerHolder h, int position) {
            h.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private final class DrawerHolder extends RecyclerView.ViewHolder {
        private final View card;
        private final View surface;
        private final ImageView art;
        private final ImageView logo;
        private final TextView add;
        private final TextView name;
        private final View folder;
        private final ImageView[] minis = new ImageView[4];
        private int cardHeight;

        DrawerHolder(ViewGroup parent) {
            super(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_slot, parent, false));
            card = itemView.findViewById(R.id.slotCard);
            surface = itemView.findViewById(R.id.slotSurface);
            art = itemView.findViewById(R.id.slotArt);
            logo = itemView.findViewById(R.id.slotLogo);
            add = itemView.findViewById(R.id.slotAdd);
            name = itemView.findViewById(R.id.slotName);
            folder = itemView.findViewById(R.id.slotFolder);
            minis[0] = itemView.findViewById(R.id.slotFolder0);
            minis[1] = itemView.findViewById(R.id.slotFolder1);
            minis[2] = itemView.findViewById(R.id.slotFolder2);
            minis[3] = itemView.findViewById(R.id.slotFolder3);

            int width = parent.getWidth() > 0 ? parent.getWidth() : parent.getMeasuredWidth();
            int inner = width - parent.getPaddingLeft() - parent.getPaddingRight();
            int cell = inner > 0 ? inner / COLUMNS : 0;
            int cardW = Math.round(AppAdapter.SLOT_CARD_W_DP * density);
            if (cell > 0) cardW = Math.min(cardW, cell - Math.round(18 * density));
            cardW = Math.max(Math.round(96 * density), cardW);
            cardHeight = Math.round(cardW * AppAdapter.SLOT_CARD_H_DP / AppAdapter.SLOT_CARD_W_DP);
            int nameH = Math.round(22 * density);
            final float radius = SlotCard.radiusPx(density);
            surface.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            surface.setClipToOutline(true);

            ViewGroup.LayoutParams cardLp = card.getLayoutParams();
            cardLp.width = cardW;
            cardLp.height = cardHeight + nameH + Math.round(6 * density);
            card.setLayoutParams(cardLp);
            ViewGroup.LayoutParams surfaceLp = surface.getLayoutParams();
            surfaceLp.width = cardW;
            surfaceLp.height = cardHeight;
            surface.setLayoutParams(surfaceLp);
            ViewGroup.MarginLayoutParams nameLp = (ViewGroup.MarginLayoutParams) name.getLayoutParams();
            nameLp.width = cardW + Math.round(20 * density);
            nameLp.topMargin = cardHeight + Math.round(6 * density);
            name.setLayoutParams(nameLp);

            card.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION) return false;
                if (keyCode == KeyEvent.KEYCODE_MENU) return showActions(pos);
                return onDirectionKey(pos, keyCode);
            });
            card.setOnFocusChangeListener((v, focused) -> {
                v.animate().cancel();
                v.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                        .setDuration(focused ? 120 : 90)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            });
        }

        /**
         * Left and right wrap to the neighbouring row instead of escaping the drawer, and up leaves the first
         * row (out of the system-apps nest, or out of the drawer entirely). Down past the last row stays put.
         */
        private boolean onDirectionKey(int pos, int keyCode) {
            int count = adapter.getItemCount();
            int row = pos / COLUMNS;
            int column = pos % COLUMNS;
            int rowStart = row * COLUMNS;
            int rowEnd = Math.min(rowStart + COLUMNS, count) - 1;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    if (column > 0 && pos > rowStart) focusAt(pos - 1);
                    else if (row > 0) focusAt(rowStart - 1);
                    else return false;
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (pos < rowEnd) focusAt(pos + 1);
                    else if (rowEnd < count - 1) focusAt(rowEnd + 1);
                    else return false;
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (row > 0) {
                        focusAt(Math.max(0, pos - COLUMNS));
                        return true;
                    }
                    return back();
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (pos + COLUMNS < count) {
                        focusAt(pos + COLUMNS);
                        return true;
                    }
                    return true;
                default:
                    return false;
            }
        }

        private void focusAt(int position) {
            RecyclerView.ViewHolder vh = grid.findViewHolderForAdapterPosition(position);
            if (vh != null) {
                vh.itemView.requestFocus();
            } else {
                grid.scrollToPosition(position);
                grid.post(() -> {
                    RecyclerView.ViewHolder late = grid.findViewHolderForAdapterPosition(position);
                    if (late != null) late.itemView.requestFocus();
                });
            }
        }

        void bind(Item item) {
            boolean isFolder = item.kind == Item.FOLDER;
            boolean glyph = item.kind == Item.CLEAR;
            folder.setVisibility(isFolder ? View.VISIBLE : View.GONE);
            add.setVisibility(glyph ? View.VISIBLE : View.GONE);
            if (glyph) add.setText("\u00D7");
            art.setImageDrawable(null);
            art.setVisibility(View.GONE);
            // Reset what the nest's contained-icon branch changes, or recycled cards keep that styling when
            // the list goes back to the drawer (icons drawn fit-and-padded inside an empty card).
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setPadding(0, 0, 0, 0);
            logo.setImageDrawable(null);
            logo.setVisibility(View.GONE);
            if (isFolder) {
                surface.setBackground(ContextCompat.getDrawable(activity, R.drawable.slot_card_empty));
                for (int i = 0; i < minis.length; i++) {
                    minis[i].setImageDrawable(i < systemApps.size() ? systemApps.get(i).icon : null);
                }
            } else if (glyph) {
                surface.setBackground(SlotCard.neutral(density));
            } else if (inFolder) {
                // Inside the system-apps nest every app keeps its card, but the icon is drawn whole and
                // smaller: these icons are detailed (gears, tiles, meters) and cropping them to fill the card
                // cuts the artwork off.
                SlotCard.Look look = SlotCard.look(item.pkg, item.icon, null, item.info,
                        activity.getPackageManager(), density);
                // The card is this app's own colour, so the grid keeps the reference's coloured cards; the icon is
                // drawn whole on top of it.
                surface.setBackground(SlotCard.tint(item.icon, density));
                art.setImageDrawable(item.icon);
                art.setVisibility(View.VISIBLE);
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int pad = Math.round(cardHeight * 0.20f);
                art.setPadding(pad, pad, pad, pad);
                name.setText(item.label);
                card.setContentDescription(item.label);
                card.setOnClickListener(v -> open(item));
                return;
            } else {
                SlotCard.Look look = SlotCard.look(item.pkg, item.icon, null, item.info,
                        activity.getPackageManager(), density);
                if (look.art != null) {
                    art.setImageDrawable(look.art);
                    art.setVisibility(View.VISIBLE);
                    surface.setBackground(null);
                } else {
                    surface.setBackground(look.fill);
                }
                if (look.logo != null) {
                    logo.setImageDrawable(look.logo);
                    logo.setVisibility(View.VISIBLE);
                    int size = Math.round(cardHeight * look.logoScale);
                    ViewGroup.LayoutParams lp = logo.getLayoutParams();
                    lp.width = size;
                    lp.height = size;
                    logo.setLayoutParams(lp);
                }
            }
            name.setText(item.label);
            card.setContentDescription(item.label);
            card.setOnClickListener(v -> open(item));
        }

        private void open(Item item) {
            switch (item.kind) {
                case Item.FOLDER:
                    inFolder = true;
                    render();
                    grid.post(() -> {
                        if (grid.getChildCount() > 0) grid.getChildAt(0).requestFocus();
                    });
                    break;
                case Item.CLEAR:
                    if (picker != null) picker.onPicked("");
                    break;
                default:
                    android.util.Log.i("TvHomePin", "drawer open " + item.label + " pkg=" + item.pkg
                            + " pick=" + pickMode);
                    if (pickMode) {
                        if (picker != null) picker.onPicked(item.pkg);
                    } else {
                        launch(item);
                    }
                    break;
            }
        }

        /** Same launch intent the tiles use: the TV entry when the app has one, so a running app resumes. */
        private void launch(Item item) {
            Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(item.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            if (item.leanback) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            close();
            try {
                activity.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, item.label),
                        Toast.LENGTH_SHORT).show();
            }
        }
    }
}

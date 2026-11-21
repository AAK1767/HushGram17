/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.settings;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateUtils;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.hushgram.extension.instagram.direct.InstantsVault;
import app.hushgram.extension.instagram.download.MediaSave;
import app.hushgram.extension.instagram.download.MediaSave.Rendition;
import app.hushgram.extension.instagram.download.PictureFetch;
import app.hushgram.extension.instagram.download.PostDetails;
import app.hushgram.extension.shared.L10n;
import app.hushgram.extension.shared.Utils;
import app.hushgram.extension.shared.ui.Dim;

/**
 * Saved Instants page: shows every Instant captured while viewing ephemeral media.
 * Tapping an item opens view, download, copy link, and deletion options.
 * HushGram17 build 17.4 test.
 */
@SuppressWarnings("deprecation")
final class InstantsVaultPage {
    static final int GRID_COLUMNS = 3;
    static final int PICTURE_DP = 48;

    private static final int VIEW = 0;
    private static final int COPY_LINK = 1;
    private static final int DOWNLOAD = 2;
    private static final int REMOVE = 3;
    private static final int DELETE_GROUP = 4;
    private static final int GROUP_DOWNLOAD_ALL = 5;

    private static boolean sAsGrid = true;
    private static boolean sGroupByUser = false;
    private static boolean sSortNewest = true;

    final Dialog dialog;
    final EditText search;
    final ListView list;
    final GridView grid;
    final ImageButton layout;
    final TextView empty;
    private final ScreenColors palette = ScreenColors.current();
    private final Rows rows;
    private final Tiles tiles;
    private final Drawable nothingYet;
    private final Drawable nothingMatches;
    private final Preference origin;
    private List<Item> all = Collections.emptyList();

    static class Item {
        final String mediaId;
        final String username;
        final String imageUrl;
        final String videoUrl;
        final boolean isVideo;
        final Long takenAtMs;

        Item(String[] row) {
            this.mediaId = row[0];
            this.username = row[1] == null ? "" : row[1];
            this.imageUrl = row[2];
            this.videoUrl = row[3];
            this.isVideo = "1".equals(row[4]);
            this.takenAtMs = row[5] == null ? null : Long.parseLong(row[5]);
        }
    }

    /** A sentinel subclass used as group-header rows. */
    static final class GroupHeader extends Item {
        final String groupUsername;
        final List<Item> members;

        GroupHeader(String username, List<Item> members) {
            super(new String[]{username, username, null, null, "0", null});
            this.groupUsername = username;
            this.members = members;
        }
    }

    private InstantsVaultPage(Context context, Preference row) {
        origin = row;
        dialog = new Dialog(context, palette.theme());
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(palette.background);

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPaddingRelative(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
        bar.addView(iconButton(context, palette, SettingsIcons.BACK, L10n.t(context, "Back"), v -> dialog.dismiss()),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        TextView title = new TextView(context);
        title.setText(L10n.t(context, "Saved Instants"));
        title.setTextColor(palette.title);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        title.setAccessibilityHeading(true);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        // Sort button
        ImageButton sortBtn = iconButton(context, palette, SettingsIcons.UPDATES,
                L10n.t(context, sSortNewest ? "Sort oldest first" : "Sort newest first"),
                v -> {
                    sSortNewest = !sSortNewest;
                    paintIcon((ImageButton) v, palette, SettingsIcons.UPDATES,
                            L10n.t(context, sSortNewest ? "Sort oldest first" : "Sort newest first"));
                    show();
                });
        bar.addView(sortBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        // Group by user button
        ImageButton groupBtn = iconButton(context, palette, SettingsIcons.PERSON,
                L10n.t(context, sGroupByUser ? "Ungroup" : "Group by username"),
                v -> {
                    sGroupByUser = !sGroupByUser;
                    paintIcon((ImageButton) v, palette, SettingsIcons.PERSON,
                            L10n.t(context, sGroupByUser ? "Ungroup" : "Group by username"));
                    show();
                });
        bar.addView(groupBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        // Allowlist button
        ImageButton allowlistBtn = iconButton(context, palette, SettingsIcons.SEARCH,
                L10n.t(context, "Capture from"),
                v -> openAllowlistDialog(context));
        bar.addView(allowlistBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        layout = iconButton(context, palette, SettingsIcons.GRID, L10n.t(context, "Show as grid"), v -> showAsGrid(!sAsGrid));
        paintLayoutButton();
        bar.addView(layout, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        bar.addView(iconButton(context, palette, SettingsIcons.DELETE, L10n.t(context, "Clear saved Instants"), v -> clearAll()),
                new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));
        root.addView(bar);

        search = new EditText(context);
        search.setHint(L10n.t(context, "Search"));
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        ScreenColors.styleField(search);

        Drawable glass = SettingsIcons.icon(context, SettingsIcons.SEARCH, palette.summary);
        glass.setBounds(0, 0, Dim.dp(20), Dim.dp(20));
        search.setCompoundDrawablesRelative(glass, null, null, null);
        search.setCompoundDrawablePadding(Dim.dp(12));

        LinearLayout.LayoutParams searchSpace = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchSpace.setMarginStart(Dim.dp(18));
        searchSpace.setMarginEnd(Dim.dp(18));
        searchSpace.topMargin = Dim.dp(4);
        searchSpace.bottomMargin = Dim.dp(4);
        root.addView(search, searchSpace);

        FrameLayout body = new FrameLayout(context);
        list = new ListView(context);
        list.setDivider(null);
        list.setCacheColorHint(Color.TRANSPARENT);
        list.setClipToPadding(false);
        rows = new Rows(context, palette);
        list.setAdapter(rows);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Item item = rows.get(position);
            if (item instanceof GroupHeader) return;
            options(view, item);
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Item item = rows.get(position);
            if (item instanceof GroupHeader) return true;
            options(view, item);
            return true;
        });
        body.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        grid = new GridView(context);
        grid.setNumColumns(GRID_COLUMNS);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Dim.dp(2));
        grid.setVerticalSpacing(Dim.dp(2));
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        tiles = new Tiles(context, palette, tileSize(context));
        grid.setAdapter(tiles);
        grid.setOnItemClickListener((parent, view, position, id) -> options(view, tiles.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            options(view, tiles.get(position));
            return true;
        });
        body.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        empty = new TextView(context);
        empty.setTextColor(palette.summary);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(Dim.dp(32), Dim.dp(32), Dim.dp(32), Dim.dp(32));
        empty.setCompoundDrawablePadding(Dim.dp(12));

        nothingYet = emptyMark(context, SettingsIcons.UPDATES);
        nothingMatches = emptyMark(context, SettingsIcons.SEARCH);
        body.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) { show(); }
        });

        SettingsDialog.keepOffTheBars(root);
        FocusRest.on(root);
        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(palette.background));
            palette.paintBars(window);
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.setNavigationBarContrastEnforced(false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        }
    }

    static InstantsVaultPage open(Context context, Preference row) {
        InstantsVaultPage page = new InstantsVaultPage(context, row);
        page.read();
        page.dialog.show();
        return page;
    }

    /** Opens the allowlist dialog. Can be called from outside (e.g. MessagesCategory). */
    static void openAllowlistDialog(Context context) {
        EditText field = new EditText(context);
        field.setHint(L10n.t(context, "e.g. john, mary (empty = everyone)"));
        field.setText(Settings.SAVE_INSTANTS_ALLOWLIST.get());
        field.setSingleLine(false);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int pad = Dim.dp(16);
        field.setPadding(pad, pad, pad, pad);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(pad, pad / 2, pad, 0);

        TextView subtitle = new TextView(context);
        subtitle.setText(L10n.t(context, "Usernames to capture (comma-separated, empty = all)"));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        subtitle.setPaddingRelative(0, 0, 0, Dim.dp(4));
        container.addView(subtitle);
        container.addView(field);

        new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "Capture from"))
                .setView(container)
                .setPositiveButton(L10n.t(context, "OK"), (d, w) -> {
                    String value = field.getText() == null ? "" : field.getText().toString();
                    Settings.SAVE_INSTANTS_ALLOWLIST.save(value.trim());
                })
                .setNegativeButton(L10n.t(context, "Cancel"), null)
                .show();
    }

    private void read() {
        Context application = dialog.getContext().getApplicationContext();
        if (!Utils.runOnBackgroundThread(() -> {
            List<String[]> rowsList = InstantsVault.Database.get(application).allRows();
            List<Item> items = new ArrayList<>();
            for (String[] r : rowsList) {
                items.add(new Item(r));
            }
            Utils.runOnMainThread(() -> {
                all = items;
                show();
            });
        })) {
            List<String[]> rowsList = InstantsVault.Database.get(application).allRows();
            List<Item> items = new ArrayList<>();
            for (String[] r : rowsList) {
                items.add(new Item(r));
            }
            all = items;
            show();
        }
    }

    private void showAsGrid(boolean tiled) {
        sAsGrid = tiled;
        paintLayoutButton();
        show();
    }

    private void paintLayoutButton() {
        paintIcon(layout, palette, sAsGrid ? SettingsIcons.LIST : SettingsIcons.GRID,
                L10n.t(dialog.getContext(), sAsGrid ? "Show as list" : "Show as grid"));
    }

    private void show() {
        List<Item> filtered = matching(all, String.valueOf(search.getText()));
        List<Item> shown = sorted(filtered);

        boolean useGrid = sAsGrid && !sGroupByUser;

        if (sGroupByUser) {
            List<Item> grouped = buildGrouped(shown);
            rows.set(grouped);
            tiles.set(Collections.emptyList());
            list.setVisibility(!grouped.isEmpty() ? View.VISIBLE : View.GONE);
            grid.setVisibility(View.GONE);
        } else {
            rows.set(shown);
            tiles.set(shown);
            list.setVisibility(!shown.isEmpty() && !sAsGrid ? View.VISIBLE : View.GONE);
            grid.setVisibility(!shown.isEmpty() && sAsGrid ? View.VISIBLE : View.GONE);
        }

        boolean isEmpty = sGroupByUser ? rows.getCount() == 0 : shown.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        Context context = dialog.getContext();
        empty.setText(all.isEmpty() ? L10n.t(context, "No saved Instants yet.")
                : L10n.t(context, "Nothing matches."));
        empty.setCompoundDrawablesRelative(null, all.isEmpty() ? nothingYet : nothingMatches, null, null);
    }

    private static List<Item> sorted(List<Item> items) {
        List<Item> copy = new ArrayList<>(items);
        copy.sort((a, b) -> {
            long ta = a.takenAtMs != null ? a.takenAtMs : 0L;
            long tb = b.takenAtMs != null ? b.takenAtMs : 0L;
            return sSortNewest ? Long.compare(tb, ta) : Long.compare(ta, tb);
        });
        return copy;
    }

    private List<Item> buildGrouped(List<Item> items) {
        // Group by username, preserving insertion order
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        for (Item item : items) {
            String key = item.username.isEmpty() ? "" : item.username;
            if (!groups.containsKey(key)) groups.put(key, new ArrayList<>());
            groups.get(key).add(item);
        }
        List<Item> flat = new ArrayList<>();
        for (Map.Entry<String, List<Item>> entry : groups.entrySet()) {
            List<Item> members = sorted(entry.getValue());
            flat.add(new GroupHeader(entry.getKey(), members));
            flat.addAll(members);
        }
        return flat;
    }

    private static List<Item> matching(List<Item> items, @Nullable String query) {
        String wanted = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (wanted.startsWith("@")) wanted = wanted.substring(1);
        if (wanted.isEmpty()) return items;
        List<Item> kept = new ArrayList<>();
        for (Item item : items) {
            if (item.username.toLowerCase(Locale.ROOT).contains(wanted)
                    || item.mediaId.toLowerCase(Locale.ROOT).contains(wanted)) {
                kept.add(item);
            }
        }
        return kept;
    }

    private static void confirm(Context context, String title, String message, String positiveLabel, Runnable onConfirm) {
        new AlertDialog.Builder(context)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveLabel, (d, w) -> onConfirm.run())
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
    }

    private void clearAll() {
        Context context = dialog.getContext();
        confirm(context, L10n.t("Clear saved Instants"),
                L10n.t("Are you sure you want to clear all saved Instants? This cannot be undone."),
                L10n.t("Clear all"),
                () -> {
                    Utils.runOnBackgroundThread(() -> {
                        InstantsVault.Database.get(context).clearAll();
                        forgetPictures();
                        Utils.runOnMainThread(() -> {
                            read();
                            Utils.showToastShort(L10n.t("Saved Instants cleared."));
                        });
                    });
                });
    }

    private void options(View anchor, Item item) {
        PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        Menu m = menu.getMenu();
        m.add(Menu.NONE, VIEW, Menu.NONE, L10n.t("View"));
        m.add(Menu.NONE, DOWNLOAD, Menu.NONE, L10n.t("Download"));
        m.add(Menu.NONE, COPY_LINK, Menu.NONE, L10n.t("Copy link"));
        m.add(Menu.NONE, REMOVE, Menu.NONE, L10n.t("Remove"));
        menu.setOnMenuItemClickListener(entry -> pick(entry.getItemId(), item));
        menu.show();
    }

    private void groupOptions(View anchor, GroupHeader header) {
        Context context = anchor.getContext();
        PopupMenu menu = new PopupMenu(context, anchor);
        Menu m = menu.getMenu();
        m.add(Menu.NONE, GROUP_DOWNLOAD_ALL, Menu.NONE, L10n.t("Download all"));
        m.add(Menu.NONE, DELETE_GROUP, Menu.NONE, L10n.t("Delete group"));
        menu.setOnMenuItemClickListener(entry -> pickGroup(entry.getItemId(), header, context));
        menu.show();
    }

    private boolean pickGroup(int id, GroupHeader header, Context context) {
        switch (id) {
            case GROUP_DOWNLOAD_ALL:
                for (Item item : header.members) {
                    Date posted = item.takenAtMs == null ? null : new Date(item.takenAtMs);
                    PostDetails details = PostDetails.of(item.mediaId, item.username, posted);
                    if (item.isVideo && item.videoUrl != null && !item.videoUrl.isEmpty()) {
                        MediaSave.saveVideo(context, Collections.singletonList(Rendition.of(item.videoUrl)), null, details);
                    } else if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                        MediaSave.savePhoto(context, Collections.singletonList(Rendition.of(item.imageUrl)), details);
                    }
                }
                return true;

            case DELETE_GROUP:
                String who = header.groupUsername.isEmpty() ? L10n.t("Unknown") : "@" + header.groupUsername;
                confirm(context, L10n.t("Delete group"),
                        L10n.t("Delete all saved Instants from ") + who + "?",
                        L10n.t("Delete"),
                        () -> {
                            Utils.runOnBackgroundThread(() -> {
                                InstantsVault.Database.get(context).deleteByUsername(header.groupUsername);
                                Utils.runOnMainThread(() -> {
                                    List<Item> updated = new ArrayList<>(all);
                                    updated.removeIf(item -> header.groupUsername.equals(item.username));
                                    all = updated;
                                    show();
                                    Utils.showToastShort(L10n.t("Group deleted."));
                                });
                            });
                        });
                return true;

            default:
                return false;
        }
    }

    private boolean pick(int id, Item item) {
        Context context = dialog.getContext();
        switch (id) {
            case VIEW:
                viewInstant(context, item);
                return true;

            case DOWNLOAD:
                Date posted = item.takenAtMs == null ? null : new Date(item.takenAtMs);
                PostDetails details = PostDetails.of(item.mediaId, item.username, posted);
                if (item.isVideo && item.videoUrl != null && !item.videoUrl.isEmpty()) {
                    MediaSave.saveVideo(context, Collections.singletonList(Rendition.of(item.videoUrl)), null, details);
                } else if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
                    MediaSave.savePhoto(context, Collections.singletonList(Rendition.of(item.imageUrl)), details);
                } else {
                    Utils.showToastShort(L10n.t("No media address available."));
                }
                return true;

            case COPY_LINK:
                String link = item.isVideo && item.videoUrl != null ? item.videoUrl : item.imageUrl;
                if (link != null && !link.isEmpty()) {
                    Utils.setClipboard(context, "Instagram", link);
                    Utils.showToastShort(L10n.t("Copied"));
                }
                return true;

            case REMOVE:
                confirm(context, L10n.t("Remove Instant"),
                        L10n.t("Remove this saved Instant?"),
                        L10n.t("Remove"),
                        () -> {
                            InstantsVault.Database.get(context).delete(item.mediaId);
                            forgetPicture(item);
                            List<Item> left = new ArrayList<>(all);
                            left.remove(item);
                            all = left;
                            show();
                            Utils.showToastShort(L10n.t("Removed"));
                        });
                return true;

            default:
                return false;
        }
    }

    private void viewInstant(Context context, Item item) {
        Dialog viewer = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        if (item.isVideo && item.videoUrl != null && !item.videoUrl.isEmpty()) {
            VideoView videoView = new VideoView(context);
            FrameLayout.LayoutParams vlp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            root.addView(videoView, vlp);

            ProgressBar spinner = new ProgressBar(context);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(spinner, slp);

            videoView.setVideoURI(Uri.parse(item.videoUrl));
            videoView.setOnPreparedListener(mp -> {
                spinner.setVisibility(View.GONE);
                mp.setLooping(true);
                videoView.start();
            });
            videoView.setOnErrorListener((mp, what, extra) -> {
                spinner.setVisibility(View.GONE);
                Utils.showToastShort(L10n.t("Couldn't play this video"));
                return true;
            });
            videoView.setOnClickListener(v -> {
                if (videoView.isPlaying()) videoView.pause();
                else videoView.start();
            });
        } else if (item.imageUrl != null && !item.imageUrl.isEmpty()) {
            ImageView imageView = new ImageView(context);
            imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER);
            root.addView(imageView, ilp);

            ProgressBar spinner = new ProgressBar(context);
            FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            root.addView(spinner, slp);

            String cacheKey = key(item, 2048);
            Bitmap cached = PICTURES.get(cacheKey);
            if (cached != null) {
                spinner.setVisibility(View.GONE);
                imageView.setImageBitmap(cached);
            } else {
                Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;
                Utils.runOnBackgroundThread(() -> {
                    Bitmap bmp = PictureFetch.fetch(appCtx, item.imageUrl, 2048);
                    if (bmp != null) PICTURES.put(cacheKey, bmp);
                    Utils.runOnMainThread(() -> {
                        spinner.setVisibility(View.GONE);
                        if (bmp != null) {
                            imageView.setImageBitmap(bmp);
                        } else {
                            Utils.showToastShort(L10n.t("Failed to load picture"));
                        }
                    });
                });
            }
            imageView.setOnClickListener(v -> viewer.dismiss());
        }

        LinearLayout topBar = new LinearLayout(context);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(Dim.dp(8), Dim.dp(8), Dim.dp(8), Dim.dp(8));
        topBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));

        ImageButton closeBtn = iconButton(context, palette, SettingsIcons.BACK, L10n.t("Back"), v -> viewer.dismiss());
        topBar.addView(closeBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        TextView title = new TextView(context);
        String who = item.username.isEmpty() ? L10n.t(context, "Instant") : "@" + item.username;
        title.setText(who);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setPaddingRelative(Dim.dp(8), 0, 0, 0);
        topBar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton dlBtn = iconButton(context, palette, SettingsIcons.DOWNLOADS, L10n.t("Download"), v -> {
            pick(DOWNLOAD, item);
        });
        topBar.addView(dlBtn, new LinearLayout.LayoutParams(Dim.dp(48), Dim.dp(48)));

        root.addView(topBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        viewer.setContentView(root);
        Window w = viewer.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        }
        viewer.show();
    }

    static int tileSize(Context context) {
        return Math.max(1, context.getResources().getDisplayMetrics().widthPixels / GRID_COLUMNS);
    }

    private static final LruCache<String, Bitmap> PICTURES = new LruCache<String, Bitmap>(ViewingHistoryPage.cacheBytes()) {
        @Override protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private static final ConcurrentHashMap<String, Object> LOADING = new ConcurrentHashMap<>();

    private static String key(Item item) {
        return item.mediaId;
    }

    private static String key(Item item, int size) {
        return item.mediaId + "\t" + size;
    }

    @Nullable
    private static Bitmap load(Context application, Item item, int size) {
        String k = key(item);
        synchronized (LOADING.computeIfAbsent(k, ignored -> new Object())) {
            String cacheKey = key(item, size);
            Bitmap cached = PICTURES.get(cacheKey);
            if (cached != null) return cached;
            if (item.imageUrl == null || item.imageUrl.isEmpty()) return null;
            Bitmap bitmap = PictureFetch.fetch(application, item.imageUrl, size);
            if (bitmap != null) PICTURES.put(cacheKey, bitmap);
            return bitmap;
        }
    }

    static void forgetPictures() {
        PICTURES.evictAll();
    }

    static void forgetPicture(Item item) {
        String held = key(item) + "\t";
        for (String key : PICTURES.snapshot().keySet()) {
            if (key.startsWith(held) || key.equals(item.mediaId)) PICTURES.remove(key);
        }
    }

    private static void showPicture(Context context, ScreenColors palette, ImageView view, Item item,
                                    int size, int inset) {
        String cacheKey = key(item, size);
        view.setTag(cacheKey);
        Bitmap cached = PICTURES.get(cacheKey);
        if (cached != null) {
            fill(view, cached);
            return;
        }
        view.setScaleType(ImageView.ScaleType.FIT_CENTER);
        view.setPadding(inset, inset, inset, inset);
        view.setImageDrawable(SettingsIcons.icon(context, item.isVideo ? SettingsIcons.REELS : SettingsIcons.FEED, palette.summary));
        Context application = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utils.runOnBackgroundThread(() -> {
            Bitmap bitmap = load(application, item, size);
            if (bitmap == null) return;
            Utils.runOnMainThread(() -> {
                if (cacheKey.equals(view.getTag())) fill(view, bitmap);
            });
        });
    }

    private static void fill(ImageView view, Bitmap bitmap) {
        view.setScaleType(ImageView.ScaleType.CENTER_CROP);
        view.setPadding(0, 0, 0, 0);
        view.setImageBitmap(bitmap);
    }

    private Drawable emptyMark(Context context, String icon) {
        Drawable mark = SettingsIcons.icon(context, icon, palette.hint);
        mark.setBounds(0, 0, Dim.dp(40), Dim.dp(40));
        return mark;
    }

    private static ImageButton iconButton(Context context, ScreenColors palette, String icon, String label, View.OnClickListener tap) {
        ImageButton button = new ImageButton(context);
        paintIcon(button, palette, icon, label);
        button.setPadding(Dim.dp(12), Dim.dp(12), Dim.dp(12), Dim.dp(12));
        button.setBackground(ripple(palette));
        button.setOnClickListener(tap);
        return button;
    }

    private static void paintIcon(ImageButton button, ScreenColors palette, String icon, String label) {
        Drawable drawable = SettingsIcons.icon(button.getContext(), icon, palette.title);
        drawable.setBounds(0, 0, Dim.dp(24), Dim.dp(24));
        button.setImageDrawable(drawable);
        button.setContentDescription(label);
    }

    private static RippleDrawable ripple(ScreenColors palette) {
        return new RippleDrawable(ColorStateList.valueOf(ScreenColors.half(palette.heading)), null, new ColorDrawable(Color.WHITE));
    }

    final class Rows extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private List<Item> shown = Collections.emptyList();

        Rows(Context context, ScreenColors palette) {
            this.context = context;
            this.palette = palette;
        }

        void set(List<Item> items) {
            shown = items;
            notifyDataSetChanged();
        }

        Item get(int position) {
            return shown.get(position);
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) {
            return shown.get(position) instanceof GroupHeader ? 0 : 1;
        }

        @Override public View getView(int position, View reused, ViewGroup parent) {
            Item item = shown.get(position);

            if (item instanceof GroupHeader) {
                GroupHeader header = (GroupHeader) item;
                LinearLayout row;
                if (reused instanceof LinearLayout && "header".equals(reused.getTag())) {
                    row = (LinearLayout) reused;
                } else {
                    row = makeHeader();
                }
                TextView label = (TextView) row.getChildAt(0);
                String displayName = header.groupUsername.isEmpty()
                        ? L10n.t(context, "Unknown") : "@" + header.groupUsername;
                label.setText(displayName);

                ImageButton menuBtn = (ImageButton) row.getChildAt(1);
                menuBtn.setOnClickListener(v -> groupOptions(v, header));
                return row;
            }

            LinearLayout row;
            if (reused instanceof LinearLayout && "item".equals(reused.getTag())) {
                row = (LinearLayout) reused;
            } else {
                row = makeItem();
            }

            String who = item.username.isEmpty() ? L10n.t(context, "Instant") : "@" + item.username;
            String type = item.isVideo ? L10n.t(context, "Video") : L10n.t(context, "Photo");
            String ago = item.takenAtMs != null ? DateUtils.getRelativeTimeSpanString(item.takenAtMs,
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() : "";
            String subtitle = ago.isEmpty() ? type : type + ", " + ago;

            LinearLayout words = (LinearLayout) row.getChildAt(1);
            ((TextView) words.getChildAt(0)).setText(who);
            ((TextView) words.getChildAt(1)).setText(subtitle);

            ImageView pic = (ImageView) row.getChildAt(0);
            showPicture(context, palette, pic, item, Dim.dp(PICTURE_DP), Dim.dp(12));
            return row;
        }

        private LinearLayout makeHeader() {
            LinearLayout row = new LinearLayout(context);
            row.setTag("header");
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(40));
            row.setPaddingRelative(Dim.dp(16), Dim.dp(6), Dim.dp(4), Dim.dp(6));
            int headerBg = Color.argb(180,
                    Color.red(palette.card),
                    Color.green(palette.card),
                    Color.blue(palette.card));
            row.setBackgroundColor(headerBg);

            TextView label = new TextView(context);
            label.setTextColor(palette.title);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setSingleLine(true);
            label.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            ImageButton menuBtn = new ImageButton(context);
            Drawable moreIcon = SettingsIcons.icon(context, SettingsIcons.MENU, palette.title);
            moreIcon.setBounds(0, 0, Dim.dp(20), Dim.dp(20));
            menuBtn.setImageDrawable(moreIcon);
            menuBtn.setPadding(Dim.dp(8), Dim.dp(8), Dim.dp(8), Dim.dp(8));
            menuBtn.setBackground(ripple(palette));
            menuBtn.setContentDescription(L10n.t(context, "Group options"));
            row.addView(menuBtn, new LinearLayout.LayoutParams(Dim.dp(40), Dim.dp(40)));

            return row;
        }

        private LinearLayout makeItem() {
            LinearLayout row = new LinearLayout(context);
            row.setTag("item");
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(Dim.dp(64));
            row.setPaddingRelative(Dim.dp(16), Dim.dp(8), Dim.dp(16), Dim.dp(8));
            row.setBackground(ripple(palette));

            ImageView picture = new ImageView(context);
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable well = new GradientDrawable();
            well.setColor(palette.card);
            well.setCornerRadius(Dim.dp(8));
            picture.setBackground(well);
            picture.setClipToOutline(true);
            LinearLayout.LayoutParams pictureSpace = new LinearLayout.LayoutParams(Dim.dp(PICTURE_DP), Dim.dp(PICTURE_DP));
            pictureSpace.setMarginEnd(Dim.dp(12));
            row.addView(picture, pictureSpace);

            LinearLayout words = new LinearLayout(context);
            words.setOrientation(LinearLayout.VERTICAL);
            TextView who = new TextView(context);
            who.setTextColor(palette.title);
            who.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            who.setSingleLine(true);
            who.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(who);

            TextView what = new TextView(context);
            what.setTextColor(palette.summary);
            what.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            what.setSingleLine(true);
            what.setEllipsize(TextUtils.TruncateAt.END);
            words.addView(what);

            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }
    }

    static final class Tiles extends BaseAdapter {
        private final Context context;
        private final ScreenColors palette;
        private final int size;
        private List<Item> shown = Collections.emptyList();

        Tiles(Context context, ScreenColors palette, int size) {
            this.context = context;
            this.palette = palette;
            this.size = size;
        }

        void set(List<Item> items) {
            shown = items;
            notifyDataSetChanged();
        }

        Item get(int position) {
            return shown.get(position);
        }

        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int position) { return shown.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View reused, ViewGroup parent) {
            Tile tile = reused instanceof Tile ? (Tile) reused : make();
            Item item = shown.get(position);
            String who = item.username.isEmpty() ? L10n.t(context, "Instant") : "@" + item.username;
            caption(tile).setText(who);
            tile.setContentDescription(who);

            ImageView badge = badge(tile);
            if (item.isVideo) {
                badge.setVisibility(View.VISIBLE);
                badge.setImageDrawable(SettingsIcons.icon(context, SettingsIcons.REELS, Color.WHITE));
            } else {
                badge.setVisibility(View.GONE);
            }

            showPicture(context, palette, picture(tile), item, size, size / 3);
            return tile;
        }

        static ImageView picture(FrameLayout tile) {
            return (ImageView) tile.getChildAt(0);
        }

        static TextView caption(FrameLayout tile) {
            return (TextView) tile.getChildAt(1);
        }

        static ImageView badge(FrameLayout tile) {
            return (ImageView) tile.getChildAt(2);
        }

        private Tile make() {
            Tile tile = new Tile(context);
            tile.setBackgroundColor(palette.card);
            tile.setForeground(ripple(palette));

            ImageView picture = new ImageView(context);
            picture.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            tile.addView(picture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView caption = new TextView(context);
            caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            caption.setTextColor(Color.WHITE);
            caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            caption.setSingleLine(true);
            caption.setEllipsize(TextUtils.TruncateAt.END);
            caption.setPaddingRelative(Dim.dp(6), Dim.dp(14), Dim.dp(6), Dim.dp(5));
            caption.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    new int[]{0x99000000, 0x00000000}));
            tile.addView(caption, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

            ImageView badge = new ImageView(context);
            badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(0x66000000);
            badge.setBackground(dot);
            badge.setPadding(Dim.dp(4), Dim.dp(4), Dim.dp(4), Dim.dp(4));
            FrameLayout.LayoutParams corner = new FrameLayout.LayoutParams(Dim.dp(24), Dim.dp(24), Gravity.TOP | Gravity.END);
            corner.setMargins(Dim.dp(6), Dim.dp(6), Dim.dp(6), Dim.dp(6));
            tile.addView(badge, corner);
            return tile;
        }
    }

    static final class Tile extends FrameLayout {
        Tile(Context context) { super(context); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, widthSpec);
        }
    }
}

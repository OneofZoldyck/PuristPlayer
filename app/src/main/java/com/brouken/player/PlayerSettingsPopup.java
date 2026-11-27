package com.brouken.player;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.TrackSelectionDialogBuilder;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Purist: replaces Media3's private gear popup (which can't be extended) with an identical-looking popup that
 * keeps "Playback speed" and "Audio track" (delegated to Media3) and adds Purist entries.
 */
public final class PlayerSettingsPopup {

    /** Media3 PlayerControlView settings positions (private constants). */
    private static final int MEDIA3_POSITION_SPEED = 0;
    private static final int MEDIA3_POSITION_AUDIO = 1;
    private static final float[] FALLBACK_SPEEDS = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};

    public interface Actions {
        void onRecheckVideoFormat();
        /** Null = subtitle settings entry hidden. */
        @Nullable Runnable subtitleSettingsAction();
        void onPopupShown();
        void onPopupDismissed();
    }

    private static final class Row {
        final int icon;
        final String title;
        @Nullable final String subtitle;
        final Runnable action;

        Row(int icon, String title, @Nullable String subtitle, Runnable action) {
            this.icon = icon;
            this.title = title;
            this.subtitle = subtitle;
            this.action = action;
        }
    }

    private final PlayerActivity activity;
    private final PlayerControlView controlView;
    private final View anchor;
    private final Actions actions;
    @Nullable private PopupWindow popup;
    @Nullable private Method media3SettingClick;

    public PlayerSettingsPopup(PlayerActivity activity, PlayerControlView controlView, View anchor, Actions actions) {
        this.activity = activity;
        this.controlView = controlView;
        this.anchor = anchor;
        this.actions = actions;
        try {
            media3SettingClick = PlayerControlView.class.getDeclaredMethod("onSettingViewClicked", int.class);
            media3SettingClick.setAccessible(true);
        } catch (Exception e) {
            media3SettingClick = null;
        }
    }

    public void show() {
        Player player = PlayerActivity.player;
        if (player == null) return;

        List<Row> rows = new ArrayList<>();
        rows.add(new Row(R.drawable.exo_ic_speed, activity.getString(R.string.exo_controls_playback_speed),
                speedLabel(player.getPlaybackParameters()), () -> openMedia3(MEDIA3_POSITION_SPEED)));
        if (player.getCurrentTracks().containsType(C.TRACK_TYPE_AUDIO)) {
            rows.add(new Row(R.drawable.exo_ic_audiotrack, activity.getString(R.string.exo_track_selection_title_audio),
                    audioLabel(player), () -> openMedia3(MEDIA3_POSITION_AUDIO)));
        }
        rows.add(new Row(R.drawable.ic_hdr_check_24dp, activity.getString(R.string.purist_recheck_format), null,
                actions::onRecheckVideoFormat));
        Runnable subtitleSettings = actions.subtitleSettingsAction();
        if (subtitleSettings != null) {
            rows.add(new Row(R.drawable.exo_ic_subtitle_on, activity.getString(R.string.purist_subtitle_settings), null, subtitleSettings));
        }

        RecyclerView list = (RecyclerView) LayoutInflater.from(activity).inflate(R.layout.exo_styled_settings_list, null);
        list.setLayoutManager(new LinearLayoutManager(activity));
        list.setAdapter(new Adapter(rows));

        PopupWindow window = new PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setOutsideTouchable(true);
        window.setOnDismissListener(() -> {
            if (popup == window) popup = null;
            actions.onPopupDismissed();
        });
        popup = window;

        // Measure and place the popup above the gear button, right-aligned with it and kept on screen.
        View root = activity.playerView;
        int margin = Utils.dpToPx(8);
        int[] anchorLoc = new int[2];
        anchor.getLocationInWindow(anchorLoc);
        int[] rootLoc = new int[2];
        root.getLocationInWindow(rootLoc);
        int maxWidth = Math.max(Utils.dpToPx(200), root.getWidth() - 2 * margin);
        int availableAbove = anchorLoc[1] - rootLoc[1] - 2 * margin;
        list.measure(View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int width = list.getMeasuredWidth();
        int height = Math.min(list.getMeasuredHeight(), Math.max(Utils.dpToPx(96), availableAbove));
        window.setWidth(width);
        window.setHeight(height);
        int x = Math.max(rootLoc[0] + margin, Math.min(anchorLoc[0] + anchor.getWidth() - width, rootLoc[0] + root.getWidth() - width - margin));
        int y = Math.max(rootLoc[1] + margin, anchorLoc[1] - height - margin);
        actions.onPopupShown();
        window.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
    }

    public void dismiss() {
        if (popup != null) popup.dismiss();
    }

    private void openMedia3(int position) {
        dismiss();
        if (media3SettingClick != null) {
            try {
                media3SettingClick.invoke(controlView, position);
                return;
            } catch (Exception ignored) {
                // fall through to the plain fallback dialogs
            }
        }
        Player player = PlayerActivity.player;
        if (player == null) return;
        if (position == MEDIA3_POSITION_SPEED) {
            String[] labels = new String[FALLBACK_SPEEDS.length];
            int checked = -1;
            for (int i = 0; i < FALLBACK_SPEEDS.length; i++) {
                labels[i] = speedLabel(new PlaybackParameters(FALLBACK_SPEEDS[i]));
                if (Math.abs(player.getPlaybackParameters().speed - FALLBACK_SPEEDS[i]) < 0.01f) checked = i;
            }
            new AlertDialog.Builder(activity)
                    .setTitle(R.string.exo_controls_playback_speed)
                    .setSingleChoiceItems(labels, checked, (d, which) -> {
                        player.setPlaybackSpeed(FALLBACK_SPEEDS[which]);
                        d.dismiss();
                    })
                    .show();
        } else {
            new TrackSelectionDialogBuilder(activity, activity.getString(R.string.exo_track_selection_title_audio),
                    player, C.TRACK_TYPE_AUDIO).build().show();
        }
    }

    private String speedLabel(PlaybackParameters p) {
        if (Math.abs(p.speed - 1f) < 0.001f) return activity.getString(R.string.purist_speed_normal);
        String s = String.format(Locale.US, "%.2f", p.speed).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + "x";
    }

    @Nullable
    private String audioLabel(Player player) {
        Format f = player instanceof androidx.media3.exoplayer.ExoPlayer ? ((androidx.media3.exoplayer.ExoPlayer) player).getAudioFormat() : null;
        if (f == null) return null;
        try {
            return new CustomDefaultTrackNameProvider(activity.getResources()).getTrackName(f);
        } catch (Exception e) {
            return null;
        }
    }

    private final class Adapter extends RecyclerView.Adapter<Adapter.Holder> {
        private final List<Row> rows;

        Adapter(List<Row> rows) {
            this.rows = rows;
        }

        final class Holder extends RecyclerView.ViewHolder {
            final ImageView icon;
            final TextView main;
            final TextView sub;

            Holder(View v) {
                super(v);
                icon = v.findViewById(R.id.exo_icon);
                main = v.findViewById(R.id.exo_main_text);
                sub = v.findViewById(R.id.exo_sub_text);
            }
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.exo_styled_settings_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Row row = rows.get(position);
            h.icon.setImageResource(row.icon);
            h.main.setText(row.title);
            if (row.subtitle != null) {
                h.sub.setText(row.subtitle);
                h.sub.setVisibility(View.VISIBLE);
            } else {
                h.sub.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> {
                if (row.action != null) {
                    // Media3 sub-menus dismiss this popup themselves; other actions dismiss it here.
                    dismiss();
                    row.action.run();
                }
            });
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}

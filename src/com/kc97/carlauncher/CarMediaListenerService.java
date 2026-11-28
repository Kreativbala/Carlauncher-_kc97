package com.kc97.carlauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.view.KeyEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CarMediaListenerService extends NotificationListenerService {
    public static final String PKG_SPOTIFY = "com.spotify.music";
    public static final String PKG_YANDEX = "ru.yandex.music";

    public static class TrackInfo {
        public String packageName;
        public String title;
        public String artist;
        public Bitmap artwork;
        public boolean playing;
        public boolean hasSession;
    }

    public interface MediaUpdateListener {
        void onMediaUpdated();
    }

    private static volatile CarMediaListenerService instance;
    private static volatile MediaUpdateListener uiListener;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MediaSessionManager sessionManager;
    private final Map<String, MediaController> controllers = new HashMap<>();
    private final Map<String, MediaController.Callback> callbacks = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            this::updateSessions;

    public static void setUiListener(MediaUpdateListener listener) {
        uiListener = listener;
        if (instance != null) {
            instance.refreshSessions();
        }
    }

    public static boolean isConnected() {
        return instance != null;
    }

    public static TrackInfo getTrackInfo(String targetPkg) {
        TrackInfo info = new TrackInfo();
        info.packageName = targetPkg;
        info.hasSession = false;
        CarMediaListenerService svc = instance;
        if (svc == null) return info;

        MediaController ctrl = svc.findController(targetPkg);
        if (ctrl == null) return info;

        info.hasSession = true;
        MediaMetadata meta = ctrl.getMetadata();
        if (meta != null) {
            info.title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            info.artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (info.artist == null || info.artist.isEmpty()) {
                info.artist = meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            }
            info.artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (info.artwork == null) {
                info.artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            }
        }
        PlaybackState state = ctrl.getPlaybackState();
        info.playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        return info;
    }

    public static void togglePlayPause(Context ctx, String targetPkg) {
        CarMediaListenerService svc = instance;
        MediaController ctrl = svc != null ? svc.findController(targetPkg) : null;
        if (ctrl != null) {
            PlaybackState st = ctrl.getPlaybackState();
            if (st != null && st.getState() == PlaybackState.STATE_PLAYING) {
                ctrl.getTransportControls().pause();
            } else {
                ctrl.getTransportControls().play();
            }
            return;
        }
        // Fallback when NotificationListener is temporarily unbound: toggle active playback via system media key!
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null && am.isMusicActive()) {
                dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return;
            }
        } catch (Throwable ignored) {}
        // Launch app if nothing is playing yet
        try {
            Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(targetPkg);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        } catch (Throwable ignored) {}
    }

    public static void skipNext(Context ctx, String targetPkg) {
        CarMediaListenerService svc = instance;
        MediaController ctrl = svc != null ? svc.findController(targetPkg) : null;
        if (ctrl != null) {
            ctrl.getTransportControls().skipToNext();
        } else {
            dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
        }
    }

    public static void skipPrevious(Context ctx, String targetPkg) {
        CarMediaListenerService svc = instance;
        MediaController ctrl = svc != null ? svc.findController(targetPkg) : null;
        if (ctrl != null) {
            ctrl.getTransportControls().skipToPrevious();
        } else {
            dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        }
    }

    private static void dispatchMediaKey(Context ctx, int keyCode) {
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
                am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
            }
        } catch (Throwable ignored) {}
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessionManager != null) {
            try {
                ComponentName cn = new ComponentName(this, CarMediaListenerService.class);
                sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, cn);
                updateSessions(sessionManager.getActiveSessions(cn));
            } catch (Throwable ignored) {}
        }
        notifyUi();
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        cleanup();
        instance = null;
        notifyUi();
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void refreshSessions() {
        if (sessionManager == null) return;
        try {
            ComponentName cn = new ComponentName(this, CarMediaListenerService.class);
            updateSessions(sessionManager.getActiveSessions(cn));
        } catch (Throwable ignored) {}
    }

    private synchronized void cleanup() {
        if (sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
            } catch (Throwable ignored) {}
        }
        for (Map.Entry<String, MediaController> e : controllers.entrySet()) {
            MediaController.Callback cb = callbacks.get(e.getKey());
            if (cb != null) {
                try { e.getValue().unregisterCallback(cb); } catch (Throwable ignored) {}
            }
        }
        controllers.clear();
        callbacks.clear();
    }

    private synchronized void updateSessions(List<MediaController> list) {
        for (Map.Entry<String, MediaController> e : controllers.entrySet()) {
            MediaController.Callback cb = callbacks.get(e.getKey());
            if (cb != null) {
                try { e.getValue().unregisterCallback(cb); } catch (Throwable ignored) {}
            }
        }
        controllers.clear();
        callbacks.clear();

        if (list != null) {
            for (MediaController ctrl : list) {
                String pkg = ctrl.getPackageName();
                if (pkg == null) continue;
                if (!controllers.containsKey(pkg)) {
                    MediaController.Callback cb = new MediaController.Callback() {
                        @Override
                        public void onPlaybackStateChanged(PlaybackState state) {
                            notifyUi();
                        }

                        @Override
                        public void onMetadataChanged(MediaMetadata metadata) {
                            notifyUi();
                        }
                    };
                    ctrl.registerCallback(cb, mainHandler);
                    controllers.put(pkg, ctrl);
                    callbacks.put(pkg, cb);
                }
            }
        }
        notifyUi();
    }

    private synchronized MediaController findController(String targetPkg) {
        return controllers.get(targetPkg);
    }

    private static void notifyUi() {
        MediaUpdateListener l = uiListener;
        if (l != null) {
            mainHandler.post(l::onMediaUpdated);
        }
    }
}

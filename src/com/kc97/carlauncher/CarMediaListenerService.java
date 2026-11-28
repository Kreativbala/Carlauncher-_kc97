package com.kc97.carlauncher;

import android.app.Notification;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class CarMediaListenerService extends NotificationListenerService {
    private static final String TAG = "KC97_MEDIA";
    private static final String PREFS_MEDIA = "kc97_media_cache";

    public static final String PKG_SPOTIFY = "com.spotify.music";
    public static final String PKG_YANDEX = "ru.yandex.music";

    public static class TrackInfo {
        public String packageName;
        public String title;
        public String artist;
        public String queueTitle;
        public String nextTrackTitle;
        public int queueSize;
        public Bitmap artwork;
        public boolean playing;
        public boolean hasSession;
        public boolean fromSavedCache;
    }

    public static class QueueEntry {
        public long queueId;
        public String title;
        public String artist;
        public boolean isCurrent;
        public boolean canSkipTo;
    }

    public interface MediaUpdateListener {
        void onMediaUpdated();
    }

    private static volatile CarMediaListenerService instance;
    private static volatile MediaUpdateListener uiListener;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static long lastSelfHealMs = 0L;
    private static volatile boolean rebindCycleInProgress = false;
    private static volatile boolean rootSelfHealTried = false;
    private static volatile long lastDumpsysPollMs = 0L;
    private static volatile boolean dumpsysPollRunning = false;
    private static volatile String activeMediaButtonPkg = null;

    // Fallback broadcast/notification/dumpsys cache + rolling track history per player
    private static final Map<String, TrackInfo> fallbackCache = new HashMap<>();
    private static final Map<String, LinkedList<String>> recentHistory = new HashMap<>();

    private MediaSessionManager sessionManager;
    private final Map<String, MediaController> controllers = new HashMap<>();
    private final Map<String, MediaController.Callback> callbacks = new HashMap<>();

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener =
            list -> refreshAllSources();

    public static void setUiListener(MediaUpdateListener listener) {
        uiListener = listener;
        if (instance != null) {
            instance.refreshAllSources();
        }
    }

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * Ensures CarMediaListenerService is enabled in Settings.Secure (auto-granting via WRITE_SECURE_SETTINGS
     * if missing) and actively bound across every head-unit reboot without ever nagging the user.
     */
    public static void ensurePermissionAndBind(Context ctx) {
        if (ctx == null) return;
        final Context appCtx = ctx.getApplicationContext();
        try {
            // Start service from our own UID first so process attachment is always allowed
            appCtx.startService(new Intent(appCtx, CarMediaListenerService.class));
        } catch (Throwable ignored) {}

        try {
            ComponentName cn = new ComponentName(appCtx, CarMediaListenerService.class);
            String flat = cn.flattenToString();
            ContentResolver cr = appCtx.getContentResolver();
            boolean hasUs = false;
            if (!rebindCycleInProgress) {
                String enabled = Settings.Secure.getString(cr, "enabled_notification_listeners");
                hasUs = enabled != null && enabled.contains(flat);

                if (!hasUs) {
                    String base = removeComponentFromColonList(enabled != null ? enabled : "", flat);
                    String updated = base.isEmpty() ? flat : (base + ":" + flat);
                    boolean wrote = Settings.Secure.putString(cr, "enabled_notification_listeners", updated);
                    Log.i(TAG, "Auto-enabled CarMediaListenerService in Settings.Secure: wrote=" + wrote);
                    hasUs = wrote;
                } else if (enabled.indexOf(flat) != enabled.lastIndexOf(flat)) {
                    // Clean up any duplicate component entries
                    String cleanBase = removeComponentFromColonList(enabled, flat);
                    String deduped = cleanBase.isEmpty() ? flat : (cleanBase + ":" + flat);
                    Settings.Secure.putString(cr, "enabled_notification_listeners", deduped);
                }
            }

            if (instance == null) {
                try {
                    NotificationListenerService.requestRebind(cn);
                } catch (Throwable ignored) {}

                long now = SystemClock.elapsedRealtime();
                if (hasUs && !rebindCycleInProgress && (lastSelfHealMs == 0L || (now - lastSelfHealMs) > 35_000L)) {
                    lastSelfHealMs = now;
                    // Force NotificationManagerService to drop any stale DEAD ConnectionRecord and rebind fresh
                    mainHandler.postDelayed(() -> {
                        if (instance != null || rebindCycleInProgress) return;
                        try {
                            String cur = Settings.Secure.getString(cr, "enabled_notification_listeners");
                            if (cur != null && cur.contains(flat)) {
                                rebindCycleInProgress = true;
                                String without = removeComponentFromColonList(cur, flat);
                                if (Settings.Secure.putString(cr, "enabled_notification_listeners", without)) {
                                    mainHandler.postDelayed(() -> {
                                        try {
                                            String cur2 = Settings.Secure.getString(cr, "enabled_notification_listeners");
                                            String clean = removeComponentFromColonList(cur2 != null ? cur2 : "", flat);
                                            String restored = clean.isEmpty() ? flat : (clean + ":" + flat);
                                            Settings.Secure.putString(cr, "enabled_notification_listeners", restored);
                                            NotificationListenerService.requestRebind(cn);
                                            Log.i(TAG, "Completed Settings.Secure listener rebind cycle");
                                        } catch (Throwable ignored) {
                                        } finally {
                                            rebindCycleInProgress = false;
                                        }
                                    }, 450L);
                                } else {
                                    rebindCycleInProgress = false;
                                }
                            }
                        } catch (Throwable ignored) {
                            rebindCycleInProgress = false;
                        }
                    }, 1800L);
                }

                if (!rootSelfHealTried) {
                    rootSelfHealTried = true;
                    mainHandler.postDelayed(() -> {
                        if (instance != null) return;
                        new Thread(() -> {
                            try {
                                String cmd = "pm grant com.kc97.carlauncher android.permission.WRITE_SECURE_SETTINGS 2>/dev/null; "
                                        + "pm disable " + flat + " >/dev/null 2>&1; "
                                        + "pm enable " + flat + " >/dev/null 2>&1";
                                Process p = Runtime.getRuntime().exec(new String[]{"su", "0", "sh", "-c", cmd});
                                p.waitFor();
                                NotificationListenerService.requestRebind(cn);
                            } catch (Throwable ignored) {}
                        }, "Media-RootRebind").start();
                    }, 4000L);
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "ensurePermissionAndBind note: " + e.getMessage());
        }
    }

    private static String removeComponentFromColonList(String list, String targetFlat) {
        String[] parts = list.split(":");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            String t = p.trim();
            if (t.isEmpty() || t.equals(targetFlat) || t.startsWith("com.kc97.carlauncher/")) continue;
            if (sb.length() > 0) sb.append(':');
            sb.append(t);
        }
        return sb.toString();
    }

    /**
     * Passive broadcast metadata receiver hook (Spotify Device Broadcast Status / Android Music metachanged).
     */
    public static void onBroadcastMetadata(Context ctx, String pkg, String title, String artist, Boolean playing) {
        if (pkg == null || pkg.isEmpty()) return;
        synchronized (fallbackCache) {
            TrackInfo t = fallbackCache.get(pkg);
            if (t == null) {
                t = new TrackInfo();
                t.packageName = pkg;
                fallbackCache.put(pkg, t);
            }
            if (title != null && !title.trim().isEmpty()) {
                t.title = title.trim();
                t.hasSession = true;
            }
            if (artist != null && !artist.trim().isEmpty()) {
                t.artist = artist.trim();
            }
            if (playing != null) {
                t.playing = playing;
            }
            if (t.title != null && !t.title.isEmpty()) {
                recordHistoryLocked(pkg, t.title, t.artist);
                persistLastTrack(ctx, pkg, t.title, t.artist);
            }
        }
        notifyUi();
    }

    private static void recordHistoryLocked(String pkg, String title, String artist) {
        if (title == null || title.trim().isEmpty()) return;
        String entry = (artist != null && !artist.trim().isEmpty())
                ? (title.trim() + " — " + artist.trim())
                : title.trim();
        LinkedList<String> list = recentHistory.get(pkg);
        if (list == null) {
            list = new LinkedList<>();
            recentHistory.put(pkg, list);
        }
        list.remove(entry);
        list.addFirst(entry);
        while (list.size() > 25) list.removeLast();
    }

    private static void persistLastTrack(Context ctx, String pkg, String title, String artist) {
        if (ctx == null || pkg == null || title == null || title.isEmpty()) return;
        try {
            SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_MEDIA, Context.MODE_PRIVATE);
            sp.edit()
                    .putString("title_" + pkg, title)
                    .putString("artist_" + pkg, artist != null ? artist : "")
                    .apply();
        } catch (Throwable ignored) {}
    }

    public static TrackInfo getTrackInfo(Context ctx, String targetPkg) {
        TrackInfo info = new TrackInfo();
        info.packageName = targetPkg;
        info.hasSession = false;

        CarMediaListenerService svc = instance;
        if (svc != null) {
            MediaController ctrl = svc.findController(targetPkg);
            if (ctrl != null) {
                MediaMetadata meta = ctrl.getMetadata();
                PlaybackState state = ctrl.getPlaybackState();
                info.playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (meta != null) {
                    info.title = firstNonEmpty(
                            meta.getString(MediaMetadata.METADATA_KEY_TITLE),
                            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                    info.artist = firstNonEmpty(
                            meta.getString(MediaMetadata.METADATA_KEY_ARTIST),
                            meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                            meta.getString(MediaMetadata.METADATA_KEY_ALBUM));
                    info.artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                    if (info.artwork == null) {
                        info.artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
                    }
                    if (info.artwork == null) {
                        info.artwork = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                    }
                }
                CharSequence qt = ctrl.getQueueTitle();
                if (qt != null && qt.length() > 0) {
                    info.queueTitle = qt.toString();
                }
                try {
                    List<MediaSession.QueueItem> q = ctrl.getQueue();
                    if (q != null && !q.isEmpty()) {
                        info.queueSize = q.size();
                        long activeId = state != null ? state.getActiveQueueItemId() : MediaSession.QueueItem.UNKNOWN_ID;
                        for (int i = 0; i < q.size(); i++) {
                            MediaSession.QueueItem item = q.get(i);
                            if (item == null || item.getDescription() == null) continue;
                            boolean match = (activeId != MediaSession.QueueItem.UNKNOWN_ID && item.getQueueId() == activeId)
                                    || (info.title != null && info.title.equals(String.valueOf(item.getDescription().getTitle())));
                            if (match && i + 1 < q.size()) {
                                MediaDescription nd = q.get(i + 1).getDescription();
                                if (nd != null && nd.getTitle() != null) {
                                    info.nextTrackTitle = nd.getTitle().toString();
                                }
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {}

                if (info.title != null && !info.title.isEmpty()) {
                    info.hasSession = true;
                    synchronized (fallbackCache) {
                        recordHistoryLocked(targetPkg, info.title, info.artist);
                    }
                    persistLastTrack(ctx, targetPkg, info.title, info.artist);
                    return info;
                }
            }
        }

        // When NotificationListenerService is not yet bound, seamlessly poll dumpsys media_session via built-in su 0
        if (svc == null) {
            pollDumpsysMediaSessionAsync(ctx);
        }

        // Check live notification / broadcast / dumpsys fallback cache
        synchronized (fallbackCache) {
            TrackInfo fb = fallbackCache.get(targetPkg);
            if (fb != null && fb.title != null && !fb.title.isEmpty()) {
                info.hasSession = true;
                info.title = fb.title;
                info.artist = fb.artist;
                info.artwork = fb.artwork;
                info.playing = fb.playing;
                info.queueTitle = fb.queueTitle;
                info.queueSize = fb.queueSize;
                return info;
            }
        }

        // Check persistent last-played track across car restarts so widget is never blank!
        if (ctx != null) {
            try {
                SharedPreferences sp = ctx.getApplicationContext().getSharedPreferences(PREFS_MEDIA, Context.MODE_PRIVATE);
                String savedTitle = sp.getString("title_" + targetPkg, null);
                if (savedTitle != null && !savedTitle.isEmpty()) {
                    info.hasSession = true;
                    info.fromSavedCache = true;
                    info.title = savedTitle;
                    info.artist = sp.getString("artist_" + targetPkg, "");
                    info.playing = false;
                }
            } catch (Throwable ignored) {}
        }
        return info;
    }

    /**
     * Returns the live playlist/queue for Yandex Music or Spotify (from MediaController.getQueue()),
     * falling back to recent session track history if the player is in radio/wave mode without QueueItems.
     */
    public static List<QueueEntry> getPlaylist(Context ctx, String targetPkg) {
        List<QueueEntry> out = new ArrayList<>();
        CarMediaListenerService svc = instance;
        String currentTitle = null;
        long activeQueueId = MediaSession.QueueItem.UNKNOWN_ID;

        if (svc != null) {
            MediaController ctrl = svc.findController(targetPkg);
            if (ctrl != null) {
                MediaMetadata meta = ctrl.getMetadata();
                if (meta != null) {
                    currentTitle = firstNonEmpty(
                            meta.getString(MediaMetadata.METADATA_KEY_TITLE),
                            meta.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                }
                PlaybackState st = ctrl.getPlaybackState();
                if (st != null) {
                    activeQueueId = st.getActiveQueueItemId();
                }
                try {
                    List<MediaSession.QueueItem> q = ctrl.getQueue();
                    if (q != null && !q.isEmpty()) {
                        int limit = Math.min(q.size(), 60);
                        for (int i = 0; i < limit; i++) {
                            MediaSession.QueueItem qi = q.get(i);
                            if (qi == null || qi.getDescription() == null) continue;
                            MediaDescription d = qi.getDescription();
                            CharSequence t = d.getTitle();
                            if (t == null || t.length() == 0) continue;
                            QueueEntry e = new QueueEntry();
                            e.queueId = qi.getQueueId();
                            e.title = t.toString();
                            e.artist = d.getSubtitle() != null ? d.getSubtitle().toString() : "";
                            e.canSkipTo = true;
                            e.isCurrent = (activeQueueId != MediaSession.QueueItem.UNKNOWN_ID && qi.getQueueId() == activeQueueId)
                                    || (currentTitle != null && currentTitle.equalsIgnoreCase(e.title));
                            out.add(e);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (out.isEmpty()) {
            TrackInfo cur = getTrackInfo(ctx, targetPkg);
            synchronized (fallbackCache) {
                LinkedList<String> hist = recentHistory.get(targetPkg);
                if (hist != null) {
                    for (String h : hist) {
                        QueueEntry e = new QueueEntry();
                        e.queueId = -1L;
                        e.title = h;
                        e.artist = "";
                        e.canSkipTo = false;
                        e.isCurrent = cur.title != null && h.startsWith(cur.title);
                        out.add(e);
                    }
                }
            }
            if (out.isEmpty() && cur.title != null && !cur.title.isEmpty()) {
                QueueEntry e = new QueueEntry();
                e.queueId = -1L;
                e.title = cur.title;
                e.artist = cur.artist != null ? cur.artist : "";
                e.canSkipTo = false;
                e.isCurrent = true;
                out.add(e);
            }
        }
        return out;
    }

    public static boolean skipToQueueItem(String targetPkg, long queueId) {
        CarMediaListenerService svc = instance;
        MediaController ctrl = svc != null ? svc.findController(targetPkg) : null;
        if (ctrl != null && queueId != -1L) {
            try {
                ctrl.getTransportControls().skipToQueueItem(queueId);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
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
        boolean hasLiveFallback = false;
        synchronized (fallbackCache) {
            TrackInfo fb = fallbackCache.get(targetPkg);
            if (fb != null && fb.hasSession && fb.title != null && !fb.title.isEmpty()) {
                hasLiveFallback = true;
            }
        }
        try {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            boolean ownsMediaBtn = targetPkg != null && targetPkg.equals(activeMediaButtonPkg);
            if (ownsMediaBtn || (am != null && am.isMusicActive())) {
                dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return;
            }
            if (hasLiveFallback && sendTargetedMediaButton(ctx, targetPkg, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
                return;
            }
        } catch (Throwable ignored) {}
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
            if (targetPkg == null || targetPkg.equals(activeMediaButtonPkg)) {
                dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_NEXT);
            } else {
                sendTargetedMediaButton(ctx, targetPkg, KeyEvent.KEYCODE_MEDIA_NEXT);
            }
        }
    }

    public static void skipPrevious(Context ctx, String targetPkg) {
        CarMediaListenerService svc = instance;
        MediaController ctrl = svc != null ? svc.findController(targetPkg) : null;
        if (ctrl != null) {
            ctrl.getTransportControls().skipToPrevious();
        } else {
            if (targetPkg == null || targetPkg.equals(activeMediaButtonPkg)) {
                dispatchMediaKey(ctx, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            } else {
                sendTargetedMediaButton(ctx, targetPkg, KeyEvent.KEYCODE_MEDIA_PREVIOUS);
            }
        }
    }

    private static boolean sendTargetedMediaButton(Context ctx, String targetPkg, int keyCode) {
        if (ctx == null || targetPkg == null) return false;
        try {
            long now = SystemClock.uptimeMillis();
            Intent down = new Intent(Intent.ACTION_MEDIA_BUTTON);
            down.setPackage(targetPkg);
            down.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            ctx.sendBroadcast(down);

            Intent up = new Intent(Intent.ACTION_MEDIA_BUTTON);
            up.setPackage(targetPkg);
            up.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            ctx.sendBroadcast(up);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Zero-permission root MediaSession fallback: when NotificationListenerService is not yet bound on KTE firmware,
     * reads active Yandex Music & Spotify sessions directly from dumpsys media_session every 2.5s.
     */
    private static void pollDumpsysMediaSessionAsync(final Context ctx) {
        if (instance != null || dumpsysPollRunning) return;
        long now = SystemClock.elapsedRealtime();
        if (lastDumpsysPollMs != 0L && (now - lastDumpsysPollMs) < 2500L) return;
        lastDumpsysPollMs = now;
        dumpsysPollRunning = true;

        final Context appCtx = ctx != null ? ctx.getApplicationContext() : null;
        new Thread(() -> {
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"su", "0", "dumpsys", "media_session"});
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
                String line;
                String curPkg = null;
                boolean curPlaying = false;
                String curTitle = null;
                String curArtist = null;
                String curQueueTitle = null;
                int curQueueSize = 0;
                boolean changed = false;

                while ((line = br.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("Media button session is ")) {
                        String rest = trimmed.substring("Media button session is ".length()).trim();
                        int slash = rest.indexOf('/');
                        if (slash > 0) {
                            activeMediaButtonPkg = rest.substring(0, slash).trim();
                        }
                        continue;
                    }
                    if (trimmed.startsWith("package=")) {
                        changed |= commitParsedSession(appCtx, curPkg, curTitle, curArtist, curPlaying, curQueueTitle, curQueueSize);
                        curPkg = trimmed.substring("package=".length()).trim();
                        curPlaying = false;
                        curTitle = null;
                        curArtist = null;
                        curQueueTitle = null;
                        curQueueSize = 0;
                    } else if (curPkg != null && trimmed.startsWith("state=PlaybackState")) {
                        curPlaying = trimmed.contains("{state=3,") || trimmed.contains("{state=3}");
                    } else if (curPkg != null && trimmed.startsWith("metadata:") && trimmed.contains("description=")) {
                        int idx = trimmed.indexOf("description=");
                        String desc = trimmed.substring(idx + "description=".length()).trim();
                        String[] parts = desc.split(", ", 3);
                        if (parts.length >= 1 && !parts[0].equals("null") && !parts[0].isEmpty()) {
                            curTitle = parts[0].trim();
                        }
                        if (parts.length >= 2 && !parts[1].equals("null") && !parts[1].isEmpty()) {
                            curArtist = parts[1].trim();
                        }
                    } else if (curPkg != null && trimmed.startsWith("queueTitle=")) {
                        int szIdx = trimmed.lastIndexOf(", size=");
                        if (szIdx > 0) {
                            String qt = trimmed.substring("queueTitle=".length(), szIdx).trim();
                            if (!qt.equals("null") && !qt.isEmpty()) curQueueTitle = qt;
                            try {
                                curQueueSize = Integer.parseInt(trimmed.substring(szIdx + 7).trim());
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                changed |= commitParsedSession(appCtx, curPkg, curTitle, curArtist, curPlaying, curQueueTitle, curQueueSize);
                p.waitFor();
                if (changed) notifyUi();
            } catch (Throwable ignored) {
            } finally {
                if (p != null) {
                    try { p.destroy(); } catch (Throwable ignored) {}
                }
                dumpsysPollRunning = false;
            }
        }, "Media-DumpsysPoll").start();
    }

    private static boolean commitParsedSession(Context appCtx, String pkg, String title, String artist,
                                               boolean playing, String queueTitle, int queueSize) {
        if (pkg == null || (!PKG_YANDEX.equals(pkg) && !PKG_SPOTIFY.equals(pkg))) return false;
        if (title == null || title.isEmpty()) return false;
        synchronized (fallbackCache) {
            TrackInfo t = fallbackCache.get(pkg);
            if (t == null) {
                t = new TrackInfo();
                t.packageName = pkg;
                fallbackCache.put(pkg, t);
            }
            boolean diff = !title.equals(t.title)
                    || (artist != null && !artist.equals(t.artist))
                    || (t.playing != playing)
                    || (t.queueSize != queueSize);
            t.title = title;
            t.artist = artist != null ? artist : "";
            t.playing = playing;
            t.queueTitle = queueTitle;
            t.queueSize = queueSize;
            t.hasSession = true;
            if (diff) {
                recordHistoryLocked(pkg, t.title, t.artist);
                persistLastTrack(appCtx, pkg, t.title, t.artist);
            }
            return diff;
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
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (instance != null) {
            refreshAllSources();
        }
        return START_STICKY;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        Log.i(TAG, "CarMediaListenerService CONNECTED!");
        sessionManager = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
        if (sessionManager != null) {
            try {
                ComponentName cn = new ComponentName(this, CarMediaListenerService.class);
                sessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, cn);
            } catch (Throwable ignored) {}
        }
        refreshAllSources();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (PKG_YANDEX.equals(pkg) || PKG_SPOTIFY.equals(pkg)) {
            inspectMediaNotification(sbn);
            refreshAllSources();
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        if (sbn == null) return;
        String pkg = sbn.getPackageName();
        if (PKG_YANDEX.equals(pkg) || PKG_SPOTIFY.equals(pkg)) {
            refreshAllSources();
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        Log.w(TAG, "CarMediaListenerService DISCONNECTED -> scheduling rebind");
        cleanup();
        instance = null;
        notifyUi();
        try {
            NotificationListenerService.requestRebind(new ComponentName(this, CarMediaListenerService.class));
        } catch (Throwable ignored) {}
    }

    @Override
    public void onDestroy() {
        cleanup();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private void refreshAllSources() {
        Map<String, MediaController> discovered = new HashMap<>();

        if (sessionManager != null) {
            try {
                ComponentName cn = new ComponentName(this, CarMediaListenerService.class);
                List<MediaController> active = sessionManager.getActiveSessions(cn);
                if (active != null) {
                    for (MediaController c : active) {
                        if (c != null && c.getPackageName() != null) {
                            discovered.put(c.getPackageName(), c);
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        // Also harvest MediaSession.Token & notification text directly from active notifications!
        try {
            StatusBarNotification[] sbns = getActiveNotifications();
            if (sbns != null) {
                for (StatusBarNotification sbn : sbns) {
                    if (sbn == null) continue;
                    String pkg = sbn.getPackageName();
                    if (pkg == null) continue;
                    Notification n = sbn.getNotification();
                    if (n != null && n.extras != null) {
                        MediaSession.Token token = n.extras.getParcelable(Notification.EXTRA_MEDIA_SESSION);
                        if (token != null && !discovered.containsKey(pkg)) {
                            try {
                                discovered.put(pkg, new MediaController(this, token));
                            } catch (Throwable ignored) {}
                        }
                        if (PKG_YANDEX.equals(pkg) || PKG_SPOTIFY.equals(pkg)) {
                            inspectMediaNotification(sbn);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        updateSessions(new ArrayList<>(discovered.values()));
    }

    private void inspectMediaNotification(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            Notification n = sbn.getNotification();
            if (pkg == null || n == null || n.extras == null) return;
            Bundle ex = n.extras;
            CharSequence t = ex.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence sub = ex.getCharSequence(Notification.EXTRA_TEXT);
            if (t == null || t.length() == 0) return;

            Bitmap art = null;
            try {
                Icon lg = n.getLargeIcon();
                if (lg != null) {
                    Drawable d = lg.loadDrawable(this);
                    if (d instanceof BitmapDrawable) {
                        art = ((BitmapDrawable) d).getBitmap();
                    }
                }
            } catch (Throwable ignored) {}

            synchronized (fallbackCache) {
                TrackInfo info = fallbackCache.get(pkg);
                if (info == null) {
                    info = new TrackInfo();
                    info.packageName = pkg;
                    fallbackCache.put(pkg, info);
                }
                info.title = t.toString().trim();
                if (sub != null && sub.length() > 0) {
                    info.artist = sub.toString().trim();
                }
                if (art != null) {
                    info.artwork = art;
                }
                info.hasSession = true;
                recordHistoryLocked(pkg, info.title, info.artist);
                persistLastTrack(this, pkg, info.title, info.artist);
            }
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

                        @Override
                        public void onQueueChanged(List<MediaSession.QueueItem> queue) {
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

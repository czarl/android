/*
 * Copyright (c) 2015 IRCCloud, Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.irccloud.android;



import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.SQLException;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.WearableExtender;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.text.Html;
import android.text.Spanned;
import android.text.TextUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.widget.RemoteViews;

import com.crashlytics.android.Crashlytics;
import com.irccloud.android.activity.QuickReplyActivity;
import com.sonyericsson.extras.liveware.extension.util.notification.NotificationUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.ConcurrentModificationException;
import java.util.Timer;
import java.util.TimerTask;public class Notifications {
    public class Notification {
        public int cid;
        public int bid;
        public long eid;
        public String nick;
        public String message;
        public String network;
        public String chan;
        public String buffer_type;
        public String message_type;
        public boolean shown = false;

        public String toString() {
            return "{cid: " + cid + ", bid: " + bid + ", eid: " + eid + ", nick: " + nick + ", message: " + message + ", network: " + network + " shown: " + shown + "}";
        }
    }

    public class comparator implements Comparator<Notification> {
        public int compare(Notification n1, Notification n2) {
            if (n1.cid != n2.cid)
                return Integer.valueOf(n1.cid).compareTo(n2.cid);
            else if (n1.bid != n2.bid)
                return Integer.valueOf(n1.bid).compareTo(n2.bid);
            else
                return Long.valueOf(n1.eid).compareTo(n2.eid);
        }
    }

    private ArrayList<Notification> mNotifications = null;
    private SparseArray<String> mNetworks = null;
    private SparseArray<Long> mLastSeenEIDs = null;

    private static Notifications instance = null;
    private int excludeBid = -1;
    private static final Timer mNotificationTimer = new Timer("notification-timer");
    private TimerTask mNotificationTimerTask = null;

    public static Notifications getInstance() {
        if (instance == null)
            instance = new Notifications();
        return instance;
    }

    public Notifications() {
        try {
            load();
        } catch (Exception e) {
        }
    }

    private void load() {
        mNotifications = new ArrayList<Notification>();
        mNetworks = new SparseArray<String>();
        mLastSeenEIDs = new SparseArray<Long>();

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());

        if (prefs.contains("notifications_json")) {
            try {
                JSONArray array = new JSONArray(prefs.getString("networks_json", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    mNetworks.put(o.getInt("cid"), o.getString("network"));
                }

                array = new JSONArray(prefs.getString("lastseeneids_json", "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject o = array.getJSONObject(i);
                    mLastSeenEIDs.put(o.getInt("bid"), o.getLong("eid"));
                }

                synchronized (mNotifications) {
                    array = new JSONArray(prefs.getString("notifications_json", "[]"));
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject o = array.getJSONObject(i);
                        Notification n = new Notification();
                        n.bid = o.getInt("bid");
                        n.cid = o.getInt("cid");
                        n.eid = o.getLong("eid");
                        n.nick = o.getString("nick");
                        n.message = o.getString("message");
                        n.chan = o.getString("chan");
                        n.buffer_type = o.getString("buffer_type");
                        n.message_type = o.getString("message_type");
                        n.network = mNetworks.get(n.cid);
                        if (o.has("shown"))
                            n.shown = o.getBoolean("shown");
                        mNotifications.add(n);
                    }
                    Collections.sort(mNotifications, new comparator());
                }
            } catch (JSONException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
            }
        }
    }

    private static final Timer mSaveTimer = new Timer("notifications-save-timer");
    private TimerTask mSaveTimerTask = null;

    @TargetApi(9)
    private void save() {
        if (mSaveTimerTask != null)
            mSaveTimerTask.cancel();
        mSaveTimerTask = new TimerTask() {

            @Override
            public void run() {
                saveNow();
            }
        };
        try {
            mSaveTimer.schedule(mSaveTimerTask, 100);
        } catch (IllegalStateException e) {
            //Timer is already cancelled
        }
    }

    public void saveNow() {
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).edit();
        try {
            JSONArray array = new JSONArray();
            synchronized (mNotifications) {
                for (Notification n : mNotifications) {
                    if(n != null) {
                        JSONObject o = new JSONObject();
                        o.put("cid", n.cid);
                        o.put("bid", n.bid);
                        o.put("eid", n.eid);
                        o.put("nick", n.nick);
                        o.put("message", n.message);
                        o.put("chan", n.chan);
                        o.put("buffer_type", n.buffer_type);
                        o.put("message_type", n.message_type);
                        o.put("shown", n.shown);
                        array.put(o);
                    }
                }
                editor.putString("notifications_json", array.toString());
            }

            array = new JSONArray();
            for (int i = 0; i < mNetworks.size(); i++) {
                int cid = mNetworks.keyAt(i);
                String network = mNetworks.get(cid);
                JSONObject o = new JSONObject();
                o.put("cid", cid);
                o.put("network", network);
                array.put(o);
            }
            editor.putString("networks_json", array.toString());

            array = new JSONArray();
            for (int i = 0; i < mLastSeenEIDs.size(); i++) {
                int bid = mLastSeenEIDs.keyAt(i);
                long eid = mLastSeenEIDs.get(bid);
                JSONObject o = new JSONObject();
                o.put("bid", bid);
                o.put("eid", eid);
                array.put(o);
            }
            editor.putString("lastseeneids_json", array.toString());

            editor.remove("dismissedeids_json");

            if (Build.VERSION.SDK_INT >= 9)
                editor.apply();
            else
                editor.commit();
        } catch (ConcurrentModificationException e) {
            save();
        } catch (OutOfMemoryError|Exception e) {
            editor.remove("notifications_json");
            editor.remove("networks_json");
            editor.remove("lastseeneids_json");
            editor.remove("dismissedeids_json");
            editor.commit();
        }
    }

    public void clear() {
        try {
            synchronized (mNotifications) {
                if (mNotifications.size() > 0) {
                    for (Notification n : mNotifications) {
                        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(n.bid);
                    }
                }
            }
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
            try {
                if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                    NotificationUtil.deleteAllEvents(IRCCloudApplication.getInstance().getApplicationContext());
            } catch (Exception e) {
                //Sony LiveWare was probably removed
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (mSaveTimerTask != null)
            mSaveTimerTask.cancel();
        mNotifications.clear();
        mLastSeenEIDs.clear();
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).edit();
        editor.remove("notifications_json");
        editor.remove("lastseeneids_json");
        editor.remove("dismissedeids_json");
        editor.commit();
        updateTeslaUnreadCount();
    }

    public void clearNetworks() {
        mNetworks.clear();
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).edit();
        editor.remove("networks_json");
        editor.commit();
    }

    public void clearLastSeenEIDs() {
        mLastSeenEIDs.clear();
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).edit();
        editor.remove("lastseeneids_json");
        editor.commit();
    }

    public long getLastSeenEid(int bid) {
        if (mLastSeenEIDs.get(bid) != null)
            return mLastSeenEIDs.get(bid);
        else
            return -1;
    }

    public synchronized void updateLastSeenEid(int bid, long eid) {
        mLastSeenEIDs.put(bid, eid);
        save();
    }

    public synchronized void dismiss(int bid, long eid) {
        Notification n = getNotification(eid);
        synchronized (mNotifications) {
            if (n != null)
                mNotifications.remove(n);
        }
        save();
        if (IRCCloudApplication.getInstance() != null)
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
        updateTeslaUnreadCount();
    }

    public synchronized void addNetwork(int cid, String network) {
        mNetworks.put(cid, network);
        save();
    }

    public synchronized void deleteNetwork(int cid) {
        mNetworks.remove(cid);
        save();
    }

    public synchronized void addNotification(int cid, int bid, long eid, String from, String message, String chan, String buffer_type, String message_type) {
        long last_eid = getLastSeenEid(bid);
        if (eid <= last_eid) {
            Crashlytics.log("Refusing to add notification for seen eid: " + eid);
            return;
        }

        String network = getNetwork(cid);
        if (network == null)
            addNetwork(cid, "Unknown Network");
        Notification n = new Notification();
        n.bid = bid;
        n.cid = cid;
        n.eid = eid;
        n.nick = from;
        n.message = TextUtils.htmlEncode(ColorFormatter.emojify(message));
        n.chan = chan;
        n.buffer_type = buffer_type;
        n.message_type = message_type;
        n.network = network;

        synchronized (mNotifications) {
            //Log.d("IRCCloud", "Add: " + n);
            mNotifications.add(n);
            Collections.sort(mNotifications, new comparator());
        }
        save();
    }

    public void deleteNotification(int cid, int bid, long eid) {
        synchronized (mNotifications) {
            for (Notification n : mNotifications) {
                if (n.cid == cid && n.bid == bid && n.eid == eid) {
                    mNotifications.remove(n);
                    save();
                    return;
                }
            }
        }
    }

    public void deleteOldNotifications(int bid, long last_seen_eid) {
        boolean changed = false, pending = false;
        if (mNotificationTimerTask != null) {
            mNotificationTimerTask.cancel();
            pending = true;
        }

        ArrayList<Notification> notifications = getOtherNotifications();

        if (notifications.size() > 0) {
            for (Notification n : notifications) {
                if (n.bid == bid && n.eid <= last_seen_eid) {
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                    changed = true;
                }
            }
        }

        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n.bid == bid && n.eid <= last_seen_eid) {
                    mNotifications.remove(n);
                    i--;
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(bid);
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
                    changed = true;
                }
            }
        }
        save();
        if (changed) {
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
            try {
                if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                    NotificationUtil.deleteEvents(IRCCloudApplication.getInstance().getApplicationContext(), com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY + " = ?", new String[]{String.valueOf(bid)});
            } catch (Exception e) {
            }
            updateTeslaUnreadCount();
        }

        if(pending)
            showNotifications(mTicker);
    }

    public void deleteNotificationsForBid(int bid) {
        ArrayList<Notification> notifications = getOtherNotifications();

        if (notifications.size() > 0) {
            for (Notification n : notifications) {
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
            }
        }
        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(bid);

        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n.bid == bid) {
                    mNotifications.remove(n);
                    i--;
                }
            }
        }
        mLastSeenEIDs.remove(bid);
        IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
        try {
            if (PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext()).getBoolean("notify_sony", false))
                NotificationUtil.deleteEvents(IRCCloudApplication.getInstance().getApplicationContext(), com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY + " = ?", new String[]{String.valueOf(bid)});
        } catch (Exception e) {
            //User has probably uninstalled Sony Liveware
        }
        updateTeslaUnreadCount();
    }

    private boolean isMessage(String type) {
        return !(type.equalsIgnoreCase("channel_invite") || type.equalsIgnoreCase("callerid"));
    }

    public int count() {
        return mNotifications.size();
    }

    public ArrayList<Notification> getMessageNotifications() {
        ArrayList<Notification> notifications = new ArrayList<Notification>();

        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n != null && n.bid != excludeBid && isMessage(n.message_type)) {
                    if (n.network == null)
                        n.network = getNetwork(n.cid);
                    notifications.add(n);
                }
            }
        }
        return notifications;
    }

    public ArrayList<Notification> getOtherNotifications() {
        ArrayList<Notification> notifications = new ArrayList<Notification>();

        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n != null && n.bid != excludeBid && !isMessage(n.message_type)) {
                    if (n.network == null)
                        n.network = getNetwork(n.cid);
                    notifications.add(n);
                }
            }
        }
        return notifications;
    }

    public String getNetwork(int cid) {
        return mNetworks.get(cid);
    }

    public Notification getNotification(long eid) {
        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n.bid != excludeBid && n.eid == eid && isMessage(n.message_type)) {
                    if (n.network == null)
                        n.network = getNetwork(n.cid);
                    return n;
                }
            }
        }
        return null;
    }

    public synchronized void excludeBid(int bid) {
        excludeBid = -1;
        ArrayList<Notification> notifications = getOtherNotifications();

        if (notifications.size() > 0) {
            for (Notification n : notifications) {
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel((int) (n.eid / 1000));
            }
        }
        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).cancel(bid);
        excludeBid = bid;
    }

    private String mTicker = null;

    public synchronized void showNotifications(String ticker) {
        if (ticker != null)
            mTicker = ColorFormatter.emojify(ticker);

        if (mNotificationTimerTask == null) {
            try {
                TimerTask task = new TimerTask() {
                    @Override
                    public void run() {
                        mNotificationTimerTask = null;
                        showMessageNotifications(mTicker);
                        showOtherNotifications();
                        mTicker = null;
                        IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(new Intent(DashClock.REFRESH_INTENT));
                        updateTeslaUnreadCount();
                    }

                    @Override
                    public boolean cancel() {
                        mNotificationTimerTask = null;
                        return super.cancel();
                    }
                };
                mNotificationTimer.schedule(task, 5000);
                mNotificationTimerTask = task;
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void showOtherNotifications() {
        String title = "";
        String text = "";
        String ticker = null;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        ArrayList<Notification> notifications = getOtherNotifications();

        int notify_type = Integer.parseInt(prefs.getString("notify_type", "1"));
        boolean notify = false;
        if (notify_type == 1 || (notify_type == 2 && NetworkConnection.getInstance().isVisible()))
            notify = true;

        if (notifications.size() > 0 && notify) {
            for (Notification n : notifications) {
                if (!n.shown) {
                    if (n.message_type.equals("callerid")) {
                        title = "Callerid: " + n.nick + " (" + n.network + ")";
                        text = n.nick + " " + n.message;
                        ticker = n.nick + " " + n.message;
                    } else {
                        title = n.nick + " (" + n.network + ")";
                        text = n.message;
                        ticker = n.message;
                    }
                    NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify((int) (n.eid / 1000), buildNotification(ticker, n.bid, new long[]{n.eid}, title, text, Html.fromHtml(text), 1, null, null, title, null));
                    n.shown = true;
                }
            }
        }
    }

    @SuppressLint("NewApi")
    private android.app.Notification buildNotification(String ticker, int bid, long[] eids, String title, String text, Spanned big_text, int count, Intent replyIntent, Spanned wear_text, String network, String auto_messages[]) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());

        NotificationCompat.Builder builder = new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext())
                .setContentTitle(title + ((network != null) ? (" (" + network + ")") : ""))
                .setContentText(Html.fromHtml(text))
                .setAutoCancel(true)
                .setTicker(ticker)
                .setWhen(eids[0] / 1000)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(IRCCloudApplication.getInstance().getApplicationContext().getResources().getColor(R.color.dark_blue))
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(false);

        if (ticker != null && (System.currentTimeMillis() - prefs.getLong("lastNotificationTime", 0)) > 10000) {
            if (prefs.getBoolean("notify_vibrate", true))
                builder.setDefaults(android.app.Notification.DEFAULT_VIBRATE);
            String ringtone = prefs.getString("notify_ringtone", "content://settings/system/notification_sound");
            if (ringtone != null && ringtone.length() > 0)
                builder.setSound(Uri.parse(ringtone));
        }

        int led_color = Integer.parseInt(prefs.getString("notify_led_color", "1"));
        if (led_color == 1) {
            if (prefs.getBoolean("notify_vibrate", true))
                builder.setDefaults(android.app.Notification.DEFAULT_LIGHTS | android.app.Notification.DEFAULT_VIBRATE);
            else
                builder.setDefaults(android.app.Notification.DEFAULT_LIGHTS);
        } else if (led_color == 2) {
            builder.setLights(0xFF0000FF, 500, 500);
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.putLong("lastNotificationTime", System.currentTimeMillis());
        editor.commit();

        Intent i = new Intent();
        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), "com.irccloud.android.MainActivity"));
        i.putExtra("bid", bid);
        i.setData(Uri.parse("bid://" + bid));
        Intent dismiss = new Intent(IRCCloudApplication.getInstance().getApplicationContext().getResources().getString(R.string.DISMISS_NOTIFICATION));
        dismiss.setData(Uri.parse("irccloud-dismiss://" + bid));
        dismiss.putExtra("bid", bid);
        dismiss.putExtra("eids", eids);

        PendingIntent dismissPendingIntent = PendingIntent.getBroadcast(IRCCloudApplication.getInstance().getApplicationContext(), 0, dismiss, PendingIntent.FLAG_UPDATE_CURRENT);

        builder.setContentIntent(PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT));
        builder.setDeleteIntent(dismissPendingIntent);

        if (replyIntent != null) {
            WearableExtender extender = new WearableExtender();
            PendingIntent replyPendingIntent = PendingIntent.getService(IRCCloudApplication.getInstance().getApplicationContext(), bid + 1, replyIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_CANCEL_CURRENT);
            extender.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_reply,
                    "Reply", replyPendingIntent)
                    .addRemoteInput(new RemoteInput.Builder("extra_reply").setLabel("Reply to " + title).build()).build());

            if (count > 1 && wear_text != null)
                extender.addPage(new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext()).setContentText(wear_text).extend(new WearableExtender().setStartScrollBottom(true)).build());

            NotificationCompat.CarExtender.UnreadConversation.Builder unreadConvBuilder =
                    new NotificationCompat.CarExtender.UnreadConversation.Builder(title + ((network != null) ? (" (" + network + ")") : ""))
                            .setReadPendingIntent(dismissPendingIntent)
                            .setReplyAction(replyPendingIntent, new RemoteInput.Builder("extra_reply").setLabel("Reply to " + title).build());

            if (auto_messages != null) {
                for (String m : auto_messages) {
                    if (m != null && m.length() > 0) {
                        unreadConvBuilder.addMessage(m);
                    }
                }
            } else {
                unreadConvBuilder.addMessage(text);
            }
            unreadConvBuilder.setLatestTimestamp(eids[count - 1] / 1000);

            builder.extend(extender).extend(new NotificationCompat.CarExtender().setUnreadConversation(unreadConvBuilder.build()));
        }

        if(replyIntent != null && prefs.getBoolean("notify_quickreply", true)) {
            i = new Intent(IRCCloudApplication.getInstance().getApplicationContext(), QuickReplyActivity.class);
            i.setData(Uri.parse("irccloud-bid://" + bid));
            i.putExtras(replyIntent);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent quickReplyIntent = PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.ic_action_reply, "Quick Reply", quickReplyIntent);
        }

        android.app.Notification notification = builder.build();

        RemoteViews contentView = new RemoteViews(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), R.layout.notification);
        contentView.setTextViewText(R.id.title, title + " (" + network + ")");
        contentView.setTextViewText(R.id.text, (count == 1) ? Html.fromHtml(text) : (count + " unread highlights."));
        contentView.setLong(R.id.time, "setTime", eids[0] / 1000);
        notification.contentView = contentView;

        if (Build.VERSION.SDK_INT >= 16 && big_text != null) {
            RemoteViews bigContentView = new RemoteViews(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), R.layout.notification_expanded);
            bigContentView.setTextViewText(R.id.title, title + (!title.equals(network) ? (" (" + network + ")") : ""));
            bigContentView.setTextViewText(R.id.text, big_text);
            bigContentView.setLong(R.id.time, "setTime", eids[0] / 1000);
            if (count > 3) {
                bigContentView.setViewVisibility(R.id.more, View.VISIBLE);
                bigContentView.setTextViewText(R.id.more, "+" + (count - 3) + " more");
            } else {
                bigContentView.setViewVisibility(R.id.more, View.GONE);
            }
            if(replyIntent != null && prefs.getBoolean("notify_quickreply", true)) {
                bigContentView.setViewVisibility(R.id.actions, View.VISIBLE);
                bigContentView.setViewVisibility(R.id.action_divider, View.VISIBLE);
                i = new Intent(IRCCloudApplication.getInstance().getApplicationContext(), QuickReplyActivity.class);
                i.setData(Uri.parse("irccloud-bid://" + bid));
                i.putExtras(replyIntent);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent quickReplyIntent = PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT);
                bigContentView.setOnClickPendingIntent(R.id.action_reply, quickReplyIntent);
            }
            notification.bigContentView = bigContentView;
        }

        return notification;
    }

    private void notifyPebble(String title, String body) {
        JSONObject jsonData = new JSONObject();
        try {
            final Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
            jsonData.put("title", title);
            jsonData.put("body", body);
            final String notificationData = new JSONArray().put(jsonData).toString();

            i.putExtra("messageType", "PEBBLE_ALERT");
            i.putExtra("sender", "IRCCloud");
            i.putExtra("notificationData", notificationData);
            IRCCloudApplication.getInstance().getApplicationContext().sendBroadcast(i);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void showMessageNotifications(String ticker) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(IRCCloudApplication.getInstance().getApplicationContext());
        String text = "";
        String weartext = "";
        ArrayList<Notification> notifications = getMessageNotifications();

        int notify_type = Integer.parseInt(prefs.getString("notify_type", "1"));
        boolean notify = false;
        if (notify_type == 1 || (notify_type == 2 && NetworkConnection.getInstance().isVisible()))
            notify = true;

        if (notifications.size() > 0 && notify) {
            int lastbid = notifications.get(0).bid;
            int count = 0;
            long[] eids = new long[notifications.size()];
            String[] auto_messages = new String[notifications.size()];
            Notification last = null;
            count = 0;
            boolean show = false;
            for (Notification n : notifications) {
                if (n.bid != lastbid) {
                    if (show) {
                        String title = last.chan;
                        if (title == null || title.length() == 0)
                            title = last.nick;
                        if (title == null || title.length() == 0)
                            title = last.network;

                        Intent replyIntent = new Intent(RemoteInputService.ACTION_REPLY);
                        replyIntent.putExtra("bid", last.bid);
                        replyIntent.putExtra("cid", last.cid);
                        replyIntent.putExtra("eids", eids);
                        replyIntent.putExtra("network", last.network);
                        if (last.buffer_type.equals("channel"))
                            replyIntent.putExtra("to", last.chan);
                        else
                            replyIntent.putExtra("to", last.nick);

                        String body = "";
                        if (last.buffer_type.equals("channel")) {
                            if (last.message_type.equals("buffer_me_msg"))
                                body = "<b>— " + last.nick + "</b> " + last.message;
                            else
                                body = "<b>&lt;" + last.nick + "&gt;</b> " + last.message;
                        } else {
                            if (last.message_type.equals("buffer_me_msg"))
                                body = "— " + last.nick + " " + last.message;
                            else
                                body = last.message;
                        }

                        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(lastbid, buildNotification(ticker, lastbid, eids, title, body, Html.fromHtml(text), count, replyIntent, Html.fromHtml(weartext), last.network, auto_messages));
                    }
                    lastbid = n.bid;
                    text = "";
                    weartext = "";
                    count = 0;
                    eids = new long[notifications.size()];
                    show = false;
                    auto_messages = new String[notifications.size()];
                }
                if (count < 3) {
                    if (text.length() > 0)
                        text += "<br/>";
                    if (n.buffer_type.equals("conversation") && n.message_type.equals("buffer_me_msg"))
                        text += "— " + n.message;
                    else if (n.buffer_type.equals("conversation"))
                        text += n.message;
                    else if (n.message_type.equals("buffer_me_msg"))
                        text += "<b>— " + n.nick + "</b> " + n.message;
                    else
                        text += "<b>" + n.nick + "</b> " + n.message;
                }
                if (weartext.length() > 0)
                    weartext += "<br/><br/>";
                if (n.message_type.equals("buffer_me_msg"))
                    weartext += "<b>— " + n.nick + "</b> " + n.message;
                else
                    weartext += "<b>&lt;" + n.nick + "&gt;</b> " + n.message;

                if (n.buffer_type.equals("conversation")) {
                    if (n.message_type.equals("buffer_me_msg"))
                        auto_messages[count] = "— " + n.nick + " " + Html.fromHtml(n.message).toString();
                    else
                        auto_messages[count] = Html.fromHtml(n.message).toString();
                } else {
                    if (n.message_type.equals("buffer_me_msg"))
                        auto_messages[count] = "— " + n.nick + " " + Html.fromHtml(n.message).toString();
                    else
                        auto_messages[count] = n.nick + " said: " + Html.fromHtml(n.message).toString();
                }

                if (!n.shown) {
                    n.shown = true;
                    show = true;

                    if (prefs.getBoolean("notify_sony", false)) {
                        long time = System.currentTimeMillis();
                        long sourceId = NotificationUtil.getSourceId(IRCCloudApplication.getInstance().getApplicationContext(), SonyExtensionService.EXTENSION_SPECIFIC_ID);
                        if (sourceId == NotificationUtil.INVALID_ID) {
                            Crashlytics.log(Log.ERROR, "IRCCloud", "Sony LiveWare Manager not configured, disabling Sony notifications");
                            SharedPreferences.Editor editor = prefs.edit();
                            editor.putBoolean("notify_sony", false);
                            editor.commit();
                        } else {
                            ContentValues eventValues = new ContentValues();
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.EVENT_READ_STATUS, false);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.DISPLAY_NAME, n.nick);

                            if (n.buffer_type.equals("channel") && n.chan != null && n.chan.length() > 0)
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.TITLE, n.chan);
                            else
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.TITLE, n.network);

                            if (n.message_type.equals("buffer_me_msg"))
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.MESSAGE, "— " + Html.fromHtml(n.message).toString());
                            else
                                eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.MESSAGE, Html.fromHtml(n.message).toString());

                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.PERSONAL, 1);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.PUBLISHED_TIME, time);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.SOURCE_ID, sourceId);
                            eventValues.put(com.sonyericsson.extras.liveware.aef.notification.Notification.EventColumns.FRIEND_KEY, String.valueOf(n.bid));

                            try {
                                IRCCloudApplication.getInstance().getApplicationContext().getContentResolver().insert(com.sonyericsson.extras.liveware.aef.notification.Notification.Event.URI, eventValues);
                            } catch (IllegalArgumentException e) {
                                Log.e("IRCCloud", "Failed to insert event", e);
                            } catch (SecurityException e) {
                                Log.e("IRCCloud", "Failed to insert event, is Live Ware Manager installed?", e);
                            } catch (SQLException e) {
                                Log.e("IRCCloud", "Failed to insert event", e);
                            }
                        }
                    }

                    if (prefs.getBoolean("notify_pebble", false)) {
                        String pebbleTitle = n.network + ":\n";
                        String pebbleBody = "";
                        if (n.buffer_type.equals("channel") && n.chan != null && n.chan.length() > 0)
                            pebbleTitle = n.chan + ":\n";

                        if (n.message_type.equals("buffer_me_msg"))
                            pebbleBody = "— " + n.message;
                        else
                            pebbleBody = n.message;

                        if (n.nick != null && n.nick.length() > 0)
                            notifyPebble(n.nick, pebbleTitle + Html.fromHtml(pebbleBody).toString());
                        else
                            notifyPebble(n.network, pebbleTitle + Html.fromHtml(pebbleBody).toString());
                    }
                }
                eids[count++] = n.eid;
                last = n;
            }

            if (show) {
                String title = last.chan;
                if (title == null || title.length() == 0)
                    title = last.nick;
                if (title == null || title.length() == 0)
                    title = last.network;

                Intent replyIntent = new Intent(RemoteInputService.ACTION_REPLY);
                replyIntent.putExtra("bid", last.bid);
                replyIntent.putExtra("cid", last.cid);
                replyIntent.putExtra("network", last.network);
                replyIntent.putExtra("eids", eids);
                if (last.buffer_type.equals("channel"))
                    replyIntent.putExtra("to", last.chan);
                else
                    replyIntent.putExtra("to", last.nick);

                String body = "";
                if (last.buffer_type.equals("channel")) {
                    if (last.message_type.equals("buffer_me_msg"))
                        body = "<b>— " + last.nick + "</b> " + last.message;
                    else
                        body = "<b>&lt;" + last.nick + "&gt;</b> " + last.message;
                } else {
                    if (last.message_type.equals("buffer_me_msg"))
                        body = "— " + last.nick + " " + last.message;
                    else
                        body = last.message;
                }
                NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(lastbid, buildNotification(ticker, lastbid, eids, title, body, Html.fromHtml(text), count, replyIntent, Html.fromHtml(weartext), last.network, auto_messages));
            }
        }
    }

    public NotificationCompat.Builder alert(int bid, String title, String body) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(IRCCloudApplication.getInstance().getApplicationContext())
                .setContentTitle(title)
                .setContentText(body)
                .setTicker(body)
                .setAutoCancel(true)
                .setColor(IRCCloudApplication.getInstance().getApplicationContext().getResources().getColor(R.color.dark_blue))
                .setSmallIcon(R.drawable.ic_stat_notify);

        Intent i = new Intent();
        i.setComponent(new ComponentName(IRCCloudApplication.getInstance().getApplicationContext().getPackageName(), "com.irccloud.android.MainActivity"));
        i.putExtra("bid", bid);
        i.setData(Uri.parse("bid://" + bid));
        builder.setContentIntent(PendingIntent.getActivity(IRCCloudApplication.getInstance().getApplicationContext(), 0, i, PendingIntent.FLAG_UPDATE_CURRENT));

        NotificationManagerCompat.from(IRCCloudApplication.getInstance().getApplicationContext()).notify(bid, builder.build());

        return builder;
    }

    public void updateTeslaUnreadCount() {
        try {
            IRCCloudApplication.getInstance().getApplicationContext().getPackageManager().getPackageInfo("com.teslacoilsw.notifier", PackageManager.GET_META_DATA);
        } catch (PackageManager.NameNotFoundException e) {
            return;
        }

        int count = 0;

        synchronized (mNotifications) {
            for (int i = 0; i < mNotifications.size(); i++) {
                Notification n = mNotifications.get(i);
                if (n.bid != excludeBid) {
                    count++;
                }
            }
        }

        try {
            ContentValues cv = new ContentValues();
            cv.put("tag", IRCCloudApplication.getInstance().getApplicationContext().getPackageManager().getLaunchIntentForPackage(IRCCloudApplication.getInstance().getApplicationContext().getPackageName()).getComponent().flattenToString());
            cv.put("count", count);
            IRCCloudApplication.getInstance().getApplicationContext().getContentResolver().insert(Uri.parse("content://com.teslacoilsw.notifier/unread_count"), cv);
        } catch (IllegalArgumentException ex) {
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
}
package com.kc97.carlauncher;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class AppDrawerDialog extends Dialog {
    private static class AppEntry {
        String label;
        String packageName;
        String activityName;
        Drawable icon;
    }

    private final List<AppEntry> apps = new ArrayList<>();
    private final BaseAdapter adapter;

    public AppDrawerDialog(Context context) {
        super(context, android.R.style.Theme_Material_NoActionBar_Fullscreen);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F0090C12"));
        int pad = dp(context, 20);
        root.setPadding(pad, dp(context, 14), pad, pad);

        // Header bar
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 8), 0, dp(context, 8), dp(context, 14));

        TextView title = new TextView(context);
        title.setText("Все приложения");
        title.setTextColor(Color.parseColor("#F3F7FF"));
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(title, titleLp);

        TextView settingsBtn = makePillButton(context, "⚙ Настройки", "#1E293B", "#E2E8F0");
        settingsBtn.setOnClickListener(v -> {
            dismiss();
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch (Throwable ignored) {}
        });
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sLp.rightMargin = dp(context, 12);
        header.addView(settingsBtn, sLp);

        TextView closeBtn = makePillButton(context, "✕ Закрыть", "#2A1620", "#FDA4AF");
        closeBtn.setOnClickListener(v -> dismiss());
        header.addView(closeBtn);
        root.addView(header);

        // App grid
        GridView grid = new GridView(context);
        grid.setNumColumns(6);
        grid.setHorizontalSpacing(dp(context, 12));
        grid.setVerticalSpacing(dp(context, 14));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setVerticalScrollBarEnabled(false);

        adapter = new BaseAdapter() {
            @Override
            public int getCount() { return apps.size(); }
            @Override
            public Object getItem(int position) { return apps.get(position); }
            @Override
            public long getItemId(int position) { return position; }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout cell;
                ImageView icon;
                TextView label;
                if (convertView == null) {
                    cell = new LinearLayout(context);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER);
                    int cp = dp(context, 12);
                    cell.setPadding(cp, cp, cp, cp);
                    GradientDrawable bg = new GradientDrawable();
                    bg.setColor(Color.parseColor("#131B28"));
                    bg.setCornerRadius(dp(context, 16));
                    bg.setStroke(1, Color.parseColor("#223047"));
                    cell.setBackground(bg);

                    icon = new ImageView(context);
                    icon.setId(android.R.id.icon);
                    LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(context, 54), dp(context, 54));
                    ilp.bottomMargin = dp(context, 8);
                    cell.addView(icon, ilp);

                    label = new TextView(context);
                    label.setId(android.R.id.text1);
                    label.setTextColor(Color.parseColor("#E2E8F0"));
                    label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                    label.setGravity(Gravity.CENTER);
                    label.setSingleLine(true);
                    label.setEllipsize(TextUtils.TruncateAt.END);
                    cell.addView(label, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                } else {
                    cell = (LinearLayout) convertView;
                    icon = cell.findViewById(android.R.id.icon);
                    label = cell.findViewById(android.R.id.text1);
                }
                AppEntry e = apps.get(position);
                icon.setImageDrawable(e.icon);
                label.setText(e.label);
                return cell;
            }
        };
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = apps.get(position);
            dismiss();
            try {
                Intent i = new Intent(Intent.ACTION_MAIN);
                i.addCategory(Intent.CATEGORY_LAUNCHER);
                i.setClassName(e.packageName, e.activityName);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                context.startActivity(i);
            } catch (Throwable ignored) {}
        });

        root.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        loadAppsAsync(context);
    }

    private void loadAppsAsync(Context context) {
        new Thread(() -> {
            PackageManager pm = context.getPackageManager();
            Intent main = new Intent(Intent.ACTION_MAIN, null);
            main.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> resolved = pm.queryIntentActivities(main, 0);
            List<AppEntry> loaded = new ArrayList<>();
            String myPkg = context.getPackageName();
            for (ResolveInfo ri : resolved) {
                if (myPkg.equals(ri.activityInfo.packageName)) continue;
                AppEntry e = new AppEntry();
                CharSequence lbl = ri.loadLabel(pm);
                e.label = lbl != null ? lbl.toString() : ri.activityInfo.packageName;
                e.packageName = ri.activityInfo.packageName;
                e.activityName = ri.activityInfo.name;
                e.icon = ri.loadIcon(pm);
                loaded.add(e);
            }
            Collections.sort(loaded, (a, b) -> a.label.compareToIgnoreCase(b.label));
            new Handler(Looper.getMainLooper()).post(() -> {
                apps.clear();
                apps.addAll(loaded);
                adapter.notifyDataSetChanged();
            });
        }).start();
    }

    private static TextView makePillButton(Context ctx, String text, String bgHex, String textHex) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor(textHex));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.getPaint().setFakeBoldText(true);
        tv.setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10));
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(Color.parseColor(bgHex));
        gd.setCornerRadius(dp(ctx, 14));
        gd.setStroke(1, Color.parseColor("#334155"));
        tv.setBackground(gd);
        return tv;
    }

    private static int dp(Context ctx, int val) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, val, ctx.getResources().getDisplayMetrics()));
    }
}

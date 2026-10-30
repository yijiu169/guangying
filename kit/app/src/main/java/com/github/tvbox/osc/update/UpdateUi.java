package com.github.tvbox.osc.update;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 更新相关的轻量 UI（全部用代码构建，不新增 layout 资源，避免与宿主工程资源冲突）。
 * 面向 10-foot TV 场景：大字号、深色底、居中、不可误触取消。
 */
final class UpdateUi {

    private UpdateUi() {
    }

    static class Holder {
        TextView title;
        TextView sub;
        ProgressBar bar;
        TextView percent;

        void setTitle(String s) {
            title.setText(s);
        }

        void setSub(String s) {
            sub.setText(s);
        }

        void setPercent(String s) {
            percent.setText(s);
        }
    }

    /** 创建一个更新进度/提示对话框（不可取消，避免下载中断） */
    static Dialog create(Activity act, String title, String sub) {
        Dialog d = new Dialog(act, android.R.style.Theme_Translucent_NoTitleBar);
        d.setCancelable(false);
        d.setCanceledOnTouchOutside(false);

        LinearLayout root = new LinearLayout(act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(act, 40), dp(act, 30), dp(act, 40), dp(act, 26));

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF20A0D12);
        bg.setCornerRadius(dp(act, 18));
        bg.setStroke(dp(act, 1), 0x33FFFFFF);
        root.setBackground(bg);

        TextView t = new TextView(act);
        t.setText(title);
        t.setTextColor(Color.WHITE);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        t.setGravity(Gravity.CENTER);
        root.addView(t, lp(-2, -2, 0, dp(act, 10)));

        TextView p = new TextView(act);
        p.setText("0%");
        p.setTextColor(0xFF46D6C4);
        p.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        p.setGravity(Gravity.CENTER);
        root.addView(p, lp(-2, -2, 0, dp(act, 6)));

        ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        root.addView(bar, lp(dp(act, 380), dp(act, 8), 0, dp(act, 12)));

        TextView s = new TextView(act);
        s.setText(sub);
        s.setTextColor(0xCCFFFFFF);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        s.setGravity(Gravity.CENTER);
        root.addView(s, lp(-2, -2, 0, 0));

        d.setContentView(root, lp(dp(act, 470), -2, 0, 0));

        Holder h = new Holder();
        h.title = t;
        h.sub = s;
        h.bar = bar;
        h.percent = p;
        d.getWindow().getDecorView().setTag(h);
        return d;
    }

    static Holder holder(Dialog d) {
        try {
            return (Holder) d.getWindow().getDecorView().getTag();
        } catch (Throwable t) {
            return null;
        }
    }

    static void setIndeterminate(Dialog d, String text) {
        Holder h = holder(d);
        if (h == null) return;
        h.percent.setText("…");
        h.bar.setIndeterminate(true);
        h.setSub(text);
    }

    static void setProgress(Dialog d, int percent, String text) {
        Holder h = holder(d);
        if (h == null) return;
        h.bar.setIndeterminate(false);
        if (percent >= 0) {
            h.bar.setProgress(percent);
            h.percent.setText(percent + "%");
        } else {
            h.percent.setText("…");
        }
        if (text != null) h.setSub(text);
    }

    static void close(Dialog d) {
        try {
            if (d != null && d.isShowing()) d.dismiss();
        } catch (Throwable ignored) {
        }
    }

    static void toast(Context ctx, String msg) {
        try {
            Toast.makeText(ctx.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    static String size(long bytes) {
        if (bytes <= 0) return "未知大小";
        double mb = bytes / 1024d / 1024d;
        if (mb >= 1) return String.format(java.util.Locale.US, "%.1f MB", mb);
        return String.format(java.util.Locale.US, "%d KB", Math.max(1, bytes / 1024));
    }

    private static LinearLayout.LayoutParams lp(int w, int h, int top, int bottom) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = top;
        p.bottomMargin = bottom;
        return p;
    }

    private static int dp(Activity a, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    /** 仅用于让编译器知道 ViewGroup 被使用（部分定制 ROM 需要显式挂载参数） */
    @SuppressWarnings("unused")
    private static ViewGroup.LayoutParams rawLp(int w, int h) {
        return new ViewGroup.LayoutParams(w, h);
    }
}

package com.github.tvbox.osc.ui.adapter;

import android.annotation.TargetApi;
import android.graphics.Outline;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.douban.DoubanImage;
import com.github.tvbox.osc.douban.DoubanItem;

/**
 * 「光影」首页单行海报适配器。
 *
 * <p>一行一个实例（不使用多行合并），因此某一行加载失败/为空时可以独立展示占位提示，
 * 不影响其它行。卡片尺寸 100x150dp（2:3），间距 10dp 由 item 的 marginEnd 提供。
 *
 * <p>焦点态（与设计稿一致）：整卡放大 1.08、上浮 4dp；白色 2dp 描边由 item 的
 * background selector（sel_gy_poster_frame）绘制，跟随卡片 10dp 圆角，不向外扩张。
 */
public class GyPosterRowAdapter extends BaseQuickAdapter<DoubanItem, BaseViewHolder> {

    /** 卡片聚焦回调：由 Activity 用来联动 Hero 与记录行列位置。 */
    public interface OnCardFocusListener {
        /**
         * @param rowIndex 行下标（0 起）
         * @param position 该行内的数据下标（0 起）
         * @param item     被聚焦的条目，可能为 null
         */
        void onCardFocused(int rowIndex, int position, DoubanItem item);
    }

    private static final float FOCUS_SCALE = 1.12f;
    private static final float FOCUS_LIFT_DP = 6f;
    private static final int DURATION = 160;
    private static final float POSTER_CORNER_DP = 8f;

    private final int rowIndex;

    private OnCardFocusListener focusListener;

    public GyPosterRowAdapter(int rowIndex) {
        super(R.layout.item_gy_poster);
        this.rowIndex = rowIndex;
    }

    public void setOnCardFocusListener(OnCardFocusListener listener) {
        this.focusListener = listener;
    }

    @Override
    protected void convert(BaseViewHolder helper, final DoubanItem item) {
        final View root = helper.itemView;
        ImageView cover = helper.getView(R.id.iv_poster);
        TextView title = helper.getView(R.id.tv_title);
        TextView rate = helper.getView(R.id.tv_rate);

        int position = helper.getAdapterPosition() - getHeaderLayoutCount();
        if (position < 0) {
            position = helper.getLayoutPosition() - getHeaderLayoutCount();
        }
        final int cardPosition = position < 0 ? 0 : position;

        title.setText(item == null || item.title == null ? "" : item.title);

        if (item == null || TextUtils.isEmpty(item.rate)) {
            rate.setVisibility(View.GONE);
        } else {
            rate.setVisibility(View.VISIBLE);
            rate.setText(item.rate);
        }

        if (item == null || TextUtils.isEmpty(DoubanImage.direct(item.cover))) {
            cover.setImageResource(R.drawable.bg_poster_placeholder);
        } else {
            Glide.with(root.getContext())
                    .load(DoubanImage.req(item.cover))
                    .transition(DrawableTransitionOptions.withCrossFade(180))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(cover);
        }

        applyRoundedClip(cover);

        // 复用视图可能带着上一次的放大状态，绑定时先复位，避免滚动后出现"幽灵放大"
        boolean focused = root.isFocused();
        root.setScaleX(focused ? FOCUS_SCALE : 1f);
        root.setScaleY(focused ? FOCUS_SCALE : 1f);
        root.setTranslationY(focused ? -dp(root, FOCUS_LIFT_DP) : 0f);
        root.setSelected(focused);

        root.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.animate()
                        .scaleX(hasFocus ? FOCUS_SCALE : 1f)
                        .scaleY(hasFocus ? FOCUS_SCALE : 1f)
                        .translationY(hasFocus ? -dp(v, FOCUS_LIFT_DP) : 0f)
                        .setDuration(DURATION)
                        .start();
                v.setSelected(hasFocus);
                if (hasFocus && focusListener != null) {
                    focusListener.onCardFocused(rowIndex, cardPosition, item);
                }
            }
        });
    }

    /**
     * 海报按 8dp 圆角裁剪，让内层画面和外框 10dp 圆角视觉对齐。
     * 5.0 以下没有 Outline 裁剪能力，直接跳过（视觉上只是角略方，不影响功能）。
     */
    private void applyRoundedClip(ImageView view) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            OutlineClipper.clip(view);
        }
    }

    /**
     * Outline 裁剪实现单独放在嵌套类里：只有 5.0+ 才会加载这个类，
     * 避免 minSdk 19 的设备在类初始化阶段碰到 ViewOutlineProvider 而崩溃。
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class OutlineClipper {

        private static final ViewOutlineProvider PROVIDER = new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        dp(view, POSTER_CORNER_DP));
            }
        };

        private OutlineClipper() {
        }

        static void clip(ImageView view) {
            view.setClipToOutline(true);
            view.setOutlineProvider(PROVIDER);
        }
    }

    private static int dp(View view, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics());
    }
}

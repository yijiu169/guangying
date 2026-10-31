package com.github.tvbox.osc.ui.adapter;

import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.douban.DoubanItem;

/**
 * 「光影」排行榜适配器。
 *
 * <p>复用首页海报卡片 {@code item_gy_poster}，卡片左上角叠加「排名序号」角标
 * （前 3 名 {@code gy_gold}，其余 {@code gy_accent}）。角标在 convert 内动态 add，
 * 以 tag 复用，绑定前清理/复用同一个实例，避免 RecyclerView 复用产生多份残留。
 *
 * <p>排名即适配器位置（数据已按评分降序），因此 rank = position + 1。
 */
public class GyRankAdapter extends BaseQuickAdapter<DoubanItem, BaseViewHolder> {

    private static final float FOCUS_SCALE = 1.08f;
    private static final float FOCUS_LIFT_DP = 4f;
    private static final int DURATION = 160;

    /** 排名角标在卡片根布局上的 tag，用于复用时的查找与清理。 */
    private static final String RANK_TAG = "gy_rank";

    public GyRankAdapter() {
        super(R.layout.item_gy_poster);
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
        final int rank = position < 0 ? 1 : position + 1;

        title.setText(item == null || item.title == null ? "" : item.title);

        // 评分移到右上角，左侧留给排名角标（不新建卡片布局，仅调整既有控件位置）
        FrameLayout.LayoutParams rlp = (FrameLayout.LayoutParams) rate.getLayoutParams();
        rlp.gravity = Gravity.TOP | Gravity.END;
        rate.setLayoutParams(rlp);

        if (item == null || TextUtils.isEmpty(item.rate)) {
            rate.setVisibility(View.GONE);
        } else {
            rate.setVisibility(View.VISIBLE);
            rate.setText(item.rate);
        }

        if (item == null || TextUtils.isEmpty(item.coverUrl())) {
            cover.setImageResource(R.drawable.bg_poster_placeholder);
        } else {
            Glide.with(root.getContext())
                    .load(item.coverUrl())
                    .transition(DrawableTransitionOptions.withCrossFade(180))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(cover);
        }

        addRankBadge(root, rank);

        // 复用视图可能带着上一次放大状态，先复位，避免滚动后出现"幽灵放大"
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
            }
        });
    }

    /** 在卡片根（FrameLayout）左上角叠加排名角标；以 tag 复用同一个 TextView。 */
    private void addRankBadge(View rootView, int rank) {
        TextView badge = (TextView) rootView.findViewWithTag(RANK_TAG);
        if (badge == null) {
            badge = new TextView(rootView.getContext());
            badge.setTag(RANK_TAG);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.leftMargin = dp(rootView, 6);
            lp.topMargin = dp(rootView, 6);
            badge.setLayoutParams(lp);
            badge.setIncludeFontPadding(false);
            badge.setBackgroundResource(R.drawable.gy_rank_badge);
            badge.setPadding(dp(rootView, 6), dp(rootView, 2), dp(rootView, 6), dp(rootView, 2));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            badge.getPaint().setFakeBoldText(true);
            ((FrameLayout) rootView).addView(badge);
        }
        badge.setText(String.valueOf(rank));
        badge.setTextColor(rootView.getResources().getColor(
                rank <= 3 ? R.color.gy_gold : R.color.gy_accent));
    }

    private static int dp(View view, float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                view.getResources().getDisplayMetrics());
    }
}

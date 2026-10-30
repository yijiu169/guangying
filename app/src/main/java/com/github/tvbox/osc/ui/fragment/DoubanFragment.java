package com.github.tvbox.osc.ui.fragment;

import android.content.Intent;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseLazyFragment;
import com.github.tvbox.osc.douban.DoubanApi;
import com.github.tvbox.osc.douban.DoubanItem;
import com.github.tvbox.osc.douban.DoubanRepository;
import com.github.tvbox.osc.ui.activity.SearchActivity;
import com.github.tvbox.osc.ui.adapter.DoubanPosterAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7GridLayoutManager;

import java.util.List;

/**
 * 豆瓣海报墙（首页第一个 Tab）。
 *
 * 行为：
 *  - 顶部一排分类标签（热门 / 最新 / 豆瓣高分 / 华语 / 欧美 / 韩日 / 动画 / 纪录片），左右键切换；
 *  - 下方 5 列海报墙，焦点卡片放大上浮；
 *  - 点海报 → 用片名去现有播放源里搜索（元数据与播放源解耦：海报来自豆瓣，播放来自你的源）。
 */
public class DoubanFragment extends BaseLazyFragment {

    private TvRecyclerView mRecycler;
    private LinearLayout mTags;
    private TextView mState;
    private DoubanPosterAdapter adapter;

    private String currentTag = DoubanApi.TAGS[0];

    @Override
    protected int getLayoutResID() {
        return R.layout.fragment_douban;
    }

    @Override
    protected void init() {
        mRecycler = rootView.findViewById(R.id.mRecycler);
        mTags = rootView.findViewById(R.id.mTags);
        mState = rootView.findViewById(R.id.mState);

        mRecycler.setLayoutManager(new V7GridLayoutManager(mContext, 5));
        adapter = new DoubanPosterAdapter();
        mRecycler.setAdapter(adapter);
        mRecycler.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) itemView.animate().scaleX(1f).scaleY(1f).translationY(0f).setDuration(160).start();
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (itemView != null) itemView.animate().scaleX(1.08f).scaleY(1.08f).translationY(-6f).setDuration(160).start();
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (position < 0 || position >= adapter.getData().size()) return;
                openByTitle(adapter.getData().get(position));
            }
        });

        buildTags();
        load(currentTag);
    }

    /* ==================== 分类标签 ==================== */

    private void buildTags() {
        if (mTags == null) return;
        mTags.removeAllViews();
        for (String tag : DoubanApi.TAGS) {
            TextView tv = new TextView(mContext);
            tv.setText(tag);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(18), dp(7), dp(18), dp(7));
            tv.setFocusable(true);
            tv.setFocusableInTouchMode(true);
            tv.setTextColor(tag.equals(currentTag) ? Color.WHITE : 0xCCB9C4D2);
            tv.setBackgroundResource(tag.equals(currentTag) ? R.drawable.sel_douban_tag_on : R.drawable.sel_douban_tag);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(10);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(v -> switchTag(tag));
            tv.setOnFocusChangeListener((v, has) -> {
                if (has) v.animate().scaleX(1.06f).scaleY(1.06f).setDuration(140).start();
                else v.animate().scaleX(1f).scaleY(1f).setDuration(140).start();
            });
            mTags.addView(tv);
        }
    }

    private void switchTag(String tag) {
        if (tag.equals(currentTag) && adapter.getItemCount() > 0) return;
        currentTag = tag;
        buildTags();
        load(tag);
    }

    /* ==================== 数据 ==================== */

    private void load(String tag) {
        showState("正在从豆瓣加载…");
        DoubanRepository.load(tag, new DoubanRepository.Callback() {
            @Override
            public void onData(List<DoubanItem> list, boolean fromCache) {
                if (!isAdded()) return;
                hideState();
                adapter.setNewData(list);
                if (mRecycler.getChildCount() > 0) mRecycler.getChildAt(0).requestFocus();
            }

            @Override
            public void onError(String message) {
                if (!isAdded()) return;
                showState(message + "\n（可返回重试，或检查网络 / 更换分类）");
            }
        });
    }

    private void showState(String text) {
        if (mState == null) return;
        mState.setText(text);
        mState.setVisibility(View.VISIBLE);
        if (mRecycler != null) mRecycler.setVisibility(View.GONE);
    }

    private void hideState() {
        if (mState == null) return;
        mState.setVisibility(View.GONE);
        if (mRecycler != null) mRecycler.setVisibility(View.VISIBLE);
    }

    /* ==================== 打开 ==================== */

    /**
     * 豆瓣只提供元数据，播放要交给播放源。
     * 这里用片名跳到 TVBox 现成的搜索页，由它去所有源里找资源。
     */
    private void openByTitle(DoubanItem item) {
        if (item == null || item.title == null) return;
        try {
            Intent intent = new Intent(mContext, SearchActivity.class);
            intent.putExtra("title", item.title);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(mContext, "打开失败：" + item.title, Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}

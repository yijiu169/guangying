package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.douban.DoubanApi;
import com.github.tvbox.osc.douban.DoubanItem;
import com.github.tvbox.osc.douban.DoubanRepository;
import com.github.tvbox.osc.ui.adapter.GyRankAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「光影」排行榜页。
 *
 * <p>TVBox 首页左侧 rail「排行」项的真实目标页（rail 接线由 lead 负责，本类只保证可启动）。
 *
 * <p>结构：顶部标题栏（"排行榜" + 一排分类玻璃胶囊 Tab） + 下方横向海报墙。
 * 数据走 {@link DoubanRepository#load}（已带缓存与串行队列，不新增任何网络代码）；
 * 切 Tab 重新 load 对应 tag，默认进「豆瓣高分」。列表按 {@link DoubanItem#rate} 降序排序后显示，
 * parseFloat 失败排最后。卡片点击语义与首页一致——用片名跳 {@link SearchActivity}。
 *
 * <p>焦点：卡片聚焦不需要联动 Hero；按返回键 {@link #finish()} 回到首页。
 */
public class GyRankActivity extends BaseActivity {

    /** 默认分类：豆瓣高分（DoubanApi.TAGS[2]）。 */
    private static final String DEFAULT_TAG = DoubanApi.TAGS[2];

    private TvRecyclerView recycler;
    private TextView emptyView;
    private LinearLayout tabsContainer;

    private GyRankAdapter adapter;
    private final List<TextView> tabViews = new ArrayList<>();
    private int activeTab = -1;
    private String currentTag = DEFAULT_TAG;

    /** 防竞态：连续切 Tab 时，只有最新一次 load 的结果才会被采用。 */
    private int loadGen = 0;

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_gy_rank;
    }

    @Override
    protected void init() {
        recycler = findViewById(R.id.rankRecycler);
        emptyView = findViewById(R.id.rankEmpty);
        tabsContainer = findViewById(R.id.tabsContainer);

        adapter = new GyRankAdapter();
        recycler.setLayoutManager(new V7LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recycler.setClipChildren(false);
        recycler.setClipToPadding(false);
        recycler.setFocusable(true);
        recycler.setMemoryFocus(true);
        recycler.setAdapter(adapter);

        recycler.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                // 缩放动画由 adapter 的焦点监听负责
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                // 仅记录位置，无需额外处理
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (position < 0 || position >= adapter.getItemCount()) {
                    return;
                }
                openItem(adapter.getItem(position));
            }
        });

        emptyView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadTag(currentTag);
            }
        });

        buildTabs();
        setActiveTab(DEFAULT_TAG);
        // 默认把焦点落在当前分类 Tab 上，方便 DPAD 上下进入海报墙
        if (activeTab >= 0 && activeTab < tabViews.size()) {
            tabViews.get(activeTab).requestFocus();
        }
        loadTag(DEFAULT_TAG);
    }

    /** 返回键直接 finish() 回到首页。 */
    @Override
    public void onBackPressed() {
        finish();
    }

    /* ==================== 分类 Tab ==================== */

    private void buildTabs() {
        tabsContainer.removeAllViews();
        tabViews.clear();
        final String[] tags = DoubanApi.TAGS;
        for (int i = 0; i < tags.length; i++) {
            final String tag = tags[i];

            TextView tab = new TextView(this);
            tab.setText(tag);
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            tab.setIncludeFontPadding(false);
            tab.setFocusable(true);
            tab.setFocusableInTouchMode(true);
            tab.setBackgroundResource(R.drawable.gy_tab_bg);
            tab.setTextColor(getResources().getColorStateList(R.color.gy_tab_text));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(10);
            tab.setLayoutParams(lp);
            tab.setPadding(dp(18), dp(8), dp(18), dp(8));

            tab.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    v.animate().scaleX(hasFocus ? 1.06f : 1f).scaleY(hasFocus ? 1.06f : 1f)
                            .setDuration(140).start();
                    if (hasFocus) {
                        HorizontalScrollView scroll = findViewById(R.id.tabScroll);
                        if (scroll != null) {
                            scroll.requestChildFocus(v, v);
                        }
                    }
                }
            });
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setActiveTab(tag);
                    loadTag(tag);
                }
            });

            tabViews.add(tab);
            tabsContainer.addView(tab);
        }
    }

    private void setActiveTab(String tag) {
        for (int i = 0; i < tabViews.size(); i++) {
            boolean active = i < DoubanApi.TAGS.length && DoubanApi.TAGS[i].equals(tag);
            tabViews.get(i).setSelected(active);
            if (active) {
                activeTab = i;
            }
        }
    }

    /* ==================== 数据加载 ==================== */

    private void loadTag(String tag) {
        currentTag = tag;
        final int gen = ++loadGen;
        showEmpty("正在加载…", false);

        DoubanRepository.load(tag, new DoubanRepository.Callback() {
            @Override
            public void onData(List<DoubanItem> list, boolean fromCache) {
                if (isUnavailable() || gen != loadGen) {
                    return;
                }
                applyData(list);
            }

            @Override
            public void onError(String message) {
                if (isUnavailable() || gen != loadGen) {
                    return;
                }
                showEmpty("加载失败，按 OK 重试", true);
            }
        });
    }

    private void applyData(List<DoubanItem> list) {
        List<DoubanItem> sorted = new ArrayList<>();
        if (list != null) {
            sorted.addAll(list);
        }
        // 按评分降序；parseFloat 失败排最后
        Collections.sort(sorted, new java.util.Comparator<DoubanItem>() {
            @Override
            public int compare(DoubanItem a, DoubanItem b) {
                float ra = parseRate(a != null ? a.rate : "");
                float rb = parseRate(b != null ? b.rate : "");
                if (ra < 0 && rb < 0) {
                    return 0;
                }
                if (ra < 0) {
                    return 1; // 无效评分排最后
                }
                if (rb < 0) {
                    return -1;
                }
                return Float.compare(rb, ra); // 降序
            }
        });

        if (sorted.isEmpty()) {
            showEmpty("暂无数据，按 OK 重试", true);
            return;
        }

        emptyView.setVisibility(View.GONE);
        recycler.setVisibility(View.VISIBLE);
        adapter.setNewData(sorted);

        // 等一次布局后再请求首张卡片焦点，否则拿不到子 View
        recycler.post(new Runnable() {
            @Override
            public void run() {
                if (isUnavailable()) {
                    return;
                }
                recycler.setSelectedPosition(0);
                View child = recycler.getLayoutManager() == null
                        ? null : recycler.getLayoutManager().findViewByPosition(0);
                if (child != null) {
                    child.requestFocus();
                } else {
                    recycler.scrollToPosition(0);
                    recycler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isUnavailable()) {
                                return;
                            }
                            View v2 = recycler.getLayoutManager() == null
                                    ? null : recycler.getLayoutManager().findViewByPosition(0);
                            if (v2 != null) {
                                v2.requestFocus();
                            } else {
                                recycler.requestFocus();
                            }
                        }
                    });
                }
            }
        });
    }

    private void showEmpty(String text, boolean focusable) {
        emptyView.setText(text);
        emptyView.setVisibility(View.VISIBLE);
        recycler.setVisibility(View.GONE);
        emptyView.setFocusable(focusable);
        emptyView.setFocusableInTouchMode(focusable);
        if (focusable) {
            emptyView.requestFocus();
        }
    }

    private static float parseRate(String rate) {
        if (TextUtils.isEmpty(rate)) {
            return -1f;
        }
        try {
            return Float.parseFloat(rate.trim());
        } catch (Throwable t) {
            return -1f;
        }
    }

    /* ==================== 跳转 / 工具 ==================== */

    private void openItem(DoubanItem item) {
        if (item == null || TextUtils.isEmpty(item.title)) {
            return;
        }
        try {
            Intent intent = new Intent(this, SearchActivity.class);
            intent.putExtra("title", item.title);
            startActivity(intent);
        } catch (Throwable ignored) {
            // 搜索页异常不影响排行榜
        }
    }

    private boolean isUnavailable() {
        if (isFinishing()) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed();
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}

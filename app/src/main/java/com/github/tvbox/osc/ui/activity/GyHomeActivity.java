package com.github.tvbox.osc.ui.activity;

import android.content.Intent;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseActivity;
import com.github.tvbox.osc.douban.DoubanApi;
import com.github.tvbox.osc.douban.DoubanItem;
import com.github.tvbox.osc.douban.DoubanRepository;
import com.github.tvbox.osc.ui.adapter.GyPosterRowAdapter;
import com.owen.tvrecyclerview.widget.TvRecyclerView;
import com.owen.tvrecyclerview.widget.V7LinearLayoutManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「光影」原生 TV 首页。
 *
 * <p>结构：左侧 88dp 竖向导航 rail + 右侧主内容区（Hero 占屏高 46%，下方三行海报墙）。
 *
 * <p>焦点模型：
 * <ul>
 *   <li>rail 自己是一个竖向 LinearLayout，用系统焦点搜索上下移动；</li>
 *   <li>每行是一个横向 TvRecyclerView，左右在行内移动；</li>
 *   <li>行首按左 → 回到 rail；行内按上下 → 跨行，第一行再往上 → rail；</li>
 *   <li>rail 上按右 → 回到上次停留的行/列。</li>
 * </ul>
 *
 * <p>Hero 联动：卡片聚焦时 Hero 换成该条目；无操作时每 6 秒在「热门」前 8 条里自动轮换。
 * 轮换回调在 onPause 移除、onResume 恢复，onDestroy 彻底清理，避免退出后仍在跑。
 *
 * <p>数据：{@link DoubanRepository#load} 三个分类并行拉取（仓库内部串行 + 缓存），
 * PREVIEW_MODE 构建下仓库自动回退到样例数据，无网也能验证界面。
 */
public class GyHomeActivity extends BaseActivity {

    /** Hero 自动轮换间隔（毫秒）。 */
    private static final long HERO_ROTATE_INTERVAL = 6000L;

    /** Hero 轮换池最大条目数。 */
    private static final int HERO_MAX_ITEMS = 8;

    /** Hero 背景渐变的候选色（按条目稳定取色，保证同一部片每次进都是同一个色）。 */
    private static final int[] HERO_TINTS = new int[]{
            0xFF0E3B37, 0xFF241539, 0xFF3A2412, 0xFF102C42,
            0xFF3B1220, 0xFF172F1D, 0xFF2E1A2B, 0xFF0D2F3A
    };

    private static final String ROW_TAG_EMPTY_HINT = "暂无数据，按 OK 重试";

    /* ==================== 视图 ==================== */

    private ImageView ivHeroBackdrop;
    private View heroTint;
    private TextView tvHeroTitle;
    private TextView tvHeroRate;
    private TextView tvHeroMeta;
    private TextView tvHeroIntro;
    private LinearLayout heroDots;
    private ScrollView rowScroll;
    private LinearLayout rowContainer;
    private LinearLayout railBar;

    /* ==================== 状态 ==================== */

    private final List<RowHolder> rowHolders = new ArrayList<>();
    private final List<DoubanItem> heroPool = new ArrayList<>();
    private final List<View> heroDotViews = new ArrayList<>();
    private final List<RailItemHolder> railItems = new ArrayList<>();

    private final Handler heroHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService introExecutor = Executors.newSingleThreadExecutor();
    private final Set<String> introRequested = new HashSet<>();

    private DoubanItem currentHeroItem = null;
    private int heroIndex = 0;
    private boolean resumed = false;
    private boolean initialFocusDone = false;

    private int lastRow = 0;
    private int lastColumn = 0;
    private int railFocusIndex = 0;
    private long lastBackTime = 0L;

    /** Hero 自动轮换任务；每 6 秒推进一张。 */
    private final Runnable heroRotateRunnable = new Runnable() {
        @Override
        public void run() {
            rotateHero();
            heroHandler.postDelayed(this, HERO_ROTATE_INTERVAL);
        }
    };

    /* ==================== 生命周期 ==================== */

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_gy_home;
    }

    @Override
    protected void init() {
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop);
        heroTint = findViewById(R.id.heroTint);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroRate = findViewById(R.id.tvHeroRate);
        tvHeroMeta = findViewById(R.id.tvHeroMeta);
        tvHeroIntro = findViewById(R.id.tvHeroIntro);
        heroDots = findViewById(R.id.heroDots);
        rowScroll = findViewById(R.id.rowScroll);
        rowContainer = findViewById(R.id.rowContainer);
        railBar = findViewById(R.id.railBar);

        buildRail();
        buildRows();
        loadAllRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        restartHeroRotation();
    }

    @Override
    protected void onPause() {
        resumed = false;
        heroHandler.removeCallbacks(heroRotateRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        heroHandler.removeCallbacksAndMessages(null);
        introExecutor.shutdownNow();
        super.onDestroy();
    }

    /** 首页是根页面：两次返回键间隔小于 2 秒才真正退出（与 TVBox 原有交互保持一致）。 */
    @Override
    public void onBackPressed() {
        long now = System.currentTimeMillis();
        if (now - lastBackTime < 2000) {
            super.onBackPressed();
            return;
        }
        lastBackTime = now;
        Toast.makeText(this, "再按一次返回键退出应用", Toast.LENGTH_SHORT).show();
    }

    /* ==================== 左侧 rail ==================== */

    private void buildRail() {
        final int[] icons = new int[]{
                R.drawable.ic_gy_home,
                R.drawable.icon_search,
                R.drawable.ic_gy_rank,
                R.drawable.ic_gy_user,
                R.drawable.icon_setting
        };
        final String[] labels = new String[]{"首页", "搜索", "排行", "我的", "设置"};

        railBar.removeAllViews();
        railItems.clear();

        for (int i = 0; i < labels.length; i++) {
            final int index = i;

            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setBackgroundResource(R.drawable.sel_gy_rail_item);
            item.setPadding(0, dp(8), 0, dp(8));

            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(22);
            item.setLayoutParams(itemLp);

            ImageView icon = new ImageView(this);
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconLp.gravity = Gravity.CENTER_HORIZONTAL;
            icon.setLayoutParams(iconLp);
            Drawable drawable = loadDrawableSafely(icons[index]);
            if (drawable != null) {
                icon.setImageDrawable(drawable);
            }
            icon.setColorFilter(getResources().getColor(R.color.gy_text_dim), PorterDuff.Mode.SRC_IN);

            TextView label = new TextView(this);
            label.setText(labels[index]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.setTextColor(getResources().getColor(R.color.gy_text_dim));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.CENTER_HORIZONTAL;
            labelLp.topMargin = dp(6);
            label.setLayoutParams(labelLp);

            item.addView(icon);
            item.addView(label);

            final RailItemHolder holder = new RailItemHolder(item, icon, label);
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    railFocusIndex = index;
                    v.animate().scaleX(hasFocus ? 1.04f : 1f).scaleY(hasFocus ? 1.04f : 1f)
                            .setDuration(140).start();
                    holder.tint(hasFocus);
                }
            });
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onRailItemClicked(index);
                }
            });
            // 从 rail 往右进入内容区：显式接管，避免系统焦点搜索随机落到某张卡片上
            item.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                        return focusContentFromRail();
                    }
                    return false;
                }
            });

            railItems.add(holder);
            railBar.addView(item);
        }
    }

    private void onRailItemClicked(int index) {
        switch (index) {
            case 0:
                focusCard(0, lastColumn);
                break;
            case 1:
                jumpActivity(SearchActivity.class);
                break;
            case 2:
                jumpActivity(GyRankActivity.class);
                break;
            case 3:
                jumpActivity(CollectActivity.class);
                break;
            case 4:
                jumpActivity(SettingActivity.class);
                break;
            default:
                break;
        }
    }

    /** rail → 内容区，回到上次停留的行列。 */
    private boolean focusContentFromRail() {
        if (rowHolders.isEmpty()) {
            return false;
        }
        int row = clamp(lastRow, 0, rowHolders.size() - 1);
        RowHolder holder = rowHolders.get(row);
        if (holder.data.isEmpty()) {
            if (holder.emptyView.requestFocus()) {
                return true;
            }
            // 该行没数据就找一个有数据的行
            for (int i = 0; i < rowHolders.size(); i++) {
                if (!rowHolders.get(i).data.isEmpty()) {
                    focusCard(i, lastColumn);
                    return true;
                }
            }
            return false;
        }
        focusCard(row, lastColumn);
        return true;
    }

    /** 回到 rail（行首按左 / 第一行按上 时调用）。 */
    private void focusRail() {
        if (railItems.isEmpty()) {
            return;
        }
        int index = clamp(railFocusIndex, 0, railItems.size() - 1);
        View target = railItems.get(index).root;
        if (!target.requestFocus()) {
            railItems.get(0).root.requestFocus();
        }
    }

    /* ==================== 海报行 ==================== */

    private void buildRows() {
        // tag 沿用豆瓣真实分类值（DoubanApi.TAGS），标题用设计稿文案
        addRow(0, DoubanApi.TAGS[0], "豆瓣热门", "实时口碑");
        addRow(1, DoubanApi.TAGS[1], "最新上架", "刚刚上线");
        addRow(2, DoubanApi.TAGS[2], "高分佳作", "评分最高");
    }

    private void addRow(final int index, String tag, String title, String subTitle) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(22);
        row.setLayoutParams(rowLp);

        // 行标题 14sp bold 白 + 右侧 11sp 灰 tag
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvTitle.setTextColor(getResources().getColor(R.color.color_FFFFFF));
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setIncludeFontPadding(false);
        header.addView(tvTitle);

        TextView tvSub = new TextView(this);
        tvSub.setText(subTitle);
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvSub.setTextColor(getResources().getColor(R.color.gy_text_dim));
        tvSub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.leftMargin = dp(8);
        tvSub.setLayoutParams(subLp);
        header.addView(tvSub);

        // 卡片区：162dp = 150dp 卡片 + 上下各 5dp 余量（聚焦放大 1.08 后不裁切）
        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(162));
        bodyLp.topMargin = dp(10);
        body.setLayoutParams(bodyLp);
        body.setClipChildren(false);
        body.setClipToPadding(false);

        final TvRecyclerView recycler = new TvRecyclerView(this);
        FrameLayout.LayoutParams recyclerLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        recycler.setLayoutParams(recyclerLp);
        recycler.setLayoutManager(new V7LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        recycler.setClipChildren(false);
        recycler.setClipToPadding(false);
        recycler.setPadding(0, dp(5), 0, dp(5));
        recycler.setFocusable(true);
        recycler.setMemoryFocus(true);

        final GyPosterRowAdapter adapter = new GyPosterRowAdapter(index);
        recycler.setAdapter(adapter);

        final TextView emptyView = new TextView(this);
        emptyView.setText(ROW_TAG_EMPTY_HINT);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        emptyView.setTextColor(getResources().getColor(R.color.gy_text_dim));
        emptyView.setGravity(Gravity.CENTER_VERTICAL);
        emptyView.setPadding(dp(14), dp(10), dp(14), dp(10));
        emptyView.setBackgroundResource(R.drawable.sel_gy_hint);
        emptyView.setFocusable(true);
        emptyView.setFocusableInTouchMode(true);
        emptyView.setVisibility(View.GONE);
        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyLp.gravity = Gravity.CENTER_VERTICAL;
        emptyView.setLayoutParams(emptyLp);

        body.addView(recycler);
        body.addView(emptyView);
        row.addView(header);
        row.addView(body);
        rowContainer.addView(row);

        final RowHolder holder = new RowHolder(index, tag, row, adapter, recycler, emptyView);
        rowHolders.add(holder);

        adapter.setOnCardFocusListener(new GyPosterRowAdapter.OnCardFocusListener() {
            @Override
            public void onCardFocused(int rowIndex, int position, DoubanItem item) {
                handleCardFocused(rowIndex, position, item);
            }
        });

        // 点卡片：像现有豆瓣页一样，用片名去各播放源里搜索
        recycler.setOnItemListener(new TvRecyclerView.OnItemListener() {
            @Override
            public void onItemPreSelected(TvRecyclerView parent, View itemView, int position) {
                // 缩放动画由 adapter 的焦点监听负责，这里不做二次处理
            }

            @Override
            public void onItemSelected(TvRecyclerView parent, View itemView, int position) {
                if (position >= 0 && position < holder.adapter.getItemCount()) {
                    lastRow = index;
                    lastColumn = position;
                }
            }

            @Override
            public void onItemClick(TvRecyclerView parent, View itemView, int position) {
                if (position < 0 || position >= holder.adapter.getItemCount()) {
                    return;
                }
                openItem(holder.adapter.getItem(position));
            }
        });

        // 边界键：行首左 → rail；上下 → 跨行；第一行上 → rail
        recycler.setOnInBorderKeyEventListener(new TvRecyclerView.OnInBorderKeyEventListener() {
            @Override
            public boolean onInBorderKeyEvent(int direction, View view) {
                if (direction == View.FOCUS_LEFT) {
                    focusRail();
                    return true;
                }
                if (direction == View.FOCUS_UP) {
                    if (index > 0) {
                        int column = recycler.getSelectedPosition();
                        focusCard(index - 1, column);
                    } else {
                        focusRail();
                    }
                    return true;
                }
                if (direction == View.FOCUS_DOWN) {
                    if (index < rowHolders.size() - 1) {
                        int column = recycler.getSelectedPosition();
                        focusCard(index + 1, column);
                        return true;
                    }
                    return false;
                }
                return false;
            }
        });

        // 空态提示：按 OK 重新拉取
        emptyView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadRow(holder);
            }
        });
    }

    /* ==================== 数据加载 ==================== */

    private void loadAllRows() {
        for (int i = 0; i < rowHolders.size(); i++) {
            loadRow(rowHolders.get(i));
        }
    }

    private void loadRow(final RowHolder holder) {
        if (holder == null || holder.loading) {
            return;
        }
        holder.loading = true;
        showPlaceholder(holder, "正在加载…");

        DoubanRepository.load(holder.tag, new DoubanRepository.Callback() {
            @Override
            public void onData(List<DoubanItem> list, boolean fromCache) {
                if (isActivityUnavailable()) {
                    return;
                }
                holder.loading = false;
                applyRowData(holder, list);
            }

            @Override
            public void onError(String message) {
                if (isActivityUnavailable()) {
                    return;
                }
                holder.loading = false;
                holder.data.clear();
                holder.adapter.setNewData(new ArrayList<DoubanItem>());
                showPlaceholder(holder, ROW_TAG_EMPTY_HINT);
                if (holder.index == 0 && !initialFocusDone) {
                    initialFocusDone = true;
                    holder.emptyView.requestFocus();
                }
            }
        });
    }

    private void applyRowData(RowHolder holder, List<DoubanItem> list) {
        holder.data.clear();
        if (list != null) {
            holder.data.addAll(list);
        }
        if (holder.data.isEmpty()) {
            holder.adapter.setNewData(new ArrayList<DoubanItem>());
            showPlaceholder(holder, ROW_TAG_EMPTY_HINT);
            return;
        }

        holder.emptyView.setVisibility(View.GONE);
        holder.recycler.setVisibility(View.VISIBLE);
        holder.adapter.setNewData(new ArrayList<>(holder.data));

        fillHeroPoolIfNeeded(holder);

        if (holder.index == 0 && !initialFocusDone) {
            initialFocusDone = true;
            // 等 RecyclerView 完成一次布局再请求焦点，否则拿不到子 View
            holder.recycler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityUnavailable()) {
                        focusCard(0, 0);
                    }
                }
            });
        }
    }

    private void showPlaceholder(RowHolder holder, String text) {
        holder.emptyView.setText(text);
        holder.emptyView.setVisibility(View.VISIBLE);
        holder.recycler.setVisibility(View.GONE);
    }

    /* ==================== Hero ==================== */

    private void fillHeroPoolIfNeeded(RowHolder holder) {
        if (!heroPool.isEmpty() || holder.data.isEmpty()) {
            return;
        }
        heroPool.clear();
        int count = Math.min(HERO_MAX_ITEMS, holder.data.size());
        for (int i = 0; i < count; i++) {
            heroPool.add(holder.data.get(i));
        }
        heroIndex = 0;
        buildDots(count);
        bindHero(heroPool.get(0));
        restartHeroRotation();
    }

    private void buildDots(int count) {
        heroDots.removeAllViews();
        heroDotViews.clear();
        for (int i = 0; i < count; i++) {
            View dot = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(6), dp(6));
            lp.rightMargin = dp(6);
            lp.gravity = Gravity.CENTER_VERTICAL;
            dot.setLayoutParams(lp);
            dot.setBackgroundResource(R.drawable.shape_gy_dot_off);
            heroDots.addView(dot);
            heroDotViews.add(dot);
        }
    }

    private void updateDots(int active) {
        for (int i = 0; i < heroDotViews.size(); i++) {
            View dot = heroDotViews.get(i);
            ViewGroup.LayoutParams rawLp = dot.getLayoutParams();
            if (rawLp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) rawLp;
                lp.width = i == active ? dp(16) : dp(6);
                lp.height = dp(6);
                dot.setLayoutParams(lp);
            }
            dot.setBackgroundResource(i == active
                    ? R.drawable.shape_gy_dot_on : R.drawable.shape_gy_dot_off);
        }
    }

    private void bindHero(final DoubanItem item) {
        if (item == null || isActivityUnavailable()) {
            return;
        }
        currentHeroItem = item;

        tvHeroTitle.setText(TextUtils.isEmpty(item.title) ? "光影" : item.title);

        if (TextUtils.isEmpty(item.rate)) {
            tvHeroRate.setText("★ 暂无评分");
            tvHeroRate.setTextColor(getResources().getColor(R.color.gy_text_dim));
        } else {
            tvHeroRate.setText("★ " + item.rate);
            tvHeroRate.setTextColor(getResources().getColor(R.color.gy_gold));
        }

        StringBuilder meta = new StringBuilder("· ");
        if (!TextUtils.isEmpty(item.year)) {
            meta.append(item.year).append(" · ");
        }
        meta.append("正片");
        tvHeroMeta.setText(meta.toString());

        tvHeroIntro.setText(heroIntroText(item));
        if (TextUtils.isEmpty(item.intro)) {
            loadHeroIntroAsync(item);
        }

        String cover = item.coverUrl();
        if (TextUtils.isEmpty(cover)) {
            ivHeroBackdrop.setImageResource(R.drawable.bg_poster_placeholder);
        } else {
            Glide.with(this)
                    .load(cover)
                    .transition(DrawableTransitionOptions.withCrossFade(260))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(ivHeroBackdrop);
        }

        applyHeroTint(item);
        updateDots(indexOfHero(item));
    }

    /**
     * 在 Hero 轮换池里找条目的下标。
     * 先按对象身份找（同一行数据内一定命中），再按 id 兜底——
     * 同一个片子在「热门」和「高分」两行里是两份不同的对象，靠 id 才能点亮同一个指示点。
     */
    private int indexOfHero(DoubanItem item) {
        if (item == null) {
            return -1;
        }
        int index = heroPool.indexOf(item);
        if (index >= 0) {
            return index;
        }
        if (TextUtils.isEmpty(item.id)) {
            return -1;
        }
        for (int i = 0; i < heroPool.size(); i++) {
            DoubanItem poolItem = heroPool.get(i);
            if (poolItem != null && item.id.equals(poolItem.id)) {
                return i;
            }
        }
        return -1;
    }

    /** 简介 2 行；没有简介时退化为类型副标题，避免 Hero 出现空行。 */
    private String heroIntroText(DoubanItem item) {
        if (item == null) {
            return "";
        }
        if (!TextUtils.isEmpty(item.intro)) {
            return item.intro;
        }
        if (!TextUtils.isEmpty(item.subtitle)) {
            return item.subtitle + " · 暂无简介";
        }
        return "暂无简介，按 OK 搜索该影片";
    }

    /** 背景渐变随条目换色：同一部片稳定取色，切换时 240ms 淡入。 */
    private void applyHeroTint(DoubanItem item) {
        if (heroTint == null || item == null) {
            return;
        }
        String key = (item.id == null ? "" : item.id) + "|" + (item.title == null ? "" : item.title);
        int tint = HERO_TINTS[Math.abs(key.hashCode()) % HERO_TINTS.length];
        int start = (tint & 0x00FFFFFF) | 0xCC000000;
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{start, 0x0006080C});
        heroTint.setBackground(gradient);
        heroTint.setAlpha(0.35f);
        heroTint.animate().alpha(1f).setDuration(240).start();
    }

    private void rotateHero() {
        if (heroPool.size() <= 1 || isActivityUnavailable()) {
            return;
        }
        heroIndex = (heroIndex + 1) % heroPool.size();
        bindHero(heroPool.get(heroIndex));
    }

    private void restartHeroRotation() {
        heroHandler.removeCallbacks(heroRotateRunnable);
        if (resumed && !heroPool.isEmpty()) {
            heroHandler.postDelayed(heroRotateRunnable, HERO_ROTATE_INTERVAL);
        }
    }

    /** 卡片聚焦：Hero 立即切到该条目，并重新计时 6 秒。 */
    private void handleCardFocused(int rowIndex, int position, DoubanItem item) {
        lastRow = rowIndex;
        lastColumn = position;
        if (item == null) {
            return;
        }
        int poolIndex = indexOfHero(item);
        heroIndex = poolIndex >= 0 ? poolIndex : 0;
        bindHero(item);
        restartHeroRotation();
    }

    /**
     * 豆瓣列表接口不返回简介，这里对当前 Hero 条目补拉一次详情页。
     * 只在没有简介时触发，按 id 去重，失败静默（不弹提示、不影响其它功能）。
     */
    private void loadHeroIntroAsync(final DoubanItem item) {
        if (item == null || TextUtils.isEmpty(item.url)) {
            return;
        }
        final String key = TextUtils.isEmpty(item.id) ? item.url : item.id;
        synchronized (introRequested) {
            if (introRequested.contains(key)) {
                return;
            }
            introRequested.add(key);
        }
        try {
            introExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    DoubanItem filled;
                    try {
                        filled = DoubanApi.detail(item);
                    } catch (Throwable t) {
                        return;
                    }
                    if (filled == null || TextUtils.isEmpty(filled.intro)) {
                        return;
                    }
                    final String intro = filled.intro;
                    heroHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isActivityUnavailable()) {
                                return;
                            }
                            DoubanItem shown = currentHeroItem;
                            boolean sameItem = shown == item
                                    || (shown != null && !TextUtils.isEmpty(shown.id)
                                    && shown.id.equals(key));
                            if (sameItem) {
                                tvHeroIntro.setText(intro);
                            }
                        }
                    });
                }
            });
        } catch (Throwable ignored) {
            // 线程池已关闭时忽略（Activity 正在销毁）
        }
    }

    /* ==================== 焦点移动 ==================== */

    /** 把焦点定位到第 rowIndex 行第 column 张卡片，并滚动到该行。 */
    private void focusCard(int rowIndex, int column) {
        if (rowIndex < 0 || rowIndex >= rowHolders.size()) {
            return;
        }
        final RowHolder holder = rowHolders.get(rowIndex);
        if (holder.data.isEmpty()) {
            holder.emptyView.requestFocus();
            return;
        }

        final int target = clamp(column, 0, holder.data.size() - 1);
        lastRow = rowIndex;
        lastColumn = target;
        holder.recycler.setSelectedPosition(target);

        View child = holder.recycler.getLayoutManager() == null
                ? null
                : holder.recycler.getLayoutManager().findViewByPosition(target);
        if (child != null) {
            child.requestFocus();
        } else {
            holder.recycler.scrollToPosition(target);
            holder.recycler.post(new Runnable() {
                @Override
                public void run() {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    View view = holder.recycler.getLayoutManager() == null
                            ? null
                            : holder.recycler.getLayoutManager().findViewByPosition(target);
                    if (view != null) {
                        view.requestFocus();
                    } else {
                        holder.recycler.requestFocus();
                    }
                }
            });
        }
        scrollRowIntoView(holder);
    }

    /** 让目标行进入可视区：只滚动最小距离，尽量保留上一行的上下文。 */
    private void scrollRowIntoView(RowHolder holder) {
        if (rowScroll == null || holder == null) {
            return;
        }
        View row = holder.container;
        int viewport = rowScroll.getHeight();
        if (viewport <= 0) {
            return;
        }
        int paddingTop = dp(6);
        int current = rowScroll.getScrollY();
        int top = row.getTop();
        int bottom = row.getBottom();

        int target = current;
        if (top - paddingTop < current) {
            target = Math.max(0, top - paddingTop);
        } else if (bottom > current + viewport) {
            target = bottom - viewport;
        }
        if (target != current) {
            rowScroll.smoothScrollTo(0, Math.max(0, target));
        }
    }

    private void openItem(DoubanItem item) {
        if (item == null || TextUtils.isEmpty(item.title)) {
            return;
        }
        try {
            Intent intent = new Intent(this, SearchActivity.class);
            intent.putExtra("title", item.title);
            startActivity(intent);
        } catch (Throwable ignored) {
            // 搜索页异常不影响首页
        }
    }

    /* ==================== 工具 ==================== */

    private Drawable loadDrawableSafely(int resId) {
        try {
            Drawable drawable = AppCompatResources.getDrawable(this, resId);
            return drawable == null ? null : drawable.mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private boolean isActivityUnavailable() {
        if (isFinishing()) {
            return true;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    /* ==================== 内部类型 ==================== */

    /** 一行海报墙的持有者。 */
    private static final class RowHolder {
        final int index;
        final String tag;
        final LinearLayout container;
        final GyPosterRowAdapter adapter;
        final TvRecyclerView recycler;
        final TextView emptyView;
        final List<DoubanItem> data = new ArrayList<>();

        boolean loading = false;

        RowHolder(int index, String tag, LinearLayout container, GyPosterRowAdapter adapter,
                  TvRecyclerView recycler, TextView emptyView) {
            this.index = index;
            this.tag = tag;
            this.container = container;
            this.adapter = adapter;
            this.recycler = recycler;
            this.emptyView = emptyView;
        }
    }

    /** 一个 rail 导航项。 */
    private static final class RailItemHolder {
        final View root;
        final ImageView icon;
        final TextView label;

        RailItemHolder(View root, ImageView icon, TextView label) {
            this.root = root;
            this.icon = icon;
            this.label = label;
        }

        void tint(boolean focused) {
            int color = focused
                    ? root.getResources().getColor(R.color.gy_accent)
                    : root.getResources().getColor(R.color.gy_text_dim);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            label.setTextColor(color);
        }
    }
}

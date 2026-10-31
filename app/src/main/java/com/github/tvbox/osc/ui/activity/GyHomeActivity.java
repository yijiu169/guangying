package com.github.tvbox.osc.ui.activity;

import android.annotation.TargetApi;
import android.content.Intent;
import android.graphics.Outline;
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
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 「光影」原生 TV 首页。
 *
 * <p>结构：左侧 128dp 竖向导航 rail（品牌 + 玻璃 pill 焦点态）+ 顶部 54dp 状态条
 * + 右侧主内容区（Hero 占屏高 46%，下方三行海报墙）。
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

    private View heroArea;
    private ImageView ivHeroBackdrop;
    private ImageView ivHeroPoster;
    private View heroTint;
    private TextView tvHeroKicker;
    private TextView tvHeroTitle;
    private TextView tvHeroRate;
    private TextView tvHeroMeta;
    private TextView tvHeroIntro;
    private LinearLayout heroDots;
    private TextView tvStatusClock;
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

    /** 状态条实时时钟：每 15 秒刷新一次 HH:mm（不追求秒级，避免无谓唤醒）。 */
    private final Runnable clockRunnable = new Runnable() {
        @Override
        public void run() {
            updateClock();
            heroHandler.postDelayed(this, 15000L);
        }
    };

    /* ==================== 生命周期 ==================== */

    @Override
    protected int getLayoutResID() {
        return R.layout.activity_gy_home;
    }

    @Override
    protected void init() {
        heroArea = findViewById(R.id.heroArea);
        ivHeroBackdrop = findViewById(R.id.ivHeroBackdrop);
        ivHeroPoster = findViewById(R.id.ivHeroPoster);
        heroTint = findViewById(R.id.heroTint);
        tvHeroKicker = findViewById(R.id.tvHeroKicker);
        tvHeroTitle = findViewById(R.id.tvHeroTitle);
        tvHeroRate = findViewById(R.id.tvHeroRate);
        tvHeroMeta = findViewById(R.id.tvHeroMeta);
        tvHeroIntro = findViewById(R.id.tvHeroIntro);
        heroDots = findViewById(R.id.heroDots);
        tvStatusClock = findViewById(R.id.tvStatusClock);
        rowScroll = findViewById(R.id.rowScroll);
        rowContainer = findViewById(R.id.rowContainer);
        railBar = findViewById(R.id.railBar);

        applyRoundedOutline(heroArea, 18);
        applyRoundedOutline(ivHeroPoster, 12);
        setLetterSpacing(tvHeroKicker, 0.22f);
        setLetterSpacing(findViewById(R.id.tvRailBrand), 0.32f);

        // rail 面板底色：linear-gradient(180deg, rgba(13,16,22,.96), rgba(9,11,16,.9))
        railBar.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0xF50D1016, 0xE6090B10}));

        // Hero 海报可点：进当前条目的详情（Hero 不放按钮，点海报即可）
        ivHeroPoster.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openItem(currentHeroItem);
            }
        });

        buildRail();
        buildRows();
        loadAllRows();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        restartHeroRotation();
        heroHandler.removeCallbacks(clockRunnable);
        clockRunnable.run();
    }

    @Override
    protected void onPause() {
        resumed = false;
        heroHandler.removeCallbacks(heroRotateRunnable);
        heroHandler.removeCallbacks(clockRunnable);
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

        // 品牌块（railBrand）写在 XML 里，这里只清掉上一次生成的导航项
        while (railBar.getChildCount() > 1) {
            railBar.removeViewAt(railBar.getChildCount() - 1);
        }
        railItems.clear();

        for (int i = 0; i < labels.length; i++) {
            final int index = i;

            // 根：可聚焦，聚焦时铺一层玻璃 pill（GradientDrawable，不新增 drawable 资源）
            FrameLayout item = new FrameLayout(this);
            item.setFocusable(true);
            item.setFocusableInTouchMode(true);
            item.setClipChildren(false);
            item.setClipToPadding(false);
            LinearLayout.LayoutParams itemLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            itemLp.bottomMargin = dp(4);
            item.setLayoutParams(itemLp);

            // 图标 + 标签（竖向居中）
            LinearLayout inner = new LinearLayout(this);
            inner.setOrientation(LinearLayout.VERTICAL);
            inner.setGravity(Gravity.CENTER);
            inner.setClipChildren(false);
            inner.setClipToPadding(false);
            inner.setPadding(dp(4), dp(13), dp(4), dp(13));
            item.addView(inner, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // 图标容器：底层辉光 + 上层图标（用同图放大 + accent 半透明近似 drop-shadow）
            FrameLayout iconWrap = new FrameLayout(this);
            LinearLayout.LayoutParams iconWrapLp = new LinearLayout.LayoutParams(dp(24), dp(24));
            iconWrapLp.gravity = Gravity.CENTER_HORIZONTAL;
            iconWrap.setLayoutParams(iconWrapLp);
            iconWrap.setClipChildren(false);

            ImageView glow = new ImageView(this);
            Drawable glowDrawable = loadDrawableSafely(icons[index]);
            if (glowDrawable != null) {
                glow.setImageDrawable(glowDrawable);
            }
            glow.setColorFilter(getResources().getColor(R.color.gy_accent), PorterDuff.Mode.SRC_IN);
            glow.setAlpha(0f);
            glow.setScaleX(1.35f);
            glow.setScaleY(1.35f);
            FrameLayout.LayoutParams glowLp = new FrameLayout.LayoutParams(dp(23), dp(23));
            glowLp.gravity = Gravity.CENTER;
            iconWrap.addView(glow, glowLp);

            ImageView icon = new ImageView(this);
            Drawable iconDrawable = loadDrawableSafely(icons[index]);
            if (iconDrawable != null) {
                icon.setImageDrawable(iconDrawable);
            }
            icon.setColorFilter(getResources().getColor(R.color.gy_text_dim), PorterDuff.Mode.SRC_IN);
            FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(23), dp(23));
            iconLp.gravity = Gravity.CENTER;
            iconWrap.addView(icon, iconLp);

            inner.addView(iconWrap);

            TextView label = new TextView(this);
            label.setText(labels[index]);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            label.setGravity(Gravity.CENTER);
            label.setIncludeFontPadding(false);
            label.getPaint().setFakeBoldText(true);
            label.setTextColor(getResources().getColor(R.color.gy_text_dim));
            LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            labelLp.gravity = Gravity.CENTER_HORIZONTAL;
            labelLp.topMargin = dp(6);
            label.setLayoutParams(labelLp);
            inner.addView(label);

            // 左侧竖向渐变指示条（accent → 豆瓣绿），仅聚焦时可见
            View indicator = new View(this);
            indicator.setBackground(buildIndicator());
            indicator.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams indicatorLp = new FrameLayout.LayoutParams(dp(3), dp(32));
            indicatorLp.gravity = Gravity.START | Gravity.CENTER_VERTICAL;
            indicatorLp.leftMargin = dp(3);
            item.addView(indicator, indicatorLp);

            final RailItemHolder holder = new RailItemHolder(item, icon, label, glow, indicator, buildPill());
            item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    railFocusIndex = index;
                    holder.setFocused(hasFocus);
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

    /** rail 聚焦态玻璃 pill：linear-gradient(180deg, rgba(255,255,255,.13), rgba(255,255,255,.05)) */
    private GradientDrawable buildPill() {
        GradientDrawable pill = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x21FFFFFF, 0x0DFFFFFF});
        pill.setCornerRadius(dp(14));
        return pill;
    }

    /** rail 聚焦态指示条：accent → 豆瓣绿 的竖向渐变。 */
    private GradientDrawable buildIndicator() {
        GradientDrawable indicator = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{getResources().getColor(R.color.gy_accent),
                        getResources().getColor(R.color.gy_douban)});
        indicator.setCornerRadius(dp(2));
        return indicator;
    }

    /** 只在 5.0+ 圆角裁剪；minSdk 19 上直接跳过（保持直角，不影响功能）。 */
    private void applyRoundedOutline(View view, final int cornerDp) {
        if (view == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            OutlineSetup.apply(view, cornerDp);
        }
    }

    /** 5.0+ 专属类单独承载，避免 minSdk 19 设备在类初始化阶段碰到 OutlineProvider。 */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class OutlineSetup {

        private OutlineSetup() {
        }

        static void apply(final View view, final int cornerDp) {
            view.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(v, cornerDp));
                }
            });
            view.setClipToOutline(true);
        }

        private static int dp(View view, int value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    view.getResources().getDisplayMetrics());
        }
    }

    /** 字距（em）：仅 5.0+ 支持，低版本忽略。 */
    private void setLetterSpacing(TextView view, float em) {
        if (view == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        view.setLetterSpacing(em);
    }

    /** 状态条时钟：HH:mm。 */
    private void updateClock() {
        if (tvStatusClock == null) {
            return;
        }
        tvStatusClock.setText(new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date()));
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
        // tag 沿用豆瓣真实分类值（DoubanApi.TAGS），标题与绿色小标签用设计稿文案
        addRow(0, DoubanApi.TAGS[0], "豆瓣热门", "实时");
        addRow(1, DoubanApi.TAGS[1], "最新上架", "最新");
        addRow(2, DoubanApi.TAGS[2], "高分佳作", "豆瓣");
    }

    private void addRow(final int index, String tag, String title, String chipText) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = dp(30);
        row.setLayoutParams(rowLp);

        // 行标题 19sp bold 白 + 豆瓣绿小标签（对齐原型 .row-head h2 / .tag）
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), 0, 0, 0);

        TextView tvTitle = new TextView(this);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 19);
        tvTitle.setTextColor(getResources().getColor(R.color.gy_text));
        tvTitle.getPaint().setFakeBoldText(true);
        tvTitle.setIncludeFontPadding(false);
        header.addView(tvTitle);

        TextView tvChip = new TextView(this);
        tvChip.setText(chipText);
        tvChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tvChip.setTextColor(getResources().getColor(R.color.gy_douban));
        tvChip.setIncludeFontPadding(false);
        tvChip.setPadding(dp(8), dp(2), dp(8), dp(2));
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setColor(0x00000000);
        chipBg.setStroke(dp(1), getResources().getColor(R.color.gy_douban_40));
        chipBg.setCornerRadius(dp(999));
        tvChip.setBackground(chipBg);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(12);
        tvChip.setLayoutParams(chipLp);
        header.addView(tvChip);

        // 卡片区：140x210 海报 + 聚焦放大余量（上下各 12dp 缓冲，放大后不裁切）
        FrameLayout body = new FrameLayout(this);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(236));
        bodyLp.topMargin = dp(8);
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
        recycler.setPadding(0, dp(12), 0, dp(12));
        recycler.setFocusable(true);
        recycler.setMemoryFocus(true);

        final GyPosterRowAdapter adapter = new GyPosterRowAdapter(index);
        recycler.setAdapter(adapter);

        // 卡片上的叠加信息（右上年份小标 / 评分角标 ★ 前缀）在挂载时补齐。
        // 行项绑定逻辑仍由 GyPosterRowAdapter 负责，这里只做幂等补字，不修改 adapter。
        recycler.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(View child) {
                int position = recycler.getChildAdapterPosition(child);
                List<DoubanItem> data = adapter.getData();
                if (position < 0 || data == null || position >= data.size()) {
                    return;
                }
                DoubanItem dataItem = data.get(position);

                TextView year = (TextView) child.findViewById(R.id.tv_year);
                if (year != null) {
                    if (dataItem == null || TextUtils.isEmpty(dataItem.year)) {
                        year.setVisibility(View.GONE);
                    } else {
                        year.setText(dataItem.year);
                        year.setVisibility(View.VISIBLE);
                    }
                }

                TextView rate = (TextView) child.findViewById(R.id.tv_rate);
                if (rate != null && rate.getVisibility() == View.VISIBLE) {
                    CharSequence text = rate.getText();
                    if (!TextUtils.isEmpty(text) && !text.toString().startsWith("★")) {
                        rate.setText("★ " + text);
                    }
                }
            }

            @Override
            public void onChildViewDetachedFromWindow(View child) {
                // 无需处理
            }
        });

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
            tvHeroRate.setText("★ 暂无");
            tvHeroRate.setTextColor(getResources().getColor(R.color.gy_text_dim));
        } else {
            tvHeroRate.setText("★ " + item.rate);
            tvHeroRate.setTextColor(getResources().getColor(R.color.gy_gold));
        }

        // 副行：年份 · 类型（subtitle 形如「2023 / 剧情」，两种都有时用 · 连接）
        StringBuilder meta = new StringBuilder();
        if (!TextUtils.isEmpty(item.year)) {
            meta.append(item.year);
        }
        if (!TextUtils.isEmpty(item.subtitle)) {
            if (meta.length() > 0) {
                meta.append(" · ");
            }
            meta.append(item.subtitle);
        }
        tvHeroMeta.setText(meta.length() == 0 ? "光影精选" : meta.toString());

        tvHeroIntro.setText(heroIntroText(item));
        if (TextUtils.isEmpty(item.intro)) {
            loadHeroIntroAsync(item);
        }

        // 背景（暗化模糊感由 alpha + 横向 scrim 实现）与左侧海报缩略图共用同一张封面
        String cover = com.github.tvbox.osc.douban.DoubanImage.direct(item.cover);
        if (TextUtils.isEmpty(cover)) {
            ivHeroBackdrop.setImageResource(R.drawable.bg_poster_placeholder);
            ivHeroPoster.setImageResource(R.drawable.bg_poster_placeholder);
        } else {
            Glide.with(this)
                    .load(com.github.tvbox.osc.douban.DoubanImage.req(item.cover))
                    .transition(DrawableTransitionOptions.withCrossFade(260))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(ivHeroBackdrop);
            Glide.with(this)
                    .load(com.github.tvbox.osc.douban.DoubanImage.req(item.cover))
                    .transition(DrawableTransitionOptions.withCrossFade(260))
                    .placeholder(R.drawable.bg_poster_placeholder)
                    .error(R.drawable.bg_poster_placeholder)
                    .centerCrop()
                    .into(ivHeroPoster);
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

    /** 简介最多 3 行；没有简介时退化为类型副标题，避免 Hero 出现空行。 */
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

    /** 横向暗化 scrim（左深右浅）+ 随条目换色的轻微色调，切换时 240ms 淡入。 */
    private void applyHeroTint(DoubanItem item) {
        if (heroTint == null || item == null) {
            return;
        }
        String key = (item.id == null ? "" : item.id) + "|" + (item.title == null ? "" : item.title);
        int tint = HERO_TINTS[Math.abs(key.hashCode()) % HERO_TINTS.length];
        int start = (tint & 0x00FFFFFF) | 0xE6000000;
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, 0x9906080C, 0x1A06080C});
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

    /** 一个 rail 导航项：图标 + 标签 + 聚焦辉光 + 左侧渐变指示条。 */
    private static final class RailItemHolder {
        final View root;
        final ImageView icon;
        final TextView label;
        final ImageView glow;
        final View indicator;
        final Drawable pill;

        RailItemHolder(View root, ImageView icon, TextView label, ImageView glow,
                       View indicator, Drawable pill) {
            this.root = root;
            this.icon = icon;
            this.label = label;
            this.glow = glow;
            this.indicator = indicator;
            this.pill = pill;
        }

        /** 对齐原型 .rail-item.focused：玻璃 pill + 指示条 + 图标辉光 + 文字/图标变白。 */
        void setFocused(boolean focused) {
            int color = focused ? 0xFFFFFFFF : root.getResources().getColor(R.color.gy_text_dim);
            icon.setColorFilter(color, PorterDuff.Mode.SRC_IN);
            label.setTextColor(color);
            root.setBackground(focused ? pill : null);
            indicator.setVisibility(focused ? View.VISIBLE : View.INVISIBLE);
            glow.setAlpha(focused ? 0.45f : 0f);
        }
    }
}

package com.github.tvbox.osc.update;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 轻量语义化版本解析与比较，支持：
 *   1.0.9 / v1.0.9 / 1.0.9-alpha01 / 1.0.9-beta2 / 1.0.9-rc1 / 20261030
 * 规则：
 *   - 数字段逐位比较，缺位按 0 处理；
 *   - 主版本相同时，正式版 > 预发布版（alpha < beta < rc < 正式）；
 *   - 解析失败时退化为字符串比较，保证不会抛异常。
 */
public final class SemVer implements Comparable<SemVer> {

    private final int[] nums;
    private final String pre;

    private SemVer(int[] nums, String pre) {
        this.nums = nums;
        this.pre = pre == null ? "" : pre.toLowerCase(Locale.ROOT);
    }

    public static SemVer parse(String raw) {
        if (raw == null) raw = "";
        String s = raw.trim();
        if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);

        String pre = "";
        int cut = indexOfAny(s, '-', '+');
        if (cut >= 0) {
            pre = s.substring(cut + 1);
            s = s.substring(0, cut);
        }

        String[] parts = s.split("\\.");
        List<Integer> list = new ArrayList<>();
        for (String p : parts) {
            int v = 0;
            int i = 0;
            while (i < p.length() && Character.isDigit(p.charAt(i))) i++;
            if (i > 0) {
                try {
                    v = Integer.parseInt(p.substring(0, i));
                } catch (NumberFormatException ignored) {
                    v = 0;
                }
            }
            list.add(v);
        }
        if (list.isEmpty()) list.add(0);

        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return new SemVer(arr, pre);
    }

    private static int indexOfAny(String s, char a, char b) {
        int i = s.indexOf(a);
        int j = s.indexOf(b);
        if (i < 0) return j;
        if (j < 0) return i;
        return Math.min(i, j);
    }

    @Override
    public int compareTo(SemVer o) {
        int n = Math.max(nums.length, o.nums.length);
        for (int i = 0; i < n; i++) {
            int a = i < nums.length ? nums[i] : 0;
            int b = i < o.nums.length ? o.nums[i] : 0;
            if (a != b) return a > b ? 1 : -1;
        }
        // 主版本相同：正式版 > 预发布
        if (pre.isEmpty() && o.pre.isEmpty()) return 0;
        if (pre.isEmpty()) return 1;
        if (o.pre.isEmpty()) return -1;
        return pre.compareTo(o.pre);
    }

    public boolean isNewerThan(SemVer other) {
        return other == null || compareTo(other) > 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < nums.length; i++) {
            if (i > 0) sb.append('.');
            sb.append(nums[i]);
        }
        if (!pre.isEmpty()) sb.append('-').append(pre);
        return sb.toString();
    }
}

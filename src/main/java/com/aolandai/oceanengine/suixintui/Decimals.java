package com.aolandai.oceanengine.suixintui;

import java.math.BigDecimal;

/**
 * 小数位相关的公共判断。
 *
 * <p>千川有多个字段限制「最多两位小数」（随心推的 {@code roi_goal}、乘方计划的 {@code roi2_goal}），
 * 而<b>判断小数位的写法很容易写错</b>，所以只留这一处实现。</p>
 *
 * <h2>⚠️ 为什么必须用 {@link BigDecimal#valueOf(double)}</h2>
 * <p>{@code new BigDecimal(1.7)} 会把 {@code double} 的<b>二进制精确值</b>展开成
 * {@code 1.6999999999999999555910790149937383830547332763671875} —— 小数位直接爆表，
 * 于是「最多两位小数」的校验会把<b>所有合法值都判成非法</b>。
 * {@code BigDecimal.valueOf(double)} 走的是 {@code Double.toString}，得到的是最短可读表示
 * （{@code "1.7"}），这才是业务上想要的口径。</p>
 *
 * <p>这个坑不报错、只静默把好数据拦掉，所以有单测钉死。</p>
 */
public final class Decimals {

    private Decimals() {
    }

    /**
     * 取数值的<b>有效小数位数</b>（自动去掉尾随零）。
     *
     * <p>{@code 1.7 → 1}、{@code 1.75 → 2}、{@code 1.755 → 3}、{@code 2.0 → 0}。</p>
     */
    public static int scaleOf(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().scale();
    }

    /**
     * 去掉多余的 {@code .0} 与科学计数法，用于拼提示文案。
     *
     * <p>{@code 10000.0 → "10000"}（不是 {@code "1E+4"}）。</p>
     */
    public static String plain(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}

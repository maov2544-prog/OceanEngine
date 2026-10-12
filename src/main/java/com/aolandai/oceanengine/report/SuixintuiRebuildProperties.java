package com.aolandai.oceanengine.report;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.nio.file.Path;

/**
 * 「已结束达标订单自动复建」配置，前缀 {@code suixintui.rebuild}。
 *
 * <p>复建 = 把日报抓到的「已结束且 ROI 达标」订单，按<b>原单参数照抄</b>重新建一个随心推订单。
 * 建单能力见 {@code com.aolandai.oceanengine.suixintui.SuixintuiOrderCreateService}。</p>
 *
 * <h2>⚠️ 没有 enabled 开关，也没有金额上限</h2>
 * <p>开关<b>跟随 {@code suixintui.reinvest.dry-run}</b>（老板明确要求，不引入独立开关）：
 * dry-run=true 时只组装请求体打印、不真建；dry-run=false 才真建。
 * 金额也<b>不做单日上限</b>（老板明确要求只限「每日单数」）。</p>
 *
 * <p>对应 application.yml 示例：</p>
 * <pre>
 * suixintui:
 *   rebuild:
 *     max-orders-per-day: 10
 *     record-store-path: D:/oceanengine/data/reinvest-rebuild-records.jsonl
 * </pre>
 */
@Validated
@ConfigurationProperties(prefix = "suixintui.rebuild")
public class SuixintuiRebuildProperties {

    /**
     * 每日最多<b>真复建</b>的订单数（默认 10）。
     *
     * <p>超出上限的订单<b>跳过</b>（不是终止本轮），日志里留痕。</p>
     *
     * <p>⚠️ 计数只算「真建成功」的记录（dry-run 记录不计入），与幂等判定口径一致 ——
     * 见 {@code SuixintuiRebuildRecordStore#effectiveCountOn}。</p>
     */
    @Positive
    private int maxOrdersPerDay = 10;

    /**
     * 复建幂等流水路径（JSONL）。
     *
     * <p>⚠️ 必须写<b>绝对路径</b>：相对路径会随工作目录漂移，重启后读不到已复建记录，
     * 会把已经复建过的订单<b>再复建一遍</b>（重复花钱）。</p>
     */
    @NotNull
    private Path recordStorePath = Path.of("D:/oceanengine/data/reinvest-rebuild-records.jsonl");

    public int getMaxOrdersPerDay() {
        return maxOrdersPerDay;
    }

    public void setMaxOrdersPerDay(int maxOrdersPerDay) {
        this.maxOrdersPerDay = maxOrdersPerDay;
    }

    public Path getRecordStorePath() {
        return recordStorePath;
    }

    public void setRecordStorePath(Path recordStorePath) {
        this.recordStorePath = recordStorePath;
    }

    /** 启动日志用的一句话概览 */
    public String describe() {
        return "每日最多 " + maxOrdersPerDay + " 单 / 流水 " + recordStorePath;
    }
}

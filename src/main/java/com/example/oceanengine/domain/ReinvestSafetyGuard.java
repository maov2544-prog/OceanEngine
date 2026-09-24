package com.example.oceanengine.domain;

import com.example.oceanengine.scheduler.SuixintuiReinvestProperties;
import com.example.oceanengine.storage.ReinvestRecordStore;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 追投资金安全护栏。所有金额均按“可能已经支出”计算，PENDING 和 UNKNOWN 也占用额度。
 */
public class ReinvestSafetyGuard {

    public record Decision(boolean allowed, String reason) {
        public static Decision allow() {
            return new Decision(true, "OK");
        }

        public static Decision reject(String reason) {
            return new Decision(false, reason);
        }
    }

    private final SuixintuiReinvestProperties props;
    private final ReinvestRecordStore store;

    public ReinvestSafetyGuard(SuixintuiReinvestProperties props, ReinvestRecordStore store) {
        this.props = props;
        this.store = store;
    }

    public Decision evaluate(Long advertiserId, Long orderId, long roundCommittedAmount,
                             LocalDateTime now) {
        long amount = props.getAmount();
        LocalDate today = now.toLocalDate();

        if (roundCommittedAmount + amount > props.getMaxAmountPerRound()) {
            return Decision.reject("本轮资金上限：已占用 " + roundCommittedAmount
                    + " 元，本次 " + amount + " 元，上限 " + props.getMaxAmountPerRound() + " 元");
        }

        long accountAmount = store.committedAmountOn(today, advertiserId, null);
        if (accountAmount + amount > props.getMaxAmountPerAdvertiserPerDay()) {
            return Decision.reject("账户日资金上限：已占用 " + accountAmount
                    + " 元，本次 " + amount + " 元，上限 "
                    + props.getMaxAmountPerAdvertiserPerDay() + " 元");
        }

        long orderAmount = store.committedAmountOn(today, advertiserId, orderId);
        if (orderAmount + amount > props.getMaxAmountPerOrderPerDay()) {
            return Decision.reject("订单日资金上限：已占用 " + orderAmount
                    + " 元，本次 " + amount + " 元，上限 "
                    + props.getMaxAmountPerOrderPerDay() + " 元");
        }

        long orderCount = store.committedCountOn(today, orderId);
        if (orderCount >= props.getMaxReinvestCountPerOrderPerDay()) {
            return Decision.reject("订单日追投次数上限：已占用 " + orderCount
                    + " 次，上限 " + props.getMaxReinvestCountPerOrderPerDay() + " 次");
        }

        LocalDateTime latest = store.latestCommittedAt(orderId);
        if (latest != null) {
            long elapsedMinutes = Duration.between(latest, now).toMinutes();
            if (elapsedMinutes < props.getCooldownMinutes()) {
                return Decision.reject("订单冷却中：距上次可能支出 " + elapsedMinutes
                        + " 分钟，要求至少 " + props.getCooldownMinutes() + " 分钟");
            }
        }

        return Decision.allow();
    }
}

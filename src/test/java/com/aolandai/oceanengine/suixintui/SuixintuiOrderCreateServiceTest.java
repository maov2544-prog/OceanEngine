package com.aolandai.oceanengine.suixintui;

import com.bytedance.ads.ApiException;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Request;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10Response;
import com.bytedance.ads.model.QianchuanAwemeUniPromotionOrderCreateV10ResponseData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SuixintuiOrderCreateService} 单测：注入假 fetcher，<b>绝不真建单</b>。
 *
 * <p>重点覆盖 dry-run —— 它是「花钱操作」的唯一防线。</p>
 */
class SuixintuiOrderCreateServiceTest {

    private static final long ACC = 1823379540880396L;
    private static final long AWEME_UID = 2071074583877891L;
    private static final long PRODUCT_ID = 3593275488396811650L;

    private final List<QianchuanAwemeUniPromotionOrderCreateV10Request> sent = new ArrayList<>();
    private SuixintuiOrderCreateService service;

    @BeforeEach
    void setUp() {
        service = new SuixintuiOrderCreateService(() -> request -> {
            sent.add(request);
            return okResponse(987654321L);
        });
    }

    private static SuixintuiOrderParam param() {
        return new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L, 6.0,
                "AUTO_BID", null, null);
    }

    @Test
    @DisplayName("★ dry-run：绝不调接口、orderId 为空，但请求体照常组装出来")
    void dryRunDoesNotCallApi() throws Exception {
        SuixintuiCreateOutcome outcome = service.create(param(), true);

        assertTrue(outcome.dryRun());
        assertNull(outcome.orderId(), "dry-run 不该有 orderId");
        assertFalse(outcome.created());
        assertTrue(sent.isEmpty(), "dry-run 绝不能真调接口 —— 那是花钱的");

        // 请求体必须已经组装好，否则 dry-run 就没意义（无法核对单位）
        assertTrue(outcome.requestBody().contains("30000"), outcome.requestBody());
        assertTrue(outcome.requestBody().contains("2071074583877891"), outcome.requestBody());
    }

    @Test
    @DisplayName("真建：调接口一次，返回 orderId")
    void realCreateReturnsOrderId() throws Exception {
        SuixintuiCreateOutcome outcome = service.create(param(), false);

        assertEquals(1, sent.size());
        assertFalse(outcome.dryRun());
        assertEquals(987654321L, outcome.orderId());
        assertTrue(outcome.created());
    }

    @Test
    @DisplayName("必填字段都进了请求体；可选字段留空时不传")
    void buildsRequestBody() throws Exception {
        service.create(param(), false);
        QianchuanAwemeUniPromotionOrderCreateV10Request req = sent.get(0);

        assertEquals(ACC, req.getAdvertiserId());
        assertEquals(AWEME_UID, req.getAwemeId());
        assertEquals(PRODUCT_ID, req.getProductId());
        assertEquals(30000L, req.getDeliverySetting().getAmount());
        assertEquals(6.0, req.getDeliverySetting().getDeliveryTime());
        assertEquals("AUTO_BID", String.valueOf(req.getDeliverySetting().getBidType()));
        assertNull(req.getDeliverySetting().getQcpxMode(), "留空 = 不传 qcpxMode");
        assertNull(req.getDeliverySetting().getRoiGoal(), "留空 = 不传 roiGoal");
        assertEquals("VIDEO_PROM_GOODS", String.valueOf(req.getMarketingGoal()),
                "marketing_goal 必须写死 VIDEO_PROM_GOODS");
    }

    @Test
    @DisplayName("可选字段填了就带上（⚠️ qcpxMode 的取值是 QCPX_MODE_ON，不是 ON）")
    void optionalFieldsArePassedThrough() throws Exception {
        service.create(new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L, 6.0,
                "MANUAL_BID", "QCPX_MODE_ON", 2.5), false);
        QianchuanAwemeUniPromotionOrderCreateV10Request req = sent.get(0);

        assertEquals("MANUAL_BID", String.valueOf(req.getDeliverySetting().getBidType()));
        assertEquals("QCPX_MODE_ON", String.valueOf(req.getDeliverySetting().getQcpxMode()));
        assertEquals(2.5, req.getDeliverySetting().getRoiGoal());
    }

    @Test
    @DisplayName("SDK 业务错误（code!=0）→ 抛 ApiException，不能静默当成成功")
    void businessErrorIsThrown() {
        SuixintuiOrderCreateService failing = new SuixintuiOrderCreateService(
                () -> request -> errorResponse(40000, "参数不合法"));
        ApiException e = assertThrows(ApiException.class, () -> failing.create(param(), false));
        assertTrue(e.getMessage().contains("40000"), e.getMessage());
    }

    @Test
    @DisplayName("code=0 但 data 为空 → 抛（不能当成建成）")
    void nullDataIsThrown() {
        SuixintuiOrderCreateService failing = new SuixintuiOrderCreateService(
                () -> request -> {
                    QianchuanAwemeUniPromotionOrderCreateV10Response r =
                            new QianchuanAwemeUniPromotionOrderCreateV10Response();
                    r.setCode(0L);
                    return r;
                });
        assertThrows(ApiException.class, () -> failing.create(param(), false));
    }

    @Test
    @DisplayName("★ code=0 但 orderId 为空 → 抛（无法确认是否真建成，宁可报错）")
    void missingOrderIdIsThrown() {
        SuixintuiOrderCreateService failing = new SuixintuiOrderCreateService(
                () -> request -> okResponse(null));
        ApiException e = assertThrows(ApiException.class, () -> failing.create(param(), false));
        assertTrue(e.getMessage().contains("orderId"), e.getMessage());
    }

    @Test
    @DisplayName("dry-run 不依赖 token：token 取不到也能演练（否则最重要的用法会被卡住）")
    void dryRunDoesNotNeedToken() throws Exception {
        SuixintuiOrderCreateService noToken = new SuixintuiOrderCreateService(() -> {
            throw new IllegalStateException("环境变量没配");
        });
        assertTrue(noToken.create(param(), true).dryRun());
        // 但真建时必须报错
        assertThrows(ApiException.class, () -> noToken.create(param(), false));
    }

    // ==================== 官方硬约束（2026-10-05 文档确认） ====================

    @Test
    @DisplayName("★ 预算不是 10 的倍数 → 抛 SuixintuiParamException(code=2)，且绝不调接口")
    void amountNotMultipleOfTenIsRejected() {
        SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 105L, 6.0,
                "AUTO_BID", null, null);
        SuixintuiParamException e =
                assertThrows(SuixintuiParamException.class, () -> service.create(bad, false));
        assertEquals(2, e.errorCode());
        assertTrue(e.getMessage().contains("105"), e.getMessage());
        assertTrue(sent.isEmpty(), "参数不合法绝不能真的发出去");
    }

    @Test
    @DisplayName("★ 预算越界（99 / 5000010）→ 抛 SuixintuiParamException")
    void amountOutOfRangeIsRejected() {
        for (long amount : new long[]{99L, 5_000_010L}) {
            SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, amount,
                    6.0, "AUTO_BID", null, null);
            assertThrows(SuixintuiParamException.class, () -> service.create(bad, false),
                    "amount=" + amount);
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ 时长不在白名单（36 小时）→ 抛（不是范围，是白名单）")
    void deliveryTimeNotWhitelistedIsRejected() {
        SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L, 36.0,
                "AUTO_BID", null, null);
        SuixintuiParamException e =
                assertThrows(SuixintuiParamException.class, () -> service.create(bad, false));
        assertEquals(2, e.errorCode());
        assertTrue(e.getMessage().contains("36"), e.getMessage());
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ dry-run 也拦非法参数 —— 否则演练会打印一个「真跑必被拒」的请求体（假绿灯）")
    void dryRunAlsoRejectsInvalidParam() {
        SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L, 3.0,
                "AUTO_BID", null, null);
        assertThrows(SuixintuiParamException.class, () -> service.create(bad, true));
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("合法边界值放行：100 元 / 168 小时")
    void boundaryValuesAreAccepted() throws Exception {
        service.create(new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 100L, 168.0,
                "AUTO_BID", null, null), false);
        assertEquals(1, sent.size());
        assertEquals(100L, sent.get(0).getDeliverySetting().getAmount());
        assertEquals(168.0, sent.get(0).getDeliverySetting().getDeliveryTime());
    }

    @Test
    @DisplayName("★ MANUAL_BID 但没给 roi_goal → 抛（官方：条件必填）")
    void manualBidWithoutRoiGoalIsRejected() {
        SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L, 6.0,
                "MANUAL_BID", null, null);
        SuixintuiParamException e =
                assertThrows(SuixintuiParamException.class, () -> service.create(bad, false));
        assertEquals(2, e.errorCode());
        assertTrue(e.getMessage().contains("roi_goal"), e.getMessage());
        assertTrue(sent.isEmpty(), "参数不合法绝不能真的发出去");
    }

    @Test
    @DisplayName("★ roi_goal 越界 / 超过两位小数 → 抛")
    void roiGoalOutOfRangeOrTooPreciseIsRejected() {
        for (double roi : new double[]{0.0, 10_000.5, 1.755}) {
            SuixintuiOrderParam bad = new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 30000L,
                    6.0, "MANUAL_BID", null, roi);
            assertThrows(SuixintuiParamException.class, () -> service.create(bad, false),
                    "roi_goal=" + roi);
        }
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("★ MANUAL_BID + roi_goal=1.7（老板定的值）→ 正常带上")
    void manualBidWithRoiGoalIsPassedThrough() throws Exception {
        service.create(new SuixintuiOrderParam(ACC, AWEME_UID, PRODUCT_ID, 200L, 24.0,
                "MANUAL_BID", null, 1.7), false);
        assertEquals(1, sent.size());
        assertEquals("MANUAL_BID", String.valueOf(sent.get(0).getDeliverySetting().getBidType()));
        assertEquals(1.7, sent.get(0).getDeliverySetting().getRoiGoal());
    }

    // ==================== 工具 ====================

    private static QianchuanAwemeUniPromotionOrderCreateV10Response okResponse(Long orderId) {
        QianchuanAwemeUniPromotionOrderCreateV10ResponseData data =
                new QianchuanAwemeUniPromotionOrderCreateV10ResponseData();
        data.setOrderId(orderId);
        QianchuanAwemeUniPromotionOrderCreateV10Response response =
                new QianchuanAwemeUniPromotionOrderCreateV10Response();
        response.setCode(0L);
        response.setData(data);
        return response;
    }

    private static QianchuanAwemeUniPromotionOrderCreateV10Response errorResponse(int code,
                                                                                  String message) {
        QianchuanAwemeUniPromotionOrderCreateV10Response response =
                new QianchuanAwemeUniPromotionOrderCreateV10Response();
        response.setCode((long) code);
        response.setMessage(message);
        return response;
    }
}

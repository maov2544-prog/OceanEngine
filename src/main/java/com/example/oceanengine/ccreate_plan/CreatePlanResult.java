package com.example.oceanengine.ccreate_plan;

import com.bytedance.ads.model.QianchuanOverallVideoCreateV10Response;

/**
 * 「新建乘方商品投放计划」归一化返回结果。
 *
 * <p>屏蔽 SDK 返回结构，业务侧只需要看这几个字段。</p>
 */
public class CreatePlanResult {

    /** 是否创建成功（code == 0） */
    private boolean success;
    /** 平台返回码，0 表示成功，详见【附录-返回码】 */
    private Long code;
    /** 平台返回信息 */
    private String message;
    /** 创建出来的计划 id，失败时为 null */
    private Long adId;
    /** 请求日志 id，排查问题时报给平台 */
    private String requestId;

    /** 由 SDK 响应构造。 */
    public static CreatePlanResult from(QianchuanOverallVideoCreateV10Response response) {
        CreatePlanResult result = new CreatePlanResult();
        if (response == null) {
            result.setSuccess(false);
            result.setMessage("接口无响应");
            return result;
        }
        result.setCode(response.getCode());
        result.setMessage(response.getMessage());
        result.setRequestId(response.getRequestId());
        result.setSuccess(response.getCode() != null && response.getCode() == 0L);
        if (response.getData() != null) {
            result.setAdId(response.getData().getAdId());
        }
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public Long getCode() {
        return code;
    }

    public void setCode(Long code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Long getAdId() {
        return adId;
    }

    public void setAdId(Long adId) {
        this.adId = adId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    @Override
    public String toString() {
        return "CreatePlanResult{success=" + success
                + ", code=" + code
                + ", message='" + message + '\''
                + ", adId=" + adId
                + ", requestId='" + requestId + '\'' + '}';
    }
}

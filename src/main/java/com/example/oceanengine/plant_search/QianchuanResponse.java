package com.example.oceanengine.plant_search;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * 千川API统一返回结构
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class QianchuanResponse<T> {

    @JsonProperty("code")
    private Integer code;

    @JsonProperty("message")
    private String message;

    @JsonProperty("data")
    private T data;

    @JsonProperty("request_id")
    private String requestId;

    /**
     * 是否请求成功
     */
    public boolean isSuccess() {
        return code != null && code == 0;
    }

    // ========== 投放计划列表响应体 ==========

    @Data
    public static class PromotionListData {
        @JsonProperty("ad_list")
        private List<PromotionPlanDTO> adList;

        @JsonProperty("page_info")
        private PageInfo pageInfo;
    }

    @Data
    public static class PageInfo {
        @JsonProperty("page")
        private Integer page;

        @JsonProperty("page_size")
        private Integer pageSize;

        @JsonProperty("total_page")
        private Integer totalPage;

        @JsonProperty("total_num")
        private Integer totalNum;
    }
}

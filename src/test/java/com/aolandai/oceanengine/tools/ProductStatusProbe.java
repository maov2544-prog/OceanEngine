package com.aolandai.oceanengine.tools;

import com.aolandai.oceanengine.client.SuixintuiTokenClient;
import com.bytedance.ads.ApiClient;
import com.bytedance.ads.ApiException;
import com.bytedance.ads.api.QianchuanAwemeProductAvailableGetV10Api;
import com.bytedance.ads.api.QianchuanProductAvailableGetV10Api;
import com.bytedance.ads.api.QianchuanUniPromotionProductGetV10Api;
import com.bytedance.ads.model.QianchuanAwemeProductAvailableGetV10Response;
import com.bytedance.ads.model.QianchuanAwemeProductAvailableGetV10ResponseDataProductListInner;
import com.bytedance.ads.model.QianchuanProductAvailableGetV10CampaignScene;
import com.bytedance.ads.model.QianchuanProductAvailableGetV10Filter;
import com.bytedance.ads.model.QianchuanProductAvailableGetV10FilterTab;
import com.bytedance.ads.model.QianchuanProductAvailableGetV10Response;
import com.bytedance.ads.model.QianchuanProductAvailableGetV10ResponseDataProductListInner;
import com.bytedance.ads.model.QianchuanUniPromotionProductGetV10Filtering;
import com.bytedance.ads.model.QianchuanUniPromotionProductGetV10FilteringTab;
import com.bytedance.ads.model.QianchuanUniPromotionProductGetV10Platfrom;
import com.bytedance.ads.model.QianchuanUniPromotionProductGetV10Response;
import com.bytedance.ads.model.QianchuanUniPromotionProductGetV10ResponseDataProductListInner;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

/**
 * <b>只读</b>探针：排查商品 {@value #PRODUCT_ID} 当前<b>是否可推广</b>
 * （是否被下架 / 无库存 / 审核未过），以及账户 {@value #ADVERTISER_ID} 当前
 * <b>可推广商品总数</b>。
 *
 * <p><b>绝对只读</b>：只调用 SDK 的 GET 查询接口，<b>不下单、不改状态、不花钱</b>，
 * 不触碰生产代码，不写任何业务流水。</p>
 *
 * <h2>为什么要查（背景）</h2>
 * <p>上一轮真复建订单 {@code 7695246925897072690}（商品即本商品）时，千川建单接口
 * 返回 {@code code=40000 message=商品校验不通过}。本探针用只读接口反查根因：
 * 商品是否已下架 / 无库存 / 审核未过，从而判断是「商品问题」还是「参数问题」。</p>
 *
 * <h2>查询的三条路径（互为交叉验证）</h2>
 * <ol>
 *   <li><b>账户可推广商品列表（主）</b>：{@link QianchuanProductAvailableGetV10Api}
 *       —— 用 {@code filter.productIds} 精确查目标商品是否在可推广列表里；
 *       再用 {@code tab=ALL} 拉全量拿到 {@code pageInfo.totalNumber}（账户可推广商品总数）。</li>
 *   <li><b>达人可推广商品（辅）</b>：{@link QianchuanAwemeProductAvailableGetV10Api}
 *       —— 按达人维度查目标商品是否可投。</li>
 *   <li><b>随心推商品库（辅）</b>：{@link QianchuanUniPromotionProductGetV10Api}
 *       —— 带 {@code stockNum}/{@code auditTime} 字段，可看到库存与审核时间。</li>
 * </ol>
 *
 * <h2>运行（PowerShell / cmd；Git Bash 跑不了 Maven）</h2>
 * <pre>
 * cd D:\program\aolandai\OceanEngine
 * mvn -o -q test-compile
 * mvn -o -q dependency:build-classpath "-Dmdep.outputFile=target\cp.txt"
 * $cp = Get-Content target\cp.txt -Raw
 * java -cp "target\classes;target\test-classes;$cp" com.aolandai.oceanengine.tools.ProductStatusProbe
 * # 可覆盖参数：--productId=... --advertiserId=... --awemeId=... --out=...
 * </pre>
 */
public final class ProductStatusProbe {

    /** 目标商品 ID（原订单 7695246925897072690 的商品） */
    private static final long PRODUCT_ID = 3502541670170374206L;

    /** 目标账户：原单真实归属账户（官方【孕产-商品】，10000 档） */
    private static final long ADVERTISER_ID = 1771298760893511L;

    /** 原单达人 UID（aweme_info.aweme_id），辅查用 */
    private static final long AWEME_ID = 1888306021535466L;

    private static final String DEFAULT_OUTPUT_PATH = "target/product-status-3502541670170374206.txt";

    private static final String API_BASE_URL = "https://api.oceanengine.com";

    /** 全量列表单页取 100（只为拿 totalNumber，取第一页即可） */
    private static final int PAGE_SIZE = 100;

    private ProductStatusProbe() {
    }

    public static void main(String[] args) {
        long productId = PRODUCT_ID;
        long advertiserId = ADVERTISER_ID;
        long awemeId = AWEME_ID;
        String outputPath = DEFAULT_OUTPUT_PATH;
        for (String arg : args) {
            if (arg == null || arg.isBlank()) {
                continue;
            }
            String a = arg.trim();
            if (a.startsWith("--productId=")) {
                productId = Long.parseLong(a.substring("--productId=".length()).trim());
            } else if (a.startsWith("--advertiserId=")) {
                advertiserId = Long.parseLong(a.substring("--advertiserId=".length()).trim());
            } else if (a.startsWith("--awemeId=")) {
                awemeId = Long.parseLong(a.substring("--awemeId=".length()).trim());
            } else if (a.startsWith("--out=")) {
                outputPath = a.substring("--out=".length());
            } else if ("--help".equals(a) || "-h".equals(a)) {
                printUsage();
                return;
            }
        }

        StringBuilder report = new StringBuilder();
        int exitCode = run(productId, advertiserId, awemeId, report);
        System.out.print(report);
        writeOutput(outputPath, report);
        System.exit(exitCode);
    }

    private static int run(long productId, long advertiserId, long awemeId, StringBuilder report) {
        report.append("=== 商品可推广性【只读】排查 ===\n");
        report.append("目标商品 : ").append(productId).append("\n");
        report.append("账户     : ").append(advertiserId).append("\n");
        report.append("达人UID  : ").append(awemeId).append("（仅辅查）\n");
        report.append("说明     : 全程只读，不下单、不花钱、不改生产代码。\n\n");

        String token;
        ApiClient apiClient;
        try {
            token = SuixintuiTokenClient.getAccessToken();
            apiClient = new ApiClient();
            apiClient.setBasePath(API_BASE_URL);
            apiClient.addDefaultHeader("Access-Token", token);
        } catch (Exception e) {
            report.append("[致命] 获取 Access Token 失败: ")
                    .append(e.getClass().getSimpleName()).append(": ").append(e.getMessage()).append("\n");
            return 1;
        }

        boolean anySuccess = false;

        // ---- 路径 1：账户可推广商品列表（精确查 + 全量计数）----
        anySuccess |= queryAccountAvailable(apiClient, advertiserId, productId, report);

        // ---- 路径 2：达人可推广商品 ----
        anySuccess |= queryAwemeAvailable(apiClient, advertiserId, awemeId, productId, report);

        // ---- 路径 3：随心推商品库（库存 / 审核时间）----
        anySuccess |= queryUniPromotionProduct(apiClient, advertiserId, productId, awemeId, report);

        report.append("\n==================== 结论 ====================\n");
        report.append("见上面各路径原始输出；任何一条查到即为客观事实。\n");
        report.append("\n[判定] 若『路径1.1 精确查』命中且有库存 → 商品【当前可推广、未下架】，\n");
        report.append("       则上一轮建单 code=40000『商品校验不通过』【不是】下架/无库存导致，\n");
        report.append("       需转查其它原因（如商品与订单场景/达人授权/商品资质/参数一致性等）。\n");

        return anySuccess ? 0 : 1;
    }

    // ==================================================================
    // 路径 1：QianchuanProductAvailableGetV10 —— 账户可推广商品列表
    // ==================================================================
    private static boolean queryAccountAvailable(ApiClient apiClient, long advertiserId,
                                                 long productId, StringBuilder report) {
        report.append("========== 路径1：账户可推广商品列表（QianchuanProductAvailableGetV10Api） ==========\n");
        report.append("接口全名: com.bytedance.ads.api.QianchuanProductAvailableGetV10Api\n");
        report.append("方法    : openApiV10QianchuanProductAvailableGetGet(advertiserId, ., filter, campaignScene, page, pageSize, .)\n\n");
        QianchuanProductAvailableGetV10Api api = new QianchuanProductAvailableGetV10Api(apiClient);
        boolean ok = false;

        // 1.1 精确查目标商品
        report.append("---- 1.1 精确查商品 ").append(productId).append(" 是否在可推广列表中 ----\n");
        try {
            QianchuanProductAvailableGetV10Filter filter = new QianchuanProductAvailableGetV10Filter()
                    .productIds(Collections.singletonList(productId))
                    .tab(QianchuanProductAvailableGetV10FilterTab.ALL);
            QianchuanProductAvailableGetV10Response resp = api
                    .openApiV10QianchuanProductAvailableGetGet(
                            advertiserId, null, filter,
                            QianchuanProductAvailableGetV10CampaignScene.DAILY_SALE,
                            1, PAGE_SIZE, null);
            ok = true;
            report.append("code=").append(resp.getCode())
                    .append(" message=").append(resp.getMessage())
                    .append(" requestId=").append(resp.getRequestId()).append("\n");
            if (resp.getData() != null) {
                Integer total = resp.getData().getPageInfo() == null
                        ? null : intOrNull(resp.getData().getPageInfo().getTotalNumber());
                report.append("命中 totalNumber = ").append(total).append("\n");
                List<QianchuanProductAvailableGetV10ResponseDataProductListInner> list =
                        resp.getData().getProductList();
                if (list == null || list.isEmpty()) {
                    report.append("⚠️ 精确查询结果为空 → 该商品【不在】账户当前可推广列表中。\n");
                } else {
                    report.append("✅ 命中 ").append(list.size()).append(" 条，原始字段：\n");
                    for (QianchuanProductAvailableGetV10ResponseDataProductListInner p : list) {
                        report.append("  id=").append(p.getId())
                                .append(", name=").append(p.getName())
                                .append(", categoryName=").append(p.getCategoryName())
                                .append(", channelType=").append(p.getChannelType())
                                .append(", inventory=").append(p.getInventory())
                                .append(", sellNum=").append(p.getSellNum())
                                .append(", firstOnShelfTime=").append(p.getFirstOnShelfTime())
                                .append(", saleTime=").append(p.getSaleTime())
                                .append(", productRate=").append(p.getProductRate())
                                .append(", tags=").append(p.getTags())
                                .append("\n");
                    }
                }
                report.append("完整 data JSON:\n").append(resp.getData().toJson()).append("\n");
            }
        } catch (ApiException e) {
            report.append("[接口异常] code=").append(e.getCode())
                    .append(" message=").append(e.getMessage()).append("\n");
        } catch (Exception e) {
            report.append("[异常] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }

        // 1.2 全量拉一页，拿账户可推广商品总数
        report.append("\n---- 1.2 账户可推广商品总数（tab=ALL，第1页）----\n");
        try {
            QianchuanProductAvailableGetV10Filter filter = new QianchuanProductAvailableGetV10Filter()
                    .tab(QianchuanProductAvailableGetV10FilterTab.ALL);
            QianchuanProductAvailableGetV10Response resp = api
                    .openApiV10QianchuanProductAvailableGetGet(
                            advertiserId, null, filter,
                            QianchuanProductAvailableGetV10CampaignScene.DAILY_SALE,
                            1, PAGE_SIZE, null);
            ok = true;
            report.append("code=").append(resp.getCode())
                    .append(" message=").append(resp.getMessage()).append("\n");
            if (resp.getData() != null && resp.getData().getPageInfo() != null) {
                report.append("★ 账户可推广商品总数 totalNumber = ")
                        .append(resp.getData().getPageInfo().getTotalNumber()).append("\n");
                report.append("   totalPage=").append(resp.getData().getPageInfo().getTotalPage())
                        .append(" pageSize=").append(resp.getData().getPageInfo().getPageSize()).append("\n");
                // 顺带核对目标商品是否出现在本页
                List<QianchuanProductAvailableGetV10ResponseDataProductListInner> list =
                        resp.getData().getProductList();
                if (list != null) {
                    boolean found = list.stream().anyMatch(p -> Long.valueOf(productId).equals(p.getId()));
                    report.append("   本页(").append(list.size()).append("条)中包含目标商品? ")
                            .append(found ? "是" : "否（可能不在前 " + PAGE_SIZE + " 条）").append("\n");
                }
            }
        } catch (ApiException e) {
            report.append("[接口异常] code=").append(e.getCode())
                    .append(" message=").append(e.getMessage()).append("\n");
        } catch (Exception e) {
            report.append("[异常] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n");
        }
        report.append("\n");
        return ok;
    }

    // ==================================================================
    // 路径 2：QianchuanAwemeProductAvailableGetV10 —— 达人可推广商品
    // ==================================================================
    private static boolean queryAwemeAvailable(ApiClient apiClient, long advertiserId, long awemeId,
                                               long productId, StringBuilder report) {
        report.append("========== 路径2：达人可推广商品（QianchuanAwemeProductAvailableGetV10Api） ==========\n");
        report.append("接口全名: com.bytedance.ads.api.QianchuanAwemeProductAvailableGetV10Api\n");
        report.append("方法    : openApiV10QianchuanAwemeProductAvailableGetGet(advertiserId, awemeId, pageSize)\n\n");
        try {
            QianchuanAwemeProductAvailableGetV10Api api = new QianchuanAwemeProductAvailableGetV10Api(apiClient);
            QianchuanAwemeProductAvailableGetV10Response resp =
                    api.openApiV10QianchuanAwemeProductAvailableGetGet(
                            advertiserId, awemeId, (long) PAGE_SIZE);
            report.append("code=").append(resp.getCode())
                    .append(" message=").append(resp.getMessage()).append("\n");
            if (resp.getData() != null) {
                List<QianchuanAwemeProductAvailableGetV10ResponseDataProductListInner> list =
                        resp.getData().getProductList();
                int size = list == null ? 0 : list.size();
                report.append("达人可推广商品数(本页) = ").append(size).append("\n");
                if (list != null) {
                    for (QianchuanAwemeProductAvailableGetV10ResponseDataProductListInner p : list) {
                        if (Long.valueOf(productId).equals(p.getId())) {
                            report.append("✅ 目标商品在达人可推广列表中: id=").append(p.getId())
                                    .append(", name=").append(p.getName())
                                    .append(", inventory=").append(p.getInventory())
                                    .append(", categoryName=").append(p.getCategoryName())
                                    .append(", channelType=").append(p.getChannelType())
                                    .append(", productRate=").append(p.getProductRate())
                                    .append(", supportProductNewOpen=").append(p.getSupportProductNewOpen())
                                    .append("\n");
                        }
                    }
                }
                report.append("完整 data JSON:\n").append(resp.getData().toJson()).append("\n");
            }
            report.append("\n");
            return true;
        } catch (ApiException e) {
            report.append("[接口异常] code=").append(e.getCode())
                    .append(" message=").append(e.getMessage()).append("\n\n");
            return false;
        } catch (Exception e) {
            report.append("[异常] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n\n");
            return false;
        }
    }

    // ==================================================================
    // 路径 3：QianchuanUniPromotionProductGetV10 —— 随心推商品库(库存/审核)
    // ==================================================================
    private static boolean queryUniPromotionProduct(ApiClient apiClient, long advertiserId,
                                                    long productId, long awemeId, StringBuilder report) {
        report.append("========== 路径3：随心推商品库（QianchuanUniPromotionProductGetV10Api） ==========\n");
        report.append("接口全名: com.bytedance.ads.api.QianchuanUniPromotionProductGetV10Api\n");
        report.append("方法    : openApiV10QianchuanUniPromotionProductGetGet(advertiserId, filtering, awemeId, ., ., ., page, pageSize, ., platform)\n\n");
        try {
            QianchuanUniPromotionProductGetV10Api api = new QianchuanUniPromotionProductGetV10Api(apiClient);
            QianchuanUniPromotionProductGetV10Filtering filtering =
                    new QianchuanUniPromotionProductGetV10Filtering()
                            .productIds(Collections.singletonList(productId))
                            .tab(QianchuanUniPromotionProductGetV10FilteringTab.ALL);
            QianchuanUniPromotionProductGetV10Response resp =
                    api.openApiV10QianchuanUniPromotionProductGetGet(
                            advertiserId, filtering, awemeId, null, null, null,
                            1, PAGE_SIZE, null,
                            QianchuanUniPromotionProductGetV10Platfrom.QIANCHUAN);
            report.append("code=").append(resp.getCode())
                    .append(" message=").append(resp.getMessage()).append("\n");
            if (resp.getData() != null) {
                List<QianchuanUniPromotionProductGetV10ResponseDataProductListInner> list =
                        resp.getData().getProductList();
                if (list == null || list.isEmpty()) {
                    report.append("⚠️ 随心推商品库未命中该商品 → 该商品当前【不在】随心推可选商品中。\n");
                } else {
                    for (QianchuanUniPromotionProductGetV10ResponseDataProductListInner p : list) {
                        report.append("  id=").append(p.getId())
                                .append(", name=").append(p.getName())
                                .append(", categoryName=").append(p.getCategoryName())
                                .append(", channelType=").append(p.getChannelType())
                                .append(", stockNum=").append(p.getStockNum())
                                .append(", sellNum=").append(p.getSellNum())
                                .append(", auditTime=").append(p.getAuditTime())
                                .append(", tag=").append(p.getTag())
                                .append(", grayReason=").append(p.getGrayReason())
                                .append(", hasRelatedStarTask=").append(p.getHasRelatedStarTask())
                                .append("\n");
                    }
                }
                report.append("完整 data JSON:\n").append(resp.getData().toJson()).append("\n");
            }
            report.append("\n");
            return true;
        } catch (ApiException e) {
            report.append("[接口异常] code=").append(e.getCode())
                    .append(" message=").append(e.getMessage()).append("\n\n");
            return false;
        } catch (Exception e) {
            report.append("[异常] ").append(e.getClass().getSimpleName())
                    .append(": ").append(e.getMessage()).append("\n\n");
            return false;
        }
    }

    private static Integer intOrNull(Long v) {
        return v == null ? null : v.intValue();
    }

    private static void writeOutput(String outputPath, StringBuilder report) {
        try {
            Path out = Paths.get(outputPath);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                w.write(report.toString());
            }
            System.out.println();
            System.out.println("（完整报告已写入 UTF-8 文件：" + out.toAbsolutePath() + "）");
        } catch (Exception e) {
            System.out.println();
            System.out.println("（写输出文件失败，忽略："
                    + e.getClass().getSimpleName() + ": " + e.getMessage() + "）");
        }
    }

    private static void printUsage() {
        System.out.println("用法: ProductStatusProbe [--productId=...] [--advertiserId=...] [--awemeId=...] [--out=...]");
        System.out.println();
        System.out.println("默认: productId=" + PRODUCT_ID
                + " advertiserId=" + ADVERTISER_ID + " awemeId=" + AWEME_ID);
        System.out.println("只读探针：只调 GET 查询接口，不下单、不花钱、不改生产代码。");
    }
}

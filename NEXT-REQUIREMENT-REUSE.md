# 新需求复用评估（2026-09-29）

> 对照对象：现有「随心推追投服务」工程（`com.aolandai:oceanengine:1.0-SNAPSHOT`）。
> 本文只回答一件事：**新需求哪些能直接用、哪些要照抄模式、哪些必须重写。**

---

## 一、需求复述

读取【定投素材表】+【历史随心推订单存量表】，按 **【达人 + 商品】** 维度合并统计累计随心推计划总笔数 N；
同时用接口校验：该【达人 + 商品】**是否已存在对应的千川计划**。

| 场景 | 条件 | 动作 |
|---|---|---|
| **A** | N < 3 | RPA 新建随心推计划；投放完成后写入定投素材表 |
| **B** | N ≥ 3 | 最后一条随心推计划投放结束后，在对应投放账户**新建千川计划**，添加**所有**视频素材；命名 `抖音号 - 商品 - 新建计划日期 MD` |
| **C1** | N > 3，**已存在**对应千川计划 | 在该计划**新增本条视频素材**，更新计划名称，日期改为最新 `M.D` |
| **C2** | N > 3，**不存在**对应千川计划 | **新建**千川计划，添加所有视频素材；命名同上 |

---

## 二、⚠️ 开工前必须先澄清的 4 个逻辑问题

### ① B 与 C 的条件重叠

B 写的是 `≥ 3`，C 写的是 `> 3` —— **`>3` 是 `≥3` 的子集**，N=4 时同时命中 B 和 C。

推测本意：**B 应是 `== 3`（正好第 3 笔，首次建千川）**，C 是 `> 3`。请确认。

### ② B 与 C2 的动作看起来完全相同

两者都是「新建千川计划 + 加所有视频素材 + 同样命名」，差别只在触发条件。
→ 建议合并为**同一个动作**，只在触发条件上区分（B: N 刚跨过 3；C2: 历史存量就 >3 但从没建过）。
**请确认 B 和 C2 是否真的没有任何业务差异**（比如投放设置、预算、定向不同）。

### ③ `M.D` 格式的含义

- **`M.D` 有没有前导零？** `9.5` 还是 `09.05`？还是 `9.05`？
- **C1 更新时改成的「最新」日期指什么？** 本次新增素材的日期？还是原计划创建日期？
- **跨年歧义**：`1.5` 是 2026 年 1 月 5 日还是 2027 年？命名里只有月日，没有年份 —— 如果计划要跨年存活，**从名字无法判断年份**。

### ④ N 的「累计笔数」口径

- 是**全历史累计**，还是**某个时间窗内**（近 N 天 / 近 N 月）？
- **「笔数」= 订单数，还是追投次数？** ⚠️ 现有系统里**一个订单可以追投多次**（日上限 6 次），
  这两个口径差好几倍。新需求的「随心推计划总笔数」听起来像**订单数**，但需要你确认。

---

## 三、可直接复用（几乎不用改）

### 1. 千川 API 接入层 —— ★★★★★ 复用度最高

**`client/QianchuanTokenClient.java` 已经存在**，它就是拿「千川 PC 版 Access Token」的
（`state=shop_qianchuan`，与随心推的 `SuixintuiTokenClient` 共用 `QIANCHUAN_SERVICE_BEARER_TOKEN` 环境变量）。
**新需求要建千川计划，token 这一环完全不用重写。**

`client/ApiErrors.java` + `client/RateLimitException.java` 是**逐字可复用**的：
三层频控（客户 `40130` / 开发者 `40110` / 全局 `40100`）+ 5 分钟惩罚 + backoff 常量，
千川 API 与随心推是同一套错误码体系。

**依赖 `oceanengine-mapi-java-client:1.1.96` 里已包含新需求要用的全部 API**（已在 jar 内逐一确认）：

| 新需求动作 | 现成 API 类 |
|---|---|
| 校验是否已有对应千川计划 | `QianchuanAdGetV10Api`、`QianchuanAdDetailGetV10Api` |
| 新建千川计划 | `QianchuanAdCreateV10Api` |
| 更新计划名称 | `QianchuanAdUpdateV10Api` |
| 给**已有**计划加素材 | `QianchuanUniPromotionAdMaterialAddV10Api` |
| 查计划已挂素材 | `QianchuanUniPromotionAdMaterialGetV10Api`、`QianchuanAdMaterialGetV10Api` |
| 按抖音号取视频素材 | `QianchuanFileVideoAwemeGetV10Api`、`QianchuanVideoByAwemeGetV10Api` |
| 商品信息 | `QianchuanUniPromotionProductGetV10Api`、`QianchuanProductAvailableGetV10Api` |
| 达人（授权抖音号） | `QianchuanAwemeAuthorizedGetV10Api` —— **项目里已在用**（见 `SuixintuiUniOrderByAwemeService`） |
| 计划投放日期 | `QianchuanAdScheduleDateUpdateV10Api` |
| 计划启停 | `QianchuanAdStatusUpdateV10Api` |

→ **不需要引新依赖，也不需要自己写 HTTP 调用。**

⚠️ **待确认**：新需求的「千川计划」是**全域推广（UniPromotion）**还是**普通计划**？
现有工程做的是「随心推全域-商品订单」，所以 `QianchuanUniPromotionAdMaterialAddV10Api` 可能才是对的；
但新建用的是 `QianchuanAdCreateV10Api`（普通计划）。这两个类型不一样，**建错了后面加素材会失败**。

### 2. Excel 读写 —— ★★★★☆

- `report/DailyReinvestReportService.java`（271 行）：读模板 → 填数据 → 另存。
  用的是 `WorkbookFactory.create(in)`，**格式无关**（`.xls`/`.xlsx` 都吃）。
  其中「订单ID / 抖音号必须写成**文本格式 `@`**」的写法可直接照抄（否则 Excel 丢 19 位精度）。
- `report/ReinvestReportProperties.java`（279 行）：路径 + 命名模板（`{month}追投日报`、`{date}追投明细表.xls`）
  的**占位符展开机制**，正好可用来定位【定投素材表】。
- `tools/XlsDumpTool.java`：打印任意 `.xls` 每个单元格的**类型**，**摸清新表结构时直接能用**。

⚠️ **两个坑**：
1. 现有依赖只有 `poi`（**HSSF，只支持 `.xls`**）。若新表是 **`.xlsx`**，必须加 `poi-ooxml` 依赖。
2. 现有是「**读模板 → 另存新文件**」。新需求要「**投放完成后写入定投素材表**」＝
   **原地追加/更新行**，这是**另一种模式**，需要新写（不能直接复用）。

### 3. 幂等与状态存储 —— ★★★★☆（照抄模式）

新需求**一定需要幂等**（避免重复建千川计划、重复加素材），项目里两种取舍都已实现过：

| 类 | 策略 | 适用 |
|---|---|---|
| `storage/ReinvestRecordStore.java`（320 行） | JSONL 全量重写 + 内存索引 + **损坏即拒绝启动** | 数据损坏会导致**重复扣费**的场景 |
| `webhook/WebhookNotificationStore.java` | JSONL 追加写 + **跳过坏行、不拒绝启动** | 数据损坏最多导致**重复发一次**的场景 |

选一种照抄即可（判断标准是「损坏的代价 vs 停摆的代价」，见 `REFERENCE.md`）。

⚠️ **`storage/ReinvestRecord.java` 没有「商品」字段**（现有字段：
`roundId / advertiserId / orderId / amount / deliveryTime / preTotalBudget / awemeName / awemeShowId / status / message / timestamp`）。
新需求是【达人 + 商品】维度 → **要么扩展流水，要么从存量表读**。

**✅ 好消息：两个维度的 key 都能从「现有已经在调」的订单详情接口拿到。**
`QianchuanAwemeUniPromotionOrderDetailV10ResponseData` 里（已用 `javap` 逐一确认）：

| 需要的东西 | 字段 | 说明 |
|---|---|---|
| 达人（抖音号） | `awemeInfo.awemeShowId` | 也可用 `awemeId` / `awemeName` |
| **商品** | `productInfo.id` + `productInfo.name` | **`id` 是稳定的数值主键** |
| 随心推计划 ID | `adId` | 一个订单对应一个随心推计划 |
| 订单创建时间 | `orderCreateTime` | 可用于时间窗口径 |

→ 所以**不需要新接口**就能建【达人 + 商品】维度，只是**当前代码没把这些字段落盘**。

⚠️ **强烈建议用 `productInfo.id` 做聚合主键，不要用商品名。**
商品名会改、可能有空格/`-`（会影响千川计划名的解析），而 `id` 稳定。
名字只用于「千川计划命名」和展示。


### 4. 调度与并发 —— ★★★★★

- `scheduler/SuixintuiReinvestScheduler.java`：`@Scheduled` + **`FileChannel.tryLock()` 单实例轮次锁**
- `dry-run` / `run-once` 开关（本地验证利器）
- `scheduler/ReinvestHealthIndicator.java`：actuator health

### 5. 配置与启动范式 —— ★★★★★

- `scheduler/SuixintuiReinvestProperties.java`（595 行）：`@ConfigurationProperties` +
  **`@AssertTrue` 启动自检** + **`describeXxx()` 启动横幅** —— 这套范式直接复制。
  横幅「打实际生效值」是排查配置问题的关键手段，务必保留。
- `launcher/ReinvestConfig.java`、`launcher/ReinvestApplication.java`：Bean 装配 + 启动横幅。

### 6. 部署与运维 —— ★★★★★

- `deploy/*.cmd` + `oceanengine-service.xml`（WinSW 注册 Windows 服务）
- `DEPLOYMENT-WINDOWS.md`（含日志分工、排障、整包拷贝审计清单）
- 日志体系：业务行在 `.out.log`（不是 `application.log`）
- 沙箱/Windows 踩坑经验（`REFERENCE.md`）

### 7. 调试工具 —— ★★★★★

- `tools/XlsDumpTool.java`（Excel 结构核对）
- `tools/WebhookProbe.java` + `tools/webhook_receiver.py`（webhook 联调）
- 一批只有 `main()` 的手工探针（`SuixintuiAccountProbeTest` 等）

---

## 四、必须全新开发（无可复用）

| # | 事项 | 说明 |
|---|---|---|
| 1 | **RPA 集成**（A 场景） | 现有是直接调 API 加预算，**没有 RPA 能力**。需确认 RPA 是什么平台（影刀？之前 webhook 就是发给影刀的） |
| 2 | **【达人 + 商品】聚合模型** | 现有是「订单」维度，需新建（数据源已有，见上） |
| 3 | **商品/达人字段落盘** | 订单详情能取到，但现有流水没存 → 需扩展流水结构或另建表 |
| 4 | **【定投素材表】读写** | 全新，且是「原地更新」模式 |
| 5 | **【历史随心推订单存量表】读取** | 全新 |
| 6 | **千川计划命名生成 + 反解** | 全新。⚠️ 见下方风险 |
| 7 | **「对应投放账户」的选择规则** | 需明确规则（是否复用现有 `advertiser-tiers` 分档？） |

### ⚠️ 风险点：靠「计划名称」做匹配很脆

C1 要判断「是否已存在对应的千川计划」，如果靠**解析计划名 `抖音号 - 商品 - 日期`** 来反查，会有这些问题：

- 商品名里**自带 `-`** → 分割错位
- 计划名被人在千川后台**手工改过** → 解析失败
- 改名后（C1 要求改日期）**解析逻辑要能容忍两种格式**
- 抖音号/商品名里可能有空格、特殊字符

**建议：另存一份「达人+商品 → 千川计划 ID」的映射表（本地 JSONL），用计划 ID 做匹配，名称只用于展示。**
命名格式变化就不会影响匹配逻辑。

---

## 五、需要你确认的问题清单

**业务逻辑**
1. B 是不是应该改成 `== 3`？还是 `>=3` 与 `>3` 另有含义？
2. B 和 C2 的动作真的完全一样吗？（投放设置/预算/定向有无差异）
3. `M.D` 要不要前导零？C1 的「最新日期」指哪个日期？跨年怎么区分？
4. N 是全历史累计还是时间窗内？「笔数」是订单数还是追投次数？

**接口与数据**
5. 「千川计划」是**全域推广**还是**普通计划**？（决定用哪套 API，建错后面加素材会失败）
6. 【定投素材表】【历史随心推订单存量表】的**路径、格式（`.xls`/`.xlsx`）、列结构**？
7. 「对应投放账户」怎么确定？复用 `advertiser-tiers` 分档，还是另给规则？
8. 商品/达人信息从哪里来？**建议扩展现有流水**（订单详情已能取到 `productInfo.id/name` 与
   `awemeInfo.awemeShowId`，只是当前没落盘），还是只读【历史随心推订单存量表】？
   ⚠️ 两者数据若不一致，**以哪个为准**？

**工程形态**
9. 新需求是**并入现有 jar**（同一个服务、加定时任务），还是**另起一个独立工程/服务**？
10. RPA 用什么平台？A 场景是「调 RPA 的接口」还是「RPA 轮询某个表」？

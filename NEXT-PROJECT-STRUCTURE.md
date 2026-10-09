# 新工程结构与迁移方案（待确认 · 2026-09-29）

> 目标目录：`D:\program\aolandai\OceanEngine-colleague-dev`（按你的要求，直接在这个目录另起工程）
> 本文只做**结构与迁移规划**。阶段 0 的 API 探测尚未跑完（见第一节）。

---

## 一、阶段 0 只读探测：已完成的结论

### ✅ 已确认可行

| 项 | 结论 | 依据 |
|---|---|---|
| Token | `QIANCHUAN_SERVICE_BEARER_TOKEN` **已配置**（长度 8，与已知一致） | 环境变量实测 |
| 同事工程 | **能编译通过**（需联网拉 2 个缺失依赖） | `mvn compile` exit=0 |
| 「查千川计划」 | `QianchuanUniPromotionListV10Api`，`searchKeywordType=AWEME` 按抖音号查 + **本地按 `productInfo.productId` 精确匹配** | 同事 `chen/QianchuanPlanService` 已实现并跑通 |
| 「建千川计划」 | `QianchuanOverallVideoCreateV10Api`（`/qianchuan/overall_video/create/`），**`deliverySetting.qcpxMode = ON` 即乘方** | 同事 `ccreate_plan/` 已实现；枚举 `DEFAULT/OFF/ON` 已核对 |
| 「视频 → 商品ID」 | 链路已跑通：`分享口令 → 19位视频ID → 抖音号uid → 翻该号视频列表 → 匹配视频ID → product_info.id` | 同事 `chen/ProductIdFromVideo` |
| 「改名」 | `QianchuanAdUpdateV10Api` 支持 `adId` + `name` | `javap` 已核对 |

> ⚠️ 我上一轮猜的 `QianchuanAdCreateV10Api` **不对**。正确入口是 **`QianchuanOverallVideoCreateV10Api`**
> （全域推广专用），`qcpxMode` 就在它的 `deliverySetting` 下。

### ❌ 探测出一个**阻塞性问题**：商品 ID 映射表不够用

你给的商品映射表**不能直接把素材表的商品名换成商品 ID**：

| 指标 | 数值 |
|---|---|
| 素材表不同「产品」名称 | **60 个** |
| 能**精确匹配**到映射表 | **38 个（63.3%）** |
| **匹配不到** | **22 个** |
| 按行数加权可匹配 | 4116 / 4992 行 = **82.45%** |
| 按行数加权**匹配不到** | **876 行 = 17.55%** |

**三类硬伤：**

1. **一名对多 ID**：`青少年身体乳` → `3778832518661406894` 与 `3778829815013048664`（都是婴童店）。
   映射不是函数，直接映射会静默取到其中一个。
2. ⚠️ **`（次链）` 陷阱（最高危）**：映射表用 `（次链）` 后缀区分**同名不同 ID** 的 SKU
   （如 `口红` vs `口红（次链）`），但**素材表从不带这个后缀**。
   → 直接精确映射会把**次链商品错配成主链 ID**。涉及 11 个商品名。
3. **命名体系不一致**：素材表用营销短名（`山茶花护肤套装` / `仿生油` / `八色口红`），
   映射表用规范 SKU 名（`山茶花套装` / `仿生皮脂润肤油` / `口红`）。
   22 个不匹配几乎全是「同款不同写法」。
   另有脏数据：`猫爪润唇膏`（专卖店行）**缺商品 ID**；`山茶花洁面（次链)` **括号不配对**。

**→ 这正好解释了同事为什么写 `ProductIdFromVideo`：走接口反查商品 ID，而不是查名称表。**

**建议：商品 ID 以「接口反查」为准（`ProductIdFromVideo` 那条链路），名称映射表只做兜底/校验。**
如果坚持用名称映射，**必须先由业务确认一张别名归一表**（含 22 组映射 + 次链规则 + 一名多 ID 规则）。

### ⏳ 还没跑的探测

- 真实拉一次计划列表，确认**实际返回的 `qcpxMode` 长什么样**（是不是 `QCPX_MODE_ON`）
- 确认 `ProductIdFromVideo` 那条链路在**当前 token 下**仍能跑通（同事的代码里有硬编码账户/抖音号）
- 确认目标账户下**是否已有乘方计划**可用来做匹配验证

---

## 二、依赖精简：可以砍掉三个依赖

我查了同事工程里每个第三方 import 的**实际使用位置**，结论很干净：

| 依赖 | 谁在用 | 处置 |
|---|---|---|
| `lombok` 1.18.48 | **只有 `plant_search/` 的 3 个文件** | ❌ 可删 |
| `httpclient` 4.5.14 | **只有 `plant_search/QianchuanPlanQueryService`** | ❌ 可删 |
| `slf4j-api` 1.7.32 | **只有 `plant_search/` 的 2 个文件** | ❌ 可删 |
| `okhttp` 4.10.0 | 只有 `plant_search/` 直接 import（SDK 本身会传递引入） | 可删（传递依赖已有） |
| `jackson-databind` | `ProductIdFromVideo` 等 | ✅ 保留 |
| `oceanengine-mapi-java-client` 1.1.96 | 核心 | ✅ 保留 |
| `poi-ooxml` | 要读 `.xlsx`（现有主项目只有 HSSF 的 `poi`） | ➕ **新增** |

**原因**：`plant_search/` 是一个**独立的、更早的实现**（裸 HTTP + lombok + slf4j），
而 `chen/` 是**用 SDK 的更新实现**，两者功能重叠。

**→ 建议保留 `chen/`，整体弃用 `plant_search/`**，这样依赖直接砍到 3 个，也消除了重复实现。

---

## 三、目标项目结构（高内聚低耦合）

**坐标**：`com.aolandai:dingtou:1.0-SNAPSHOT`，基础包 `com.aolandai.dingtou`
（名字待你确认；现有工程是 `com.aolandai:oceanengine`）

```
com.aolandai.dingtou
├── launcher/         启动入口 + Bean 装配
├── config/           配置（@ConfigurationProperties + @AssertTrue 自检 + 启动横幅）
├── client/           外部接入基础设施 —— 与业务无关，可整包复用
│     QianchuanTokenClient / SuixintuiTokenClient
│     ApiErrors / RateLimitException / ApiRetry
├── source/           数据源层：只负责「把表读成对象」
│     ExcelReader(通用 POI 封装) + 4 个 Reader + model/
├── normalize/        归一化层 ★本次新增，风险最高
│     DouyinIdNormalizer / ProductNameNormalizer
│     AccountNameNormalizer / MdTextDateParser(解析 9.2 这种小数日期)
├── aggregate/        聚合层：按【达人+商品】算累计笔数 N
├── decision/         决策层：纯函数、无 IO，A/B/C1/C2 判定 + 计划命名
├── qianchuan/        千川能力层：查 / 建 / 改名 / 加素材 / 视频→商品ID
├── rpa/              影刀接入层
├── store/            幂等流水层（通用 JSONL + 计划映射表 + 动作流水）
├── pipeline/         编排层：把上面串成「一轮」
├── scheduler/        调度 + 单实例锁 + health
└── tools/            调试探针（不进生产路径）
```

**分层的依赖方向是单向的**（`pipeline` → `decision`/`aggregate`/`qianchuan`/`rpa` → `source`/`store`/`client`），
`decision` 是**纯函数层**（输入决策表、输出动作），不碰 IO —— 这样 A/B/C 的业务规则可以**单测全覆盖**，
不会因为接口/文件问题而没法测。

**关键设计：`store/PlanLedger`（达人+商品 → 千川计划 ID 映射表）**

这是我对上一轮「靠计划名反查很脆」那条风险的落地：**用计划 ID 做匹配，名称只用于展示**。
`decision` 层拿到的「是否已有千川计划」来自这张本地映射表（接口查询作为校验/回填），
计划命名规则怎么改都不影响匹配逻辑。

---

## 四、迁移映射表（逐文件）

| 新位置 | 来源 | 处置 |
|---|---|---|
| `client/QianchuanTokenClient` | 同事 `client/` | 直接迁移 |
| `client/SuixintuiTokenClient` | 同事 `client/` | 直接迁移 |
| `client/ApiErrors` + `RateLimitException` | **主项目**（同事工程没有） | 直接迁移 —— 三层频控 + 5 分钟惩罚 |
| `qianchuan/QianchuanPlanQueryService` | 同事 `chen/QianchuanPlanService` **+** `plant_search/` 的「按商品ID查」模式 | **二合一**：以 `chen/` 的 SDK 写法为主，吸收 `plant_search` 的查询模式 |
| `qianchuan/QianchuanPlanCreateService` | 同事 `ccreate_plan/QianchuanOverallVideoCreateService` + `OverallVideoCreateParam` + `CreatePlanResult` | 迁移，**去硬编码**，参数从配置/入参来 |
| `qianchuan/QianchuanVideoProductResolver` | 同事 `chen/ProductIdFromVideo`（743 行） | ⚠️ **必须重构**：拆掉 `main()`、去掉硬编码常量、改成可注入的服务；短链 HTML 抓取那段要评估稳定性 |
| `qianchuan/model/*` | 同事 `chen/QianchuanPlanQueryResult`、`AmbiguousPlanException` | 迁移（`AmbiguousPlanException` 保留 —— 「匹配到多个计划」必须人工介入） |
| `config/*` | 主项目 `SuixintuiReinvestProperties` 的**范式** | 照抄范式（自检 + 横幅） |
| `store/*` | 主项目 `ReinvestRecordStore` / `WebhookNotificationStore` | 抽成通用 JSONL 存储 |
| `scheduler/*` | 主项目 `SuixintuiReinvestScheduler` + `FileChannel.tryLock()` | 照抄 |
| `tools/XlsDumpTool` | 主项目 `tools/` | 迁移 |
| `deploy/*` + 部署文档 | 主项目 `deploy/` | 照抄（改服务名/路径） |

**不迁移**（与定投无关）：

- 主项目 `domain/SuixintuiReinvestCandidateSelector`、`service/suixintui/*`（随心推**追投**逻辑）
- 主项目 `scheduler/ReinvestRound`、`webhook/*`、`report/*`（日报）

**整体弃用**：同事 `plant_search/` 包（6 个文件）—— 被 `chen/` 覆盖，且带来 3 个无用依赖。

---

## 五、需要你确认的 4 件事

1. **商品 ID 以哪个为准？**
   - **(A) 接口反查为准**（同事 `ProductIdFromVideo` 那条链路），名称映射表只做兜底 ← **我建议这个**
   - (B) 名称映射表为准 → **必须先让业务确认一张别名归一表**（22 组映射 + 次链规则 + 一名多 ID 规则）

2. **`plant_search/` 包整体弃用、保留 `chen/` 的 SDK 实现** —— 同意吗？
   （这样能砍掉 lombok / httpclient / slf4j 三个依赖，并消除重复实现）

3. **坐标与包名**：`com.aolandai:dingtou` / `com.aolandai.dingtou` 可以吗？还是沿用 `oceanengine` 前缀？

4. **新工程要不要做成 Spring Boot 服务**（像现有工程那样 WinSW 托管、定时轮询），
   还是**保持普通 Java + 手动/调度器触发**？（现有工程是 Spring Boot + WinSW，建议保持一致）

---

## 六、我建议的下一步

1. 你确认上面 4 件事（尤其是**第 1 件**，它决定 `normalize` 模块怎么写）。
2. 我先把**阶段 0 剩下的 API 探测**跑完（只读、不花钱）：真实拉一次计划列表，
   确认 `qcpxMode` 的实际取值、确认目标账户下已有计划长什么样。
3. 然后按第三节的结构**搭骨架 + 迁移代码**（`chen/` 那几块是现成的，工作量主要在 `source` / `normalize` / `decision`）。

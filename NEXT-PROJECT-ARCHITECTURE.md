# 新工程技术架构（待确认 · 2026-09-29）

> 本文**取代** `NEXT-PROJECT-STRUCTURE.md` 第三节的目录结构（那份里其余内容仍然有效）。
> 目录：`D:\program\aolandai\OceanEngine-colleague-dev`　坐标：`com.aolandai:dingtou`　包：`com.aolandai.dingtou`

---

## 一、形态变了：不是定时轮询，是「影刀驱动的执行器」

你的原话：**「接收请求根据请求的信息运行特定类，应答字段传回影刀端」**。

所以这不是现有那种 `@Scheduled` 轮询服务，而是一个 **HTTP 任务执行器**：

```
影刀 ──POST /api/v1/task/{taskCode}──►  本服务
                                          ├─ 鉴权
                                          ├─ 按 taskCode 找到对应的 TaskHandler
                                          ├─ 执行（读表 / 调千川 API / 算决策）
                                          └─ 组装结构化应答
影刀 ◄────── {code, message, data} ──────
```

**职责边界建议**（这条最关键，需要你确认）：

| 谁 | 负责什么 |
|---|---|
| **影刀** | RPA 动作（新建随心推、点表、写 Excel）、流程编排、决定何时调用本服务 |
| **本服务** | 一切**逻辑**：读表聚合、达人/商品归一化、A/B/C 决策、**全部千川 API 调用**、幂等落盘 |

**为什么这样分**：决策规则会反复变（B 从 `>=3` 改 `==3` 就是例子），放在 Java 里能**单测全覆盖**；
放影刀里改一次就要人肉回归一次。而 RPA 那些点击/写表动作天然属于影刀。

---

## 二、技术选型：Spring Boot 3.4.5 + 内嵌 Tomcat

**推荐 `spring-boot-starter-web`**，理由：

| 理由 | 说明 |
|---|---|
| 与现有工程同栈 | 部署（WinSW）、日志、排障、配置自检**整套经验直接复用**，不用重新踩坑 |
| 内嵌 Tomcat | 单端口，不需要额外装 web 容器；打包就是一个 jar |
| actuator 现成 | 影刀调用前可先探活 `/actuator/health`；现有工程已验证过「只绑回环」的写法 |
| 配置范式现成 | `@ConfigurationProperties` + `@AssertTrue` 自检 + 启动横幅，照抄即可 |
| 生态 | 拦截器（鉴权）、统一异常处理、JSON 序列化都是开箱即用 |

**不推荐**的替代：
- 纯 `HttpServer`（JDK 自带）：省依赖，但鉴权/异常/JSON/线程池都要手写，不划算
- Flask/FastAPI：要跨语言维护，且千川 SDK 是 Java 的（`oceanengine-mapi-java-client`）→ **必须 Java**

---

## 三、核心设计：任务分发（命令模式）

**一个入口，按 `taskCode` 分发**：

```
POST /api/v1/task/{taskCode}
Header:  X-Auth-Token: <token>
Body:    { "requestId": "影刀生成的唯一号", "params": { ... } }
```

**统一应答**：

```json
{
  "code": 0,
  "message": "ok",
  "requestId": "...",
  "taskId": "...",
  "costMs": 1234,
  "data": { ... }
}
```

`code`：`0`=成功 / `1`=业务失败（附 `message`）/ `2`=参数错 / `3`=未授权 / `4`=执行超时（转异步）

**分发实现**（加任务不改分发器，开闭原则）：

```java
public interface TaskHandler {
    String code();                                   // 任务码
    Object handle(JsonNode params) throws Exception; // 业务实现
}
```

Spring 自动收集所有 `TaskHandler` Bean → 构建 `Map<String, TaskHandler>`。
**新增一个任务 = 新增一个 `@Component` 类**，不碰任何既有代码。

### 建议的任务清单（初版）

| taskCode | 入参 | 返回 | 影刀拿它做什么 |
|---|---|---|---|
| `ping` | — | 版本、时间、表是否可读 | 调用前探活 |
| `decide` | 达人抖音号 + 商品（或商品ID） | `{N, hasPlan, planId, scenario, action}` | 按场景分支：A 走 RPA，B/C 调下面的任务 |
| `aggregate` | 可选筛选条件 | 决策表（可分页） | 批量拉全量决策，自己循环 |
| `videoProduct` | 抖音号 + 视频ID/分享链接 | `{productId, productName}` | 补全素材表缺失的商品 ID |
| `planQuery` | 账户 + 达人 + 商品 | `{found, planId, name, qcpxMode, ...}` | 二次确认 |
| `planCreate` | 账户 + 达人 + 商品 + 素材列表 | `{planId, name}` | 建乘方计划 |
| `planAddMaterial` | 计划ID + 素材列表 + 新名称 | `{ok}` | C1：加素材 + 改名 |
| `planRegister` | 达人 + 商品 + 计划ID | `{ok}` | 建完后登记进本地映射表 |

> 最后一条 `planRegister` 是落实「**用计划 ID 匹配、不靠计划名反查**」的关键 —— 建完计划立刻登记，
> 以后 `decide` 直接查本地映射表，快且稳。

---

## 四、四个必须处理好的工程问题

### ① 同步还是异步 —— 建议「同步为主 + 超时转异步」

影刀等应答最简单，但千川接口 + 翻页可能跑几分钟，影刀那边通常有超时。

**方案**：每个任务配 `timeoutMs`（配置里按 taskCode 配）。

- 在超时内跑完 → **同步返回** `code=0` + `data`
- 超时 → 立刻返回 `code=4` + `taskId`，任务继续在后台跑；
  影刀轮询 `GET /api/v1/task/{taskId}` 拿结果

**别让影刀死等** —— 死等的结果是影刀超时重试 → 重复建计划。

### ② 幂等 —— 必须做，否则影刀重试会重复建计划

请求带 `requestId`，服务端 JSONL 落盘：

- 已处理过该 `requestId` → **直接返回上次的结果**（不重复执行）
- 执行中 → 返回 `code=4` + 同一个 `taskId`

**这是本工程最重要的一个防护**：影刀的重试策略不可控，而「重复建千川计划」是要花钱的。

### ③ 并发控制 —— 复用现有工程的单实例锁

`FileChannel.tryLock()` 那套照搬。防止影刀并发触发导致同一「达人+商品」被建两次计划。
另外建议：**对「达人+商品」加细粒度锁**，同一组合串行执行。

### ④ 鉴权 —— 不要裸奔

`X-Auth-Token` 头，值从配置读；不匹配直接 401。
现有工程的 actuator 都收到回环了（`server.address: 127.0.0.1`），这个服务是要**被影刀从外部调**的，
如果部署在不同机器上，**至少要上 token，最好再限制来源 IP**。

---

## 五、目录结构（web 版）

```
com.aolandai.dingtou
├── launcher/          启动 + Bean 装配
│     DingtouApplication / DingtouConfig
├── config/            配置（@ConfigurationProperties + 自检 + 启动横幅）
│     DingtouProperties / QianchuanProperties / SourceProperties / TaskProperties
├── web/               ★ Web 层：只做「收请求、发应答」，不含业务
│     TaskController            统一入口（POST /task/{code}、GET /task/{id}）
│     AuthInterceptor           鉴权
│     ApiExceptionHandler       统一异常 → 结构化应答
│     dto/  ApiRequest / ApiResponse / ErrorCode
├── task/              ★ 任务层：分发 + 各任务实现
│     TaskHandler               接口
│     TaskDispatcher            按 code 分发
│     TaskRegistry              启动时校验「code 不重复 / 无遗漏」
│     handler/                  一个任务一个类
│        PingHandler / DecideHandler / AggregateHandler / VideoProductHandler
│        PlanQueryHandler / PlanCreateHandler / PlanAddMaterialHandler / PlanRegisterHandler
├── client/            接入基础设施（与业务无关）
│     QianchuanTokenClient / SuixintuiTokenClient / ApiErrors / RateLimitException / ApiRetry
├── source/            数据源层：把表读成对象
│     ExcelReader + DeliverySheetReader / OrderStockReader / ProductDictReader / AccountDictReader
│     model/
├── normalize/         归一化层 ★风险最高
│     DouyinIdNormalizer / ProductNameNormalizer / AccountNameNormalizer / MdTextDateParser
├── aggregate/         聚合层：按【达人+商品】算 N
├── decision/          决策层：**纯函数、无 IO**，A/B/C 判定 + 计划命名
├── qianchuan/         千川能力层（迁移同事的 chen/ 与 ccreate_plan/）
├── store/             幂等流水：JsonlStore + RequestLedger + PlanLedger + ActionLedger
├── scheduler/         兜底轮询（可选）+ InstanceLock + HealthIndicator
└── tools/             调试探针（不进生产路径）
```

**依赖方向严格单向**：

```
web → task → pipeline(可选) → decision / aggregate / qianchuan → source / store / client
                                  ↑
                          decision 不依赖任何 IO
```

**`task` 层只做编排**，具体逻辑都在下层；`web` 层不认识任何业务类。

---

## 六、代码风格约定（重新调整）

| 项 | 约定 | 理由 |
|---|---|---|
| **Lombok** | ❌ **不用** | 现有工程全项目无 lombok；且同事 pom 里的 lombok 只为 `plant_search/` 服务，弃用后一并去掉。**显式 getter/setter 或 `record`** |
| **日志** | ✅ **改用 slf4j + logback** | 现有工程全 `System.out.println`，导致业务日志跑到 WinSW 的 `.out.log`、`application.log` 几乎是空的。**web 服务需要按级别/按请求打日志**，slf4j 更合适。⚠️ 这会改变日志排查方式，需同步更新部署文档 |
| **DTO** | 不可变优先，用 `record` | 跨层传数据只传不可变对象 |
| **异常** | 自定义 `BizException(code, message)`，由 `ApiExceptionHandler` 统一转应答 | 业务代码里不写 try-catch 兜底 |
| **返回** | 不用 `null` 表示「无」 | 用 `Optional` 或明确的空对象 |
| **HTTP 调用** | 统一走 `client/` 的封装（含频控/重试） | 不要在业务类里裸写 `HttpClient` |
| **魔法值** | 全部进配置或常量类 | 同事的 `ProductIdFromVideo` 就是反面教材（账户/抖音号/分享链接全硬编码） |
| **测试** | `decision` 层单测覆盖率要求 100% | 它是纯函数，没理由不测 |

---

## 七、需要你确认的 3 件事

1. **职责边界**：同意「影刀只做 RPA + 编排，所有逻辑和千川 API 调用都在本服务」吗？
   （若你希望影刀那边也保留一部分逻辑，接口设计要跟着变）

2. **服务能不能读到那 4 张 Excel？** 即：**本服务与影刀跑在同一台机器**，还是不同机器？
   - 同机 → 服务直接按配置路径读表（推荐，逻辑集中好维护）
   - 不同机 → 要么把表放共享盘，要么**由影刀把数据放进请求体**（请求会很大）

3. **日志改用 slf4j** 是否接受？（会改变现在的日志排查习惯 —— 好处是 `application.log` 终于有内容了）

确认后我就按第五节搭骨架，并开始迁移同事的 `chen/` + `ccreate_plan/` 两块现成能力。

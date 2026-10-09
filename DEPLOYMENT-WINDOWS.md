# OceanEngine Windows 部署教程

本文适用于把项目部署到另一台 Windows 电脑，并让随心推追投程序后台常驻运行。

## 1. 部署前准备

目标电脑需要具备：

- Windows 10/11 或 Windows Server
- JDK 17
- 网络可以访问 `https://api.oceanengine.com` 和 `https://aocilenda.cn`
- 一个用于运行程序的 Windows 用户
- 一个不会被清理的程序目录，例如 `D:\oceanengine`

不要只复制 `target\classes`。推荐在开发电脑打包后，只把可执行 JAR、外部配置和流水目录部署到目标电脑。

开发电脑打包时还需要能访问 Maven 依赖仓库 `https://artifact.bytedance.com`（SDK 依赖 oceanengine-mapi-java-client 来自该私有仓库，已在 pom.xml 配置）。

安全提示：程序默认把 Web 端口绑在回环网卡上（`server.address: 127.0.0.1`），只有本机能访问 `/actuator/health`，不需要额外配防火墙。**不要改回 `0.0.0.0`**：`/actuator/health` 的 UP/DOWN 状态会反映最近一轮追投是否出错，暴露出去会泄露运行健康信息。

## 2. 安装 Java 17

在目标电脑安装 64 位 JDK 17。安装后打开新的 PowerShell，确认：

```powershell
java -version
```

输出的主版本应为 `17`。如果电脑安装了多个 Java，使用下面命令确认实际路径：

```powershell
where.exe java
$env:JAVA_HOME
```

## 3. 创建目录

以 PowerShell 执行：

```powershell
New-Item -ItemType Directory -Force D:\oceanengine\config | Out-Null
New-Item -ItemType Directory -Force D:\oceanengine\data | Out-Null
New-Item -ItemType Directory -Force D:\oceanengine\logs | Out-Null
# 日报模板目录（日报靠这个 .xls 模板出表，必须存在）
New-Item -ItemType Directory -Force D:\oceanengine\data\currentreport | Out-Null
```

最终目录建议如下：

```text
D:\oceanengine\
  oceanengine-1.0-SNAPSHOT.jar
  config\application.yml
  data\reinvest-records.jsonl
  data\reinvest.lock
  data\currentreport\每日追投明细表模板.xls   ← 日报模板，必须放对位置
  logs\
```

> 「{yyyy-MM}追投日报」这类月份文件夹**不需要预先创建**，生成报表时会自动建。

## 4. 在开发电脑打包

在项目根目录执行：

```powershell
mvn clean package
```

确认生成：

```text
target\oceanengine-1.0-SNAPSHOT.jar
```

如果只需要编译，不想执行测试：

```powershell
mvn clean package -DskipTests
```

**两种都可以，完整构建是安全的。** `src/test` 下唯一会真实扣费的 `ReinvestApiSmokeTest`
已经加了 `@EnabledIfEnvironmentVariable(named = "RUN_REAL_REINVEST_TEST")` 守卫，默认被跳过；
其余带 `@Test` 的类要么是纯单元测试，要么用 Mockito 打桩，不会碰真实接口。

> 注意区分：`src/test` 下还有一批**只有 `main()`、没有 `@Test`** 的调试工具
> （如 `suixintui/SuixintuiBudgetAddTest`、`tools/SuixintuiOrderDetailTest`）。
> surefire 不会自动执行它们，**必须手动指定类名**；其中 `SuixintuiBudgetAddTest` 会真实扣费，跑之前想清楚。

⚠️ 以后新增会真实扣费的测试，务必照抄那个 `@EnabledIfEnvironmentVariable` 守卫，
否则 `mvn clean package` 会直接花钱。

> **首次构建需要联网**：生成日报用的 Apache POI 不在私有仓库里，走 Maven Central。
> 本机 `~/.m2` 缓存过之后就能离线构建（`mvn -o package`）。
> 打出来的 jar 约 82MB（引入 POI 之前约 79MB）。

## 5. 复制部署文件

把下面文件复制到目标电脑：

```text
target\oceanengine-1.0-SNAPSHOT.jar
src\main\resources\application.yml
D:\oceanengine\data\currentreport\每日追投明细表模板.xls   ← 日报模板（在目标机/共享盘上取一份）
deploy\oceanengine-service.xml
deploy\install-service.cmd
deploy\uninstall-service.cmd
deploy\restart-service.cmd
deploy\stop-service.cmd
deploy\start-service.cmd
deploy\status.cmd
```

分别放到：

```text
D:\oceanengine\oceanengine-1.0-SNAPSHOT.jar
D:\oceanengine\config\application.yml
D:\oceanengine\data\currentreport\每日追投明细表模板.xls
D:\oceanengine\oceanengine-service.xml
D:\oceanengine\install-service.cmd
D:\oceanengine\uninstall-service.cmd
D:\oceanengine\restart-service.cmd
D:\oceanengine\stop-service.cmd
D:\oceanengine\start-service.cmd
D:\oceanengine\status.cmd
```

⚠️ **日报模板必须放到 `template-path` 指向的位置**（默认 `D:\oceanengine\data\currentreport\每日追投明细表模板.xls`）。
模板缺失**不会**让服务启动失败，但日报会生成失败——所以这一项要单独确认。
表头行和单元格样式都是从模板继承的，想改表头/配色就改这个模板文件，不用改代码。

⚠️ **复制 `.cmd` 文件时不要用会改编码/换行的工具**（比如某些编辑器「另存为 UTF-8」、或经过 Linux 环境的中转）。
`.cmd` 必须是 **ANSI/GBK 编码 + CRLF 换行**，否则在目标机上双击会报一堆「不是内部或外部命令」。
判断与修复办法见第 13 节。

另外还需要下载 WinSW 本体（不在仓库里）：

1. 打开 https://github.com/winsw/winsw/releases
2. 下载 `WinSW-x64.exe`
   - 目标电脑没有 .NET Framework 4.6.1+ 时用这个（自包含，约 17MB）
   - 有 .NET Framework 4.6.1+ 时可改用 `WinSW.NET461.exe`（约 0.6MB）
3. **重命名为 `oceanengine-service.exe`**，放到 `D:\oceanengine\`

> 文件名必须与 `oceanengine-service.xml` 同名（去掉 `.exe`），WinSW 靠这个规则找配置。
> 不要复制开发电脑的 `target\classes`、`.vscode`、`.git` 和临时文件。

## 6. 修改生产配置

编辑目标电脑的：

```text
D:\oceanengine\config\application.yml
```

至少确认以下配置：

```yaml
server:
  port: 8080
  # 只绑回环：健康检查仅本机可访问。不要删，也不要改成 0.0.0.0
  address: 127.0.0.1

suixintui:
  reinvest:
    # 账户分档（账户的唯一来源）。⚠️ 外部配置里必须把全部档次和账户写全，见下方「账户分档」一节
    advertiser-tiers:
      - name: 10000档
        max-amount-per-advertiser-per-day: 10000
        advertiser-ids: [1779106144470100, 1778533513439244, 1776098375039040, 1771298760893511, 1795656569312393]
      - name: 20000档
        max-amount-per-advertiser-per-day: 20000
        advertiser-ids: [1823379540880396]
      - name: 15000档
        max-amount-per-advertiser-per-day: 15000
        advertiser-ids: []
    record-store-path: D:/oceanengine/data/reinvest-records.jsonl
    instance-lock-path: D:/oceanengine/data/reinvest.lock
    dry-run: true
    run-once: false

  # 每日追投明细表：每日 00:01 把「前一日」的流水按订单聚合成表，读 .xls 模板填数据后另存
  report:
    enabled: true
    # Spring 6 段 cron：秒 分 时 日 月 周。留空或写错会导致启动失败
    cron: "0 1 0 * * ?"
    template-path: D:/oceanengine/data/currentreport/每日追投明细表模板.xls
    output-dir: D:/oceanengine/data
    # 账户 ID → 投放账户名，填报表的「投放账户」列。没配到的会显示账户 ID 并在日志提醒
    advertiser-names:
      1779106144470100: 婴童【青少年-商品】
      1778533513439244: 婴童【儿童-商品】
      1776098375039040: 官方【青少年-商品】
      1771298760893511: 官方【孕产-商品】
      1795656569312393: 官方【婴童-商品】
      1823379540880396: 官方【母婴商品】
    # 启动时若「昨日报表」不存在则补生成一次；部署后想立刻验证就把 run-now 改 true
    catch-up-on-start: true
    run-now: false

logging:
  file:
    name: D:/oceanengine/logs/application.log
  logback:
    rollingpolicy:
      # 按天 + 按大小滚动，保留最近 30 天，归档总量上限 2000MB
      file-name-pattern: D:/oceanengine/logs/application.%d{yyyy-MM-dd}.%i.log
      max-file-size: 100MB
      max-history: 30
      total-size-cap: 2000MB
```

上面这些配置在复制过去的模板里**都已经有值**（两个绝对路径、回环绑定、日志落盘路径），核对即可，不需要手工补。

**但 `dry-run` 和 `run-once` 必须逐次确认：** 第一次部署必须是 `dry-run: true` + `run-once: false`。如果模板里被人改成过 `dry-run: false`（本地实测时常见），**复制过去就直接开始真实扣费了**，务必在启动服务前核对这两行。

⚠️ **不要用 `management.server.address` 来收紧 actuator。** 这个参数只有在 actuator 单独占一个端口（即同时配了 `management.server.port`）时才允许设置，否则程序**启动直接失败**：

```text
java.lang.IllegalStateException: Management-specific server address cannot be configured
as the management server is not listening on a separate port
```

本项目没有独立管理端口的必要——它没有任何业务 HTTP 接口，8080 上唯一对外的东西就是 `/actuator/health`，所以用 `server.address` 一次性把整个 Web 端口收到回环就够了。

第一次部署必须保持 `dry-run: true`。确认连续几轮输出的订单、ROI、金额和账户都正确后，才改为：

```yaml
dry-run: false
```

同时核对这些业务配置：

- `advertiser-tiers`：账户分档，**这是账户的唯一来源**。每档含 `name`（档次名，用于日志/报错）、`max-amount-per-advertiser-per-day`（本档每个账户的每日金额上限）、`advertiser-ids`（本档账户）。⚠️ **见下方警告，这个列表必须写全**
- `interval-ms`：轮次间隔（毫秒）。模板默认 `600000`（10 分钟）。**以目标电脑外部配置文件里的实际值为准**，部署前按业务确认追投频率
- `amount`：每单追加金额
- `delivery-time`：只能使用 24、48、72、96、120、144、168
- `min-roi`：ROI 阈值（**全局默认**，未命中商品级覆盖的商品都用它）
- `min-stat-cost`：**整体消耗下限（元）**。低于它的订单一律不追投（2026-09-30 新增）。
  理由：消耗太少还没跑出有效数据，而且它们的分支②容易因「增量占比虚高」而误触发。
  边界：正好等于下限**保留**；消耗为 null **排除**；设 0 表示不过滤
- `min-budget-used-ratio`：使用率阈值。**2026-09-30 起判定改成两分支 OR**，见下方专节
- `product-roi-overrides`：**商品级 ROI 门槛**（2026-10-04 新增）。少量特定商品要更高的 ROI
  才允许追投，见下方专节
- `consume-snapshot-path`：**每轮每单整体消耗**快照文件（分支②要用的「上一轮消耗」）。
  ⚠️ 必须绝对路径；**文件损坏会导致启动失败**（快照丢失会让分支②失效，退化成只判使用率）
- `snapshot-retention-days`：快照保留天数，默认 7（超期条目自动清理）
- `max-amount-per-order-per-day`：单订单每日金额上限
- `max-amount-per-round`：单轮金额上限。**所有账户共享**（跨账户累加），一轮用满后本轮后续账户的候选全部被拒，所以它决定了单轮最多投几单

> 单账户每日金额上限**不在这一层**，已改为按档次配置，见上面的 `advertiser-tiers`。没有全局的 `max-amount-per-advertiser-per-day` 了。

### ⚠️ 商品级 ROI 门槛（2026-10-04 起）

**需求**：绝大多数订单用统一门槛 `min-roi`（生产 1.8）；**少量特定商品**要求更高的 ROI
才允许追投。例如商品 `3686032876161335490` 必须 **ROI ≥ 6**。

**配置**（`suixintui.reinvest` 下）：

```yaml
product-roi-overrides:
  3686032876161335490: 6
  # 后面还会加，往下接着写
```

**判定口径**：

| 情况 | 用哪个门槛 |
|---|---|
| 商品 ID 命中 `product-roi-overrides` | **配的值**（可高于也可低于 `min-roi`） |
| 未命中 / 拿不到商品 ID（`product_info` 为 null） | 回退全局 `min-roi` |
| `product-roi-overrides` 整个不写 | 功能不生效，**行为与改造前完全一致** |

- 商品 ID 取自列表响应的 `order_list[].product_info.product_id`（`Long`）。
- 边界**含等于**：`roi >= 门槛` 即放行（与全局 ROI 判定一致）。
- **同一份规则**同时用于**追投筛选**与**日报「已结束达标订单」sheet**——两处口径必须一致，
  否则「被追投的订单」和「日报里的达标订单」会对不上。

**两个坑**：

1. ⚠️ 列表接口**不能按商品过滤**（`filtering` 只有 `status` + 创建时间），所以是在**内存里分流**判
   ROI 的。订单列表本来就是拉全量，不存在「特定商品被漏掉」的问题。
2. ⚠️ 「没配到的商品」会**静默回退**全局门槛，**启动自检校验不出漏配**。防漏配只能人工核对：
   **启动横幅有 `商品级 ROI 门槛: 3686...→6.0（其余商品用全局门槛 1.8）` 这样一行**。
   没配时打印 `未配置（全部商品用全局门槛 1.8）`——**看不到你配的商品就是没生效**。

**为什么放在 `reinvest` 而不是 `report` 下**：门槛是**追投筛选**的属性，日报只是复用同一份口径。

### ⚠️ 使用率判定是「两分支 OR」（2026-09-30 起）

一个订单要被追投，必须同时满足：**ROI 达标**、**整体消耗 ≥ `min-stat-cost`**、
以及 **下面两个分支任一成立**。

```
①  使用率      = C_now / B                      >= min-budget-used-ratio
②  增强使用率  = (2 × C_now − C_prev) / B       >= min-budget-used-ratio
```

| 符号 | 含义 |
|---|---|
| `C_now` | 本轮的整体消耗（`stats_info.stat_cost_for_roi2`） |
| `C_prev` | **上一轮**的整体消耗，取自 `consume-snapshot-path` 的快照 |
| `B` | 投放总金额 = 创建预算 + 追加金额 |

**分支②的业务含义**：展开成 `C_now >= 0.4×B + C_prev/2` 可以看出「**消耗涨得越快，越早触发**」。
它的作用是把「差一点到 80%、但这一轮烧得很快」的订单捞回来 —— 这是改动的目的。

#### `C_prev` 缺失时：按「本轮增量 = 0」处理（2026-09-30 修正）

**`C_prev` 缺失**的场景：服务重启后的第一轮、订单首次出现在列表里、快照文件被删/丢失。

此时**按 `C_prev = C_now` 处理**，分支② 退化成 `C_now / B` —— **与分支① 完全相同**，
这一轮只按 80% 门槛判，**不会放宽条件**。日志里会显示 `上轮消耗=无(增量按0)`，
且 `增强使用率` 与 `使用率` **数值相同**。

> ⚠️ **这里修掉过一个 bug**（2026-09-30）：原先 `C_prev` 缺失时按 **0** 兜底，
> 分支② 变成 `2×C_now/B` —— **把使用率虚高一倍**，门槛从 80% 悄悄降到 40%。
> 后果是**服务每次重启后的第一轮大批多投**（实测 19 条 ROI 达标里多投 10 单 ≈ 2000 元）。
>
> 实测对照（同一批订单，无快照）：
> - 按 0 兜底（旧）：使用率 61.12% → 增强使用率 **122.24%**（正好翻倍）→ 命中
> - 按 `C_now`（现）：使用率 61.12% → 增强使用率 **61.12%**（相等）→ 不命中
>
> 对应的 `fallback-prev-cost` 配置项**已删除**（不再需要兜底值）。

⚠️ 注意：这只影响「缺基线」的那一轮；**后续轮次分支② 照常工作**，
消耗涨得快的订单仍能被提前捞回来。

#### 快照文件

`consume-snapshot-path` 存「每轮每单的整体消耗」，每个订单只保留最新一条，所以文件大小只跟订单数有关
（实测 396 单 ≈ 58 KB），不会随轮次数增长。`snapshot-retention-days`（默认 7 天）自动清理超期条目。

⚠️ 该文件**损坏会导致服务拒绝启动**。理由：快照丢失会让分支② 失效、退化成只判使用率 ——
虽然**不会再导致多花钱**（那是修正前的行为），但会让筛选变保守、**漏掉本该提前追投的订单**。
**停摆的代价小于静默漏投的代价。** 修复或删除该文件后重启即可（删掉就等于所有订单都从"无基线"开始）。

### 账户分档（`advertiser-tiers`）怎么配

账户按档次分组，每个档次有自己的单账户日限额：

```yaml
suixintui:
  reinvest:
    advertiser-tiers:
      - name: 10000档
        max-amount-per-advertiser-per-day: 10000
        advertiser-ids:
          - 1779106144470100
          - 1778533513439244
      - name: 20000档
        max-amount-per-advertiser-per-day: 20000
        advertiser-ids:
          - 1823379540880396
      - name: 15000档          # 预留档：账户暂时为空，之后补账户只改这里
        max-amount-per-advertiser-per-day: 15000
        advertiser-ids: []
```

规则：

- **档次数量不限**，一个两个三个都行，程序不写死 3
- **档次顺序 = 每轮的账户扫描顺序**。单轮额度是先到先得，排在前面的账户优先花，所以把额度紧张的账户排前面
- **档次里的账户列表允许为空**（预留档），但**至少要有一个档次、且全部档次合起来至少有一个账户**，否则启动失败
- **同一账户不得出现在两个档次里**，否则启动失败（避免限额归属不明）
- 每档的 `max-amount-per-advertiser-per-day` 必须 ≥ `amount`，否则启动失败
- 账户不在任何档次里 → 该账户**本轮所有候选都会被拒**并打日志（正常配置下不会出现，自检已拦住）

### 每日追投明细表（`report`）怎么配

每日 **00:01** 把**前一日**的追投流水按订单聚合成一张表，读 `.xls` 模板填数据后另存。

**输出路径**：

```text
D:\oceanengine\data\{yyyy-MM}追投日报\{yyyy-MM-dd}追投明细表.xls
例：D:\oceanengine\data\2026-09追投日报\2026-09-23追投明细表.xls
```

月份文件夹不存在会**自动创建**。改 `output-dir` 就换根目录，月份文件夹名和文件名模板分别由
`folder-pattern` / `file-name-pattern` 控制。

**表格内容**（模板第 1 行是表头、原样保留，数据从第 2 行开始写）：

| 列 | 内容 |
|---|---|
| A 订单ID | 文本格式，避免 Excel 把 19 位数字按数值处理而丢精度 |
| B 抖音名 | 追投时从订单列表写入流水 |
| C 抖音号 | 同上，文本格式 |
| D 投放账户 | 取 `advertiser-names` 映射；**没配到的显示账户 ID** 并在日志里提醒 |
| E 追投次数 | 该订单当天累计次数（数值） |
| F 追投金额 | 该订单当天累计金额，单位元（数值） |
| G 时间 | 报表归属日期，格式由 `date-pattern` 决定 |
| H 追投合计金额 | **只在 H2 写当天所有订单的总合计**，其余行留空；没有单独的合计行 |

**一行 = 一个订单**（当天多笔追投聚合成一行）。排序：投放账户 ID 升序 → 组内追投金额降序。

**口径**：`include-statuses` 默认 `PENDING / DONE / UNKNOWN` —— 与资金护栏一致，即「可能已经花了钱」的
都算，避免「实际扣了费但报表里没有」。`FAILED`（接口明确业务失败）不计入。
⚠️ 这也是列表，外部配置里是**整体替换**，要改就写全。

**当天没有任何追投也会出文件**（只有表头 + `H2 = 0`），下游推送程序不必判断「文件不存在」。

**为什么报表不调接口**：抖音名/抖音号在**追投那一刻**就写进了流水，账户名来自配置。
所以订单结束、接口故障、Token 过期都不影响出报表。

**三种触发方式**：

| 触发 | 说明 |
|---|---|
| 定时 | 每日 `cron`（默认 `0 1 0 * * ?` = 00:01:00）生成前一天。**改 cron 要重启服务才生效** |
| 启动补生成 | `catch-up-on-start: true` 时，启动若发现「昨日报表」不存在就补一次。**只补昨天这一天**，更早的缺口不追 |
| `run-now` | 部署后不想等到 00:01：临时改 `true` 重启一次就立刻出表。**验证完记得改回 `false`** |

`run-now` 可配 `run-now-date: 2026-09-23` 指定生成哪一天，不填默认「昨天」。

### 「已结束达标订单」sheet（2026-09-30 新增）

日报现在有**两个 sheet**：

| # | sheet | 内容 | 数据来源 |
|---|---|---|---|
| 0 | `Sheet1`（模板原有） | 当天**被追投过**的订单明细（一行 = 一个订单） | **本地流水**，不调接口 |
| 1 | `已结束达标订单` | 当天**新结束**且 ROI 达标的订单：订单ID / 抖音名 / 抖音号 / 投放账户 / roi | **查接口** |

#### ⚠️ 为什么需要「七天底表」

官方列表接口**只能按创建时间过滤，返回项里没有「结束时间」字段** —— 所以「当天结束的订单」
**无法直接查询**。做法（老板 2026-09-30 定的）：

1. 每天日报生成前，拉一次「近 N 天创建 + 状态 ∈ 配置的结束态集合 + ROI ≥ 阈值」的全量
   （2026-10-04 起只剩 **FINISHED 投放完成**，见下方配置项）；
2. 与上次的**底表**（`finished-orders-store-path`）对比，**多出来的就是新结束的**；
3. 用本次全量**覆盖**底表。

#### ⚠️ 首次运行只建底表、不输出

底表不存在时（首次部署 / 底表被删），程序**只建立底表、不输出「新增」**，
日志会明确打印「底表首次建立（N 单），本次不输出「新增」」。
理由：日报的语义是「每日新增」，把近 N 天的历史订单一股脑塞进当天 sheet 会误导。

#### 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `finished-orders-enabled` | `true` | 关掉就不生成这个 sheet（主表不受影响） |
| `finished-orders-sheet-name` | `已结束达标订单` | 代码会自动清洗非法字符并截到 31 字符 |
| `finished-orders-store-path` | `D:/oceanengine/data/report-finished-orders.jsonl` | 七天底表。⚠️ 必须绝对路径 |
| `finished-orders-lookback-days` | `7` | 回看窗口。⚠️ 接口只能按**创建时间**过滤，创建超过 N 天才结束的订单会漏 |
| `finished-orders-statuses` | `FINISHED` | 视为「已结束」的状态。**2026-10-04 起只要 `FINISHED`（投放完成）**；`FROZEN`（投放终止）/`OVER`（投放结束）不统计。⚠️ 是**列表**，外部配置**整体替换**，要改就写全 |

> **只想改「抓哪些状态」不用改代码**：改目标机 `config/application.yml` 里的
> `finished-orders-statuses` 单项列表 → `restart-service.cmd` 即可（顺带请求数从 18 降到 6）。
> **不要在外部配置里漏写这个 key** —— 列表是整体替换，漏写会回退到 jar 内置默认值。

ROI 阈值与口径**沿用追投配置**（`min-roi` / `roi-metric` / `product-roi-overrides`），不单独配。

#### ⚠️ 与主表的三个差异（都是刻意的）

1. **要调接口**：主表刻意不调任何接口（接口故障不影响出表）。这个 sheet 需要查接口，
   **拉取失败只让它留空 + 打日志，绝不阻塞主表**。
2. **有请求成本**：账户数 × 状态数 = **6 × 1 = 6 次列表请求**（不含翻页；2026-10-04 前三状态时为 18 次），
   在 00:01 与追投轮次并发，**有限流风险**。实测一次约 20 秒。
3. **底表损坏不拒绝启动**：跳过坏行即可（底表丢了最多让 sheet 多列一批历史订单，不涉及资金）。
   对比：消耗快照损坏会拒绝启动（那个丢了会让分支② 失效、筛选变保守漏投，所以宁可停摆）。

#### 实测（2026-09-30，6 个账户真实接口）

| 运行 | 拉取 | 底表原有 | 新增 |
|---|---|---|---|
| 第 1 次 | 85 单 | 无（首次） | **0**（只建底表） |
| 第 2 次 | 85 单 | 85 单 | **0** |
| 第 3 次（人为删掉 6 条底表） | 85 单 | 79 单 | **6** ✅ |
| 第 4 次 | 85 单 | 85 单 | **0** |

拉取明细（当时还是三状态口径）：`ROI ≥ 1.8 达标 85 单，ROI 未达标跳过 1342 单`；
状态分布 `FINISHED 40 / OVER 25 / FROZEN 20`。
⚠️ 2026-10-04 收窄成只抓 `FINISHED` 后，同一批数据里**达标单数会明显变少**（按当时分布只剩 FINISHED 那 40 单，
其中 ROI 达标的更少），底表也会随之只保留 FINISHED 的单 —— **这是预期行为，不是丢数据**。

⚠️ **注意**：这个 sheet **没有「整体消耗下限」过滤**（与追投筛选不同）。
实测底表里有 ROI = 1845 这种极端值，是小消耗订单算出来的虚高 ROI。
如果不想看到这类数据，需要另外加消耗下限过滤（**目前没有，是待确认项**）。

### ⚠️ 日报**不是实时**的：追投成功 → 进日报，中间隔一整天
这是两个不同环节，写入时机完全不同：

| 环节 | 写入时机 | 谁在读它 |
|---|---|---|
| **追投流水**（JSONL） | 追投成功的**那一刻立即写** | 幂等判断、日限额、webhook、日报 |
| **日报**（`.xls`） | **第二天 00:01** 才生成，内容是**前一天**整天 | 下游推送程序 |

**例**：9/29 10:00 追投成功 → 立刻进流水（日期归属 9/29）→
出现在 **9/30 00:01** 生成的
`D:\oceanengine\data\2026-09追投日报\2026-09-29追投明细表.xls` 里。

**为什么**：日报按「一整天」聚合，当天没过完只能是半成品。所以
**想知道实时用量要看流水或 webhook，不要等日报。**

⚠️ 推论：**停机多天只补昨天，中间那几天不会自动补**（更早的缺口不追）。
要补历史某天得用 `run-now` + `run-now-date` 指定。

⚠️ **模板缺失不会导致启动失败**（不连累追投），只在生成报表时报错并写日志。
⚠️ **新增投放账户时记得同步 `advertiser-names`**，否则报表里「投放账户」列显示的是账户 ID。

### 追投阈值回调（`webhook`）怎么配

某订单**当日**追投次数或金额达到阈值时，向下游 POST 一次 JSON，用于触发另一个程序的功能。

**只通知，不改变追投行为** —— 阈值低于日上限时，通知之后订单仍会继续被追投。
默认阈值 5 次 / 1000 元，日上限是 6 次 / 1200 元，即「顶到上限前一步通知」。

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `enabled` | `true` | 总开关。**`url` 为空时启用会导致启动失败** |
| `url` | 已配置（影刀 iPaaS 回调） | 下游地址，必须 `http://` 或 `https://` 开头 |
| `count-threshold` | `5` | 当日追投次数阈值（达到即触发，含等于） |
| `amount-threshold` | `1000` | 当日追投金额阈值（元） |
| `timeout-ms` | `5000` | 单次请求超时（毫秒） |
| `notify-log-path` | `D:/oceanengine/data/reinvest-webhook.jsonl` | 已通知流水，**必须绝对路径** |
| `headers` | 空 | 可选自定义请求头，对方要求鉴权时用 |

⚠️ **`enabled: true` 但 `url` 为空会导致启动失败** —— 这是故意的（启动自检拦截）。
否则会退化成每轮往空地址发请求、只在日志里报错，很难发现。

**行为要点**

- **计数口径与资金护栏完全一致**：`PENDING` / `DONE` / `UNKNOWN` 计入，`FAILED` 不计入。
- **同一订单同一天只发一次**。已通知记录写在 `notify-log-path`，**重启不会重复发**；
  停服期间漏发的，下一轮（约 10 分钟内）会补上。
- **发送失败不算「已通知」**，下一轮自动重试。失败只记日志，**绝不影响追投轮次**。
- **`dry-run: true` 时不发送**。所以演练期间看不到 webhook，这是预期的。

**payload**（`POST` + `Content-Type: application/json`）：

```json
{
  "date": "2026-09-29",
  "orderId": "7690526648109645860",
  "advertiserId": 1823379540880396,
  "awemeName": "某某旗舰店",
  "awemeShowId": "MS4wLjABAAAA...",
  "reinvestCount": 5,
  "reinvestAmount": 1000,
  "reason": "COUNT_AND_AMOUNT",
  "dryRun": false,
  "sentAt": "2026-09-29T10:05:00+08:00"
}
```

**真实运行发出去的与探针样例结构完全相同**（同样 10 个字段、同名、同类型），但**值不同**：

| 字段 | 探针样例 | 真实运行 |
|---|---|---|
| `date` | 运行当天 | 业务日（当天） |
| `orderId` | 固定样例号（字符串） | 真实订单号（**字符串**） |
| `advertiserId` | 固定样例账户 | 该订单归属的**真实账户** |
| `awemeName` | `探针-测试抖音名` | **真实抖音名**（老流水可能为 `null`） |
| `awemeShowId` | `MS4wLjABAAAAprobe` | **真实抖音号**（老流水可能为 `null`） |
| `reinvestCount` | 固定 5 | 真实当日累计次数（**可能 > 5**，见下） |
| `reinvestAmount` | 固定 1000 | 真实当日累计金额 |
| `reason` | `COUNT_AND_AMOUNT` | 按实际达标情况（见下） |
| `dryRun` | 探针手填 `false` | **恒为 `false`** —— dry-run 时根本不发送 |
| `sentAt` | 探针运行时刻 | 真实发送时刻（带时区偏移） |

`reason` 取值：`COUNT`（只有次数达标）/ `AMOUNT`（只有金额达标）/ `COUNT_AND_AMOUNT`（两者同时达标）。
当前默认参数（`amount: 200`、阈值 5 次 / 1000 元）下**恒为 `COUNT_AND_AMOUNT`**，因为 5×200 正好等于 1000。

### ⚠️ 下游要判「次数够没够」，请判 `reinvestCount`，不要判 `reason`

`reason` 说明的是**哪个阈值达标**，**不是次数**。它只在「`amount` 恰好等于 200」这个巧合下
才与「次数 ≥ 5」等价 —— 而 `amount` 是生产参数，改配置即可生效、不用重新打包：

| `amount` 改成 | 第 5 次追投时累计金额 | 那次通知的 `reason` | 若下游判 `reason=="COUNT_AND_AMOUNT"` |
|---|---|---|---|
| 200（当前） | 1000 | `COUNT_AND_AMOUNT` | ✅ 正常 |
| 100 | 500 | `COUNT` | ❌ **永不触发** |
| 250 | 1250 | 第 4 次就到 1000 → `AMOUNT` | ❌ **永不触发**（而且第 4 次就发了） |
| 300 | 1500 | 第 4 次 → `AMOUNT` | ❌ 同上 |

`count-threshold` / `amount-threshold` 本身也是配置项，改动同样会让这个巧合失效。

**正确做法：判 `reinvestCount >= 5`。** 三点理由：

1. 它直接表达「当日追投次数 ≥ 5」，对 `amount` 和两个阈值配置**都免疫**；
2. `reason` **判不出具体次数** —— 即使 `COUNT_AND_AMOUNT` 成立，也无从得知是 5 还是 6；
3. 用 `>=` 而非 `==`：通知是**每订单每天一条、在首次达标的轮次发出**。正常是 5，
   但若那次发送失败，下一轮会重试，此时 `reinvestCount` 可能已经是 6。

⚠️ **同一订单同一天只会收到一条通知。** 所以下游「下一步操作」每天最多执行一次。
如果下游需要「每次追投都触发」，当前设计**不支持**，需要改需求。

⚠️ **字段顺序不是对外承诺**，请**按 key 取值**，不要按位置解析。
`orderId` 是**字符串**（19 位数字按 JSON number 发出去，下游用 JavaScript 解析会丢精度、
末几位变 0 而匹配不到订单 —— **请对方也按字符串处理**）；
`advertiserId` / `reinvestCount` / `reinvestAmount` 是**数字**。

⚠️ **时机**：生产上限是 `max-reinvest-count-per-order-per-day: 6`、
`max-amount-per-order-per-day: 1200`，而阈值是 5 次 / 1000 元 —— 距上限只有 **1 次 / 200 元**。
下游收到通知后请**尽快**执行，否则该订单可能已被限额挡住、不再追投。

**验证对方端点（不花钱）**
生产是 `dry-run=true`，演练模式不写追投流水，所以「真跑一轮看发不发」是验证不了的。
用探针直接发一条样例 payload：

```bash
mvn -o test-compile
mvn -o dependency:build-classpath -Dmdep.outputFile=cp.txt
java -cp "target/test-classes;target/classes;$(cat cp.txt)" \
     com.aolandai.oceanengine.tools.WebhookProbe https://对方地址
```

退出码 0 = 对方返回 2xx；非 0 = 失败（原因会打印出来）。**不产生任何追投、不花一分钱。**

**只看 payload、不打扰对方**（加 `--print`，此时地址可省略）：

```bash
java -cp "target/test-classes;target/classes;$(cat cp.txt)" \
     com.aolandai.oceanengine.tools.WebhookProbe --print https://对方地址
```

打印出来的单行 JSON 就是**真实发送的那些字节**（与 `HttpWebhookSender` 共用同一段序列化代码，
不存在「打印的和发的不是一回事」）。会顺带给一份缩进易读版，并提示 `orderId` 必须是字符串。

> 控制台偶尔把中文显示成 `Ä¿±ê` 这种是**抓取端的显示假象，不影响 payload**（已隔离测试确认：
> 加不加编码参数输出都一样）。要可靠核对就把输出重定向到文件再看，别信控制台。
> 若报 `WebhookProbe.<clinit>`，是 `-cp` 里漏了 `cp.txt`，不是代码问题。

**本地起一个接收端，端到端联调（推荐，不打扰对方）**

`tools/webhook_receiver.py` —— 一个只解析、只返回、**不写任何文件**的本地接收端：

```bash
# 终端 A：起接收端（默认 127.0.0.1:8099）
python tools/webhook_receiver.py

# 终端 B：用探针把真实格式的样例打过去
java -cp "target/test-classes;target/classes;$(cat cp.txt)" \
     com.aolandai.oceanengine.tools.WebhookProbe http://127.0.0.1:8099/
```

接收端会按字段逐个打印 payload，并把 `reinvestCount` 用 `<<<<` 标出来；响应体是
`{"ok":true,"reinvestCount":5,"orderId":"...","date":"...","reason":"..."}`。
坏 JSON 返回 400，缺字段时 `reinvestCount` 为 `null`（不会崩）。**只用标准库，无需 pip install。**

要用它模拟「下游怎么判」，把接收端打印的 `reinvestCount` 与 5 比一下即可 ——
**别去判 `reason`**（原因见上一节）。

### ⚠️ PowerShell 下 POST 的正确姿势（`curl` 会踩坑）

PowerShell 里 `curl` 是 `Invoke-WebRequest` 的**别名**，`-H` / `-d` 不是它的参数，会直接报
`无法绑定参数"Headers"`。改用 `curl.exe` 又会踩第二个坑：**PS 5.1 传参给原生 exe 时会吞掉
JSON 里的双引号**，服务端收到的是 `{orderId:769052...,reinvestCount:9}`（引号全没了）→ 400。

**✅ 推荐：用 PowerShell 原生 `Invoke-RestMethod`（没有引号地狱）**

```powershell
Invoke-RestMethod -Uri 'http://127.0.0.1:8099/' -Method Post `
  -ContentType 'application/json' -Body '{"orderId":"7690526648109645860","reinvestCount":7,"reason":"COUNT"}'
```

返回的是**已解析的对象**，直接 `$r.reinvestCount` 取即可。
**带中文的 payload 要传 UTF-8 字节**，否则 PS 5.1 会按默认编码发出去：

```powershell
$json  = '{"orderId":"7690526648109645860","awemeName":"某某旗舰店","reinvestCount":12}'
$bytes = [Text.Encoding]::UTF8.GetBytes($json)
Invoke-RestMethod -Uri 'http://127.0.0.1:8099/' -Method Post `
  -ContentType 'application/json; charset=utf-8' -Body $bytes
```

**✅ 备选：坚持用 `curl.exe`，则内层双引号必须转义成 `\"`**

```powershell
curl.exe -s --noproxy '*' -X POST http://127.0.0.1:8099/ `
  -H "Content-Type: application/json" `
  -d '{\"orderId\":\"7690526648109645860\",\"reinvestCount\":11,\"reason\":\"COUNT\"}'
```

（实测：不转义 → 400；转义 → 正常。用 `--%` 停止解析**也救不了**，引号照样被吞。）

**两条通用提醒**

- **续行符不是 `\`** —— 那是 bash。PowerShell 用**反引号** `` ` ``，或者干脆写成一行。
- **`--noproxy '*'` 建议带上**：本机若配了系统代理，`127.0.0.1` 可能被绕去代理。
- 看到中文变成 `涓嶆槸鍚堟硶` 这类乱码，是 **PS 控制台按 GBK 解码了 UTF-8 输出**（显示问题，
  服务端收到的字节是对的）。想看清就先执行 `[Console]::OutputEncoding = [Text.Encoding]::UTF8`。

**排查**

| 现象 | 原因 |
|---|---|
| 服务起不来，报 webhook 配置不合法 | `enabled: true` 但 `url` 没填 / 不是 http(s) 开头 |
| 一直不发 | ① `dry-run: true`；② `enabled: false`；③ 当日用量还没到阈值 |
| 日志里反复「通知失败」 | 对方端点不可达或返回非 2xx。看日志里的 HTTP 状态码与响应体前 200 字符 |
| 重启后收到重复通知 | 检查 `notify-log-path` 是否绝对路径 —— 相对路径会随工作目录漂移，导致读不到历史 |

### ⚠️ 外部配置是「按 key 覆盖」，列表参数是「整体替换」

外部 `config\application.yml` **不会整份替换** jar 内置配置，而是按 key 逐个覆盖：没写的 key 保留内置值。实测确认（外部只写 `amount: 999`，账户列表、间隔、投放时长都仍是内置值）。

**但列表类型是个例外，它整体替换、不合并：**

```yaml
# 内置有 3 档 6 个账户
advertiser-tiers:
  - name: 10000档
    max-amount-per-advertiser-per-day: 10000
    advertiser-ids: [1779106144470100, 1778533513439244, ...]
  # ...共 3 档
```

```yaml
# 外部只写了 1 档 1 个账户 → 结果就只剩这 1 档 1 个账户
# 另外 2 档 5 个账户被静默丢弃，不报错
advertiser-tiers:
  - name: 只剩一个
    max-amount-per-advertiser-per-day: 10000
    advertiser-ids: [1111111111111111]
```

（2026-09-28 实测确认：启动日志打出「账户 1 个 / 1 档」，退出码 0，没有任何警告。）

**在外部配置里写 `advertiser-tiers` 时，必须把要追投的档次和账户全部列出来。** 漏掉的账户不会报错，只是再也不追投了——启动自检只校验「档次非空、名称非空、账户不跨档重复、日限额 ≥ 单笔金额」，**校验不出漏写**。改动档次后，务必对照启动日志打印的「分档:」那一行核对一遍：

```text
分档: 10000档=5 个账户/日上限 10000 元, 20000档=1 个账户/日上限 20000 元, 15000档=0 个账户/日上限 15000 元
```

> **从旧配置迁移**：旧版的顶层 `advertiser-ids` 和 `max-amount-per-advertiser-per-day` 已**不再被识别**，写在配置里会被静默忽略（不会报错）。必须改写成 `advertiser-tiers`，否则程序会直接用 jar 内置的档次配置——**内置配置里的账户不一定是你要投的账户**。

### 配置优先级（高 → 低）

1. **命令行参数** —— `oceanengine-service.xml` 里 `<arguments>` 中的 `--xxx`
2. **外部配置** —— `D:\oceanengine\config\application.yml`
3. **jar 内置配置** —— `src\main\resources\application.yml`

实测：外部配 `amount: 999`，命令行再传 `--suixintui.reinvest.amount=777` → 生效值是 **777**。所以排查「改了配置没生效」时，第 3 步一定要看 `<arguments>` 有没有命令行覆盖项。

程序启动时会做配置自检（spring-boot-starter-validation）：金额上限小于单笔追加金额、`delivery-time` 不是官方允许值、档次为空/档次名称为空/账户跨档重复/某档日限额小于单笔金额时，**启动即失败**，报错形如：

```text
Binding to target com.aolandai.oceanengine.scheduler.SuixintuiReinvestProperties failed:
    Property: suixintui.reinvest.safetyLimitsConsistent
    Value: "false"
    Reason: 资金上限/档次配置不合法：日限额不得小于单笔追投金额，delivery-time 必须为官方允许值，
            档次不得为空、名称必填、账户不得跨档重复
```

遇到"启动后立即退出且退出码非 0"，先看启动日志里的校验错误信息，不要直接怀疑 Token 或网络。

常用参数（金额、间隔、投放时长、ROI）都写在本文件里，改完执行 `restart-service.cmd`（或 `.\oceanengine-service.exe restart`）即可生效，不需要重新打包。

## 7. 配置 Token

程序运行必须读取环境变量：

```text
QIANCHUAN_SERVICE_BEARER_TOKEN
```

推荐通过 Windows 图形界面配置：

1. 打开“系统属性”
2. 选择“高级”
3. 点击“环境变量”
4. 在“系统变量”中新建 `QIANCHUAN_SERVICE_BEARER_TOKEN`
5. 值填入服务端 Bearer Token
6. 重启 PowerShell 或 Windows 服务（环境变量是进程启动时读取的，改完必须重启服务才生效）

⚠️ 必须建在**系统变量**里，不要建在「用户变量」里：服务以 LocalSystem 账户运行，读不到用户变量。安装脚本会检查这一项。

不要把 Token 写进 `application.yml`、提交到 Git，或直接写进公开的 `.bat` 文件。

验证变量是否存在时不要打印 Token 内容：

```powershell
if ([string]::IsNullOrWhiteSpace($env:QIANCHUAN_SERVICE_BEARER_TOKEN)) {
    throw 'QIANCHUAN_SERVICE_BEARER_TOKEN 未配置'
}
Write-Host 'Token environment variable is configured.'
```

## 8. 第一次单轮演练

以 PowerShell 执行：

```powershell
Set-Location D:\oceanengine

java -jar .\oceanengine-1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:D:/oceanengine/config/ `
  --suixintui.reinvest.run-once=true
```

确认：

- 程序能正常启动
- 能获取 Token
- 能访问千川接口
- 输出的账户和候选订单正确
- 启动日志开头包含 `dry-run=true`（还会打印账户分档、账户总数、每单追加金额、轮次间隔、流水文件绝对路径，便于核对配置是否生效）
- 程序执行一轮后自动退出

演练没有问题后，再将外部配置改为 `dry-run: false`，重复单轮验证。真实执行前必须人工确认金额和账户。

## 9. 手动常驻运行

```powershell
Set-Location D:\oceanengine

java -jar .\oceanengine-1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:D:/oceanengine/config/
```

程序启动 10 秒后执行第一轮，之后每轮结束后等待配置的间隔时间。按 `Ctrl+C` 停止。

## 10. 使用 WinSW 注册为 Windows 服务（后台常驻）

不再使用「任务计划程序 + .cmd」。改用 WinSW 把程序注册成真正的 Windows 服务：崩溃自动重启、开机自启、日志自动按大小滚动、停机时先让当前轮次跑完。

### 10.1 准备 WinSW

按第 5 节把 `oceanengine-service.exe`（由 `WinSW-x64.exe` 重命名而来）和 `oceanengine-service.xml` 放到 `D:\oceanengine\`。

XML 里只有一处必须改：`<executable>` 指向的 JDK 17 路径。用下面命令确认实际路径：

```powershell
where.exe java
```

### 10.2 关键配置说明

`deploy\oceanengine-service.xml` 已写好下列配置，这里说明为什么这么设：

| 配置 | 值 | 原因 |
|---|---|---|
| `<stoptimeout>` | `120 sec` | 默认只有 15 秒。一轮要取 Token + 遍历全部账户，可能跑几分钟。WinSW 会先给 JVM 发 Ctrl+C 让它优雅退出，超时才强杀 |
| `<onfailure>` | `restart` / `60 sec` | 进程非 0 退出时每 60 秒重启一次 |
| `<resetfailure>` | `1 hour` | 连续运行满 1 小时后失败计数归零 |
| `<log mode="roll-by-size">` | 10MB × 30 个 | 只捕获 java 的 stdout/stderr，写到 `oceanengine-service.out.log` / `.err.log`。**业务输出全在这里**（代码里没有 Logger），所以留存时长由 `keepFiles` 决定 |
| `<startmode>` | `Automatic` + 延迟启动 | 开机自启，延迟约 2 分钟，避免与其他开机服务抢资源 |

**Token 不写在 XML 里。** 服务进程会继承 Windows 的「系统」环境变量，java 通过 `System.getenv("QIANCHUAN_SERVICE_BEARER_TOKEN")` 直接读到。必须配置成**系统变量**而非用户变量，否则 LocalSystem 账户读不到。

不要改写成 `<env value="%QIANCHUAN_SERVICE_BEARER_TOKEN%"/>` 的形式：变量未定义时 WinSW 不做替换，会把字面量当令牌传下去，报出来的是「HTTP 状态码：401」而不是「未配置环境变量」，更难排查。

**日志不再双写。** WinSW 只捕获进程的 stdout/stderr，落到 `oceanengine-service.out.log`；logback 写 `application.log`。两个文件不同名，不会互相干扰（原来的 `.cmd` 把 stdout 重定向到 logback 正在写的同一个文件，会打架）。

### 10.3 安装并启动

以**管理员身份**运行：

```text
D:\oceanengine\install-service.cmd
```

脚本会先检查 WinSW 本体、jar、外部配置、Token 环境变量是否齐全，再执行安装和启动；任一项缺失会直接报错退出，不会装出一个起不来的服务。

也可以手动执行：

```powershell
Set-Location D:\oceanengine
.\oceanengine-service.exe install
.\oceanengine-service.exe start
```

### 10.4 常用命令

```powershell
Set-Location D:\oceanengine

.\oceanengine-service.exe status      # 查询状态
.\oceanengine-service.exe restart     # 重启（改了配置后用）
.\oceanengine-service.exe stop        # 停止
.\oceanengine-service.exe uninstall   # 卸载
```

改完 `config\application.yml` 后双击 `restart-service.cmd` 即可生效；日常还有三个脚本可双击：`stop-service.cmd`（停止）、`start-service.cmd`（启动）、`status.cmd`（一键看运行状态，见第 12 节）。卸载双击 `uninstall-service.cmd`（只卸载服务，不删数据文件）。`sc query oceanengine` 也能查状态。

当前程序已经有轮次文件锁，但生产环境仍然只启动一个实例——**不要**在多台电脑上同时运行同一套账户配置。

### 10.5 停机时确认当前轮次跑完了

停止服务后查看**业务日志**（注意是 `.out.log`，不是 `application.log`，原因见第 12 节）：

```text
D:\oceanengine\logs\oceanengine-service.out.log
```

如果看到轮次跑到一半被中断，把 `<stoptimeout>` 调大，并在 `config\application.yml` 里加上：

```yaml
spring:
  task:
    scheduling:
      shutdown:
        await-termination: true
        await-termination-period: 110s
```

默认情况下 Spring 不会等待正在执行的定时任务，加了这段才会等它跑完。**这一项未在本机实测**，第一次停机时请对照日志确认是否生效。

## 11. 健康检查

程序运行后，在目标电脑执行：

```powershell
Invoke-WebRequest http://127.0.0.1:8080/actuator/health
```

> 端口绑在回环网卡上，**必须在目标电脑本机执行**这条命令；从别的机器访问会连接失败，这是预期行为，不是故障。

正常结果应包含：

```json
{"status":"UP"}
```

注意：`show-details: never` 不会显示明细。如果返回 `{"status":"DOWN"}`，说明最近一轮追投出错（程序活着但轮次失败），原因看日志：

```text
D:\oceanengine\logs\application.log
```

> 嫌麻烦的话，直接双击 `D:\oceanengine\status.cmd`，它会把健康检查连同服务状态、进程、端口、最近轮次一起打印出来（见第 12 节）。

## 12. 日常操作

### 一键查看运行状态（推荐）

直接双击 `D:\oceanengine\status.cmd`，或在命令行执行：

```bat
D:\oceanengine\status.cmd
```

它依次输出 6 段：

| 段 | 内容 | 正常表现 |
|---|---|---|
| 1. 服务状态 | `Get-Service` 结果 | `状态: Running  启动类型: Automatic` |
| 2. 进程 | 包装进程 + java 进程 | 两行都 `[OK]` |
| 3. 端口监听 | `netstat` 过滤 `127.0.0.1:8080` | 一行 `LISTENING` |
| 4. 健康检查 | 调 `/actuator/health` | `[OK] HTTP 200 {"status":"UP"}` |
| 5. 最近轮次 | 从 `.out.log` 抓 `[汇总]` / `[轮次中止]` / `[日报]` | 有轮次汇总行 |
| 6. 日志文件 | `err.log` 是否为空 + 日志列表 | `[OK] err.log 为空` |

**日常巡检只看这一个脚本的输出就够了。**

> ⚠️ `status.cmd` 和 `deploy\` 下其他 `.cmd` 一样，**必须保持 ANSI/GBK 编码 + CRLF 换行**。
> 用记事本 / VSCode 改完如果另存成了 UTF-8，双击会报「'xxx' 不是内部或外部命令」（症状与修复见第 13 节）。
> **要改这些脚本，请用能指定编码的方式保存，或直接用仓库 `deploy\` 下的同名文件覆盖。**

查看服务状态：

```powershell
sc query oceanengine
```

或：

```powershell
Get-Service oceanengine
```

### 停止 / 启动 / 重启服务（需要管理员权限）

**最省事：双击对应脚本**（都要「以管理员身份运行」）：

| 想做什么 | 双击这个 | 说明 |
|---|---|---|
| 停止 | `stop-service.cmd` | 停完自动打印服务状态和端口是否释放 |
| 启动 | `start-service.cmd` | 启动后自动等 10 秒，再显示端口是否已监听 |
| 重启 | `restart-service.cmd` | 改了 `config\application.yml` 后用这个 |
| 卸载 | `uninstall-service.cmd` | 停止 + 注销服务，**不删数据文件** |

**或者直接敲命令**：

```powershell
Set-Location D:\oceanengine
.\oceanengine-service.exe stop
.\oceanengine-service.exe start
.\oceanengine-service.exe restart
```

等价的原生 PowerShell 命令（同样需要管理员）：

```powershell
Stop-Service oceanengine
Start-Service oceanengine
Restart-Service oceanengine
```

#### ⚠️ 停服时的行为（重要）

- WinSW 的 `<stoptimeout>` 是 **120 秒**：发停止信号后最多等 120 秒让进程自己收尾，超时才强杀。正常几秒退完。
- **Spring 默认不等待正在执行的定时任务**（本项目也没配 `spring.task.scheduling.shutdown.await-termination`），
  所以**如果刚好停在一轮追投中间，那一轮会被打断**。
- **被打断不会造成重复扣费**：每次追投都先写本地 JSONL 流水，重启后按流水做幂等判断，
  已写进流水的不会追第二次。→ **改配置时随手重启是安全的，不需要等某一轮跑完。**
- **停服后服务不会自动再起**（它只是开机自启）。要恢复就双击 `start-service.cmd`。

停完确认（`stop-service.cmd` 会自动打印这几项）：

```powershell
Get-Service oceanengine          # 应为 Stopped
netstat -ano | findstr 8080      # 应无输出，端口已释放
```

日志有两个来源，各自独立滚动，都不需要人工清理：

| 文件 | 内容 | 滚动策略 |
|---|---|---|
| `logs\application.log` | **框架日志**（Spring 启动、Tomcat、异常栈等，logback 写）。应用代码里**没有任何 Logger 调用**，所以这个文件几乎不增长——一次完整启动约 2KB | 单文件 100MB；保留 30 天；归档总量上限 2000MB |
| `logs\oceanengine-service.out.log` | **进程 stdout**，包括业务提示行：`===== 随心推追投服务启动 =====`、`dry-run=...`、`日报: ...`、`[日报] 完成：...`、`[轮次中止]`、`轮次结束: 轮次 R... 候选 x 成功 y 失败 z`。**真正会增长的是这个文件** | 超过 10MB 滚动，保留 30 个（合计约 300MB，实测约 650KB/天，够存约 1 年） |
| `logs\oceanengine-service.err.log` | **进程 stderr**（JVM 自身启动失败、未捕获异常） | 同上 |

> 想延长追投记录的留存时间，要调的是 `oceanengine-service.xml` 里 `<log mode="roll-by-size">` 的 `sizeThreshold` / `keepFiles`，**不是** logback 的 rollingpolicy——后者管的是那个几乎不长的 `application.log`。

### ⚠️ 业务日志在哪，容易找错

业务提示行是用 `System.out.println` 打的，**不走 logback，所以不会进 `application.log`**，只在 `.out.log` 里。

实测对照（同一次运行）：`application.log` 只有 12 行框架日志，而控制台输出 27 行——差的那 15 行就是启动横幅和业务提示。

**所以要看追投结果（候选/成功/失败/金额），必须看 `oceanengine-service.out.log`，看 `application.log` 是找不到的。**

排查入口对照：

| 症状 | 看哪个文件 |
|---|---|
| 配置校验失败、启动异常栈 | `application.log` |
| 追投轮次结果、候选订单数、实际执行情况 | `oceanengine-service.out.log` |
| 日报是否生成成功、生成到了哪个路径 | `oceanengine-service.out.log`（找 `[日报]` 开头的行） |
| 服务起不来、JVM 直接崩 | `oceanengine-service.err.log` |

查看命令（`-Wait` 相当于 `tail -f`，Ctrl+C 退出）：

```powershell
Get-Content D:\oceanengine\logs\oceanengine-service.out.log -Tail 50 -Wait
Get-Content D:\oceanengine\logs\application.log -Tail 50 -Wait
```

手动运行时（第 8、9 节）日志直接打在控制台，**不写文件**。想留档就自己重定向：

```powershell
java -jar .\oceanengine-1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:D:/oceanengine/config/ `
  2>&1 | Out-File -FilePath D:\oceanengine\logs\manual-run.log -Encoding utf8
```

> 别用 `>`：PowerShell 5.1 的 `>` 默认写 UTF-16LE，中文看起来会不正常。

日志是**逐行及时落盘**的（不是退出时才刷），所以 `Get-Content -Wait` 可以实时跟踪。

升级版本前：

1. 停止服务：`.\oceanengine-service.exe stop`
2. 备份 `config\application.yml`
3. 备份 `data\reinvest-records.jsonl`
4. 替换 `D:\oceanengine\oceanengine-1.0-SNAPSHOT.jar`
5. 确认日报模板仍在 `data\currentreport\每日追投明细表模板.xls`（新版本可能换了模板要求）
6. 先以 `dry-run: true` 按第 8 节手动跑一轮验证
7. 确认无误后改回 `dry-run: false`，执行 `.\oceanengine-service.exe start`

不要删除 `reinvest-records.jsonl`，否则会丢失幂等和金额限制记录，可能造成重复追投风险。

### 手动补某一天的报表

自动补生成只补「昨天」。要补更早的日期，改外部配置里的两个值再重启服务即可：

```yaml
suixintui:
  report:
    run-now: true
    run-now-date: 2026-09-20    # 要补哪一天，不填默认「昨天」
```

```powershell
Set-Location D:\oceanengine
.\oceanengine-service.exe restart
Get-Content D:\oceanengine\logs\oceanengine-service.out.log -Tail 20
```

应当看到 `[日报] 完成：日报 2026-09-20 | ...`。

**补完必须把 `run-now` 改回 `false` 并再重启一次**，否则以后每次重启都会重新生成这一天的报表。

⚠️ **已存在的报表会被覆盖**，重跑同一天就是重新生成一份。

> 报表是从**本地流水文件**（`record-store-path`）算出来的，不是从接口查的。
> 所以只要流水文件里还有那一天的记录，任何历史日期都能补；流水文件被清理掉的日子就补不回来了。

## 13. 常见问题

### 双击 `install-service.cmd` 报「'xxx' 不是内部或外部命令」

例如：

```text
'PORT_TEMPLATE' 不是内部或外部命令，也不是可运行的程序
'not' 不是内部或外部命令，也不是可运行的程序
'态查询：sc' 不是内部或外部命令，也不是可运行的程序
```

**这不是权限问题，也不是脚本写错了**，而是 `.cmd` 文件的**编码/换行**被改坏了。cmd.exe 对批处理文件有两个硬要求：

| 要求 | 说明 |
|---|---|
| 换行必须是 **CRLF** | LF-only 会让 cmd 解析错位 |
| 编码必须是 **ANSI/GBK**（中文系统），且**不要 `chcp 65001`** | cmd.exe 在 65001 代码页下读批处理存在缓冲区错位 bug，中文行的后半截会被当成命令执行 |

**判断当前文件是什么编码：**

```powershell
# 看换行符：应当每个 \n 前面都有 \r
$b = [IO.File]::ReadAllBytes('D:\oceanengine\install-service.cmd')
"CRLF={0}  单独LF={1}" -f ([regex]::Matches([Text.Encoding]::Latin1.GetString($b), "`r`n").Count), (($b | Where-Object {$_ -eq 10}).Count)
```

**修复办法**（在开发机上改好再复制过去）：用 VS Code / Notepad++ 打开 → 右下角编码改成 `GB2312`/`ANSI` → 换行改成 `CRLF` → 保存。

> ⚠️ **以后编辑 `deploy\` 下的 `.cmd` 文件，务必确认编码是 GBK、换行是 CRLF。**
> 用 UTF-8 保存、或把 LF 提交进仓库，都会让目标机上双击运行失败。
> `.cmd` 里也**不要写 emoji**（`⚠️` 这类），同样会触发解析错位。

### 启动后退出，退出码为 1

优先检查：

```powershell
java -version
if ([string]::IsNullOrWhiteSpace($env:QIANCHUAN_SERVICE_BEARER_TOKEN)) { 'Token missing' }
```

再查看启动日志。最常见原因是 Token 未配置、外部配置路径错误、端口 8080 被占用或网络无法访问 Token 服务。

### 服务每 60 秒重启一次

这是 `<onfailure action="restart" delay="60 sec"/>` 在起作用：进程以非 0 退出后 WinSW 会不断重启它。WinSW 没有「重试 N 次后放弃」的选项，所以只要根因没解决就会一直循环。

**不要**因此怀疑 Token 或网络，先看日志里的真正原因：

```text
D:\oceanengine\logs\application.log          业务日志（配置校验错误在这里）
D:\oceanengine\logs\oceanengine-service.err.log   JVM 自身的报错
```

如果是配置校验错误，见下一节。

### 服务状态是 STOPPED，或启动后立刻停止

```powershell
sc query oceanengine
Get-Content D:\oceanengine\logs\oceanengine-service.err.log -Tail 50
```

常见原因：

- `<executable>` 里的 JDK 路径不对 → 服务会因找不到可执行文件直接失败，此时 `.err.log` 里可能什么都没有，改用 `where.exe java` 核对路径
- Token 配成了「用户变量」而不是「系统变量」→ LocalSystem 账户读不到，程序报「未配置环境变量 QIANCHUAN_SERVICE_BEARER_TOKEN」
- 配置了 Token 之后没有重启服务 → 环境变量是进程启动时读取的

### 改了 config\application.yml 但没生效

1. 确认改的是 `D:\oceanengine\config\application.yml`，不是 jar 内置的那份
2. 确认改完执行了 `.\oceanengine-service.exe restart`
3. 检查 `oceanengine-service.xml` 的 `<arguments>` 里有没有命令行覆盖项——**命令行参数优先级高于 yml**，会盖掉你的修改
4. 启动日志开头会打印实际生效的**分档、各项资金上限**、账户总数、每单金额、轮次间隔和流水文件绝对路径，对照它确认：

   ```text
   dry-run=true, 账户 6 个 / 3 档
   分档: 10000档=5 个账户/日上限 10000 元, 20000档=1 个账户/日上限 20000 元, 15000档=0 个账户/日上限 15000 元
   资金上限: 单轮 30000 元（所有账户共享）, 单订单/天 1200 元, 单账户/天 见上面的分档
   每单 +200 元 / 24.0 小时, 每 600 秒一轮, 流水文件=D:\oceanengine\data\reinvest-records.jsonl
   ```

   > **这三行就是「实际生效值」的唯一权威来源。** 拿不准配置有没有生效时，直接和这三行对，不要凭配置文件里的文字判断。

### 启动日志里的值和配置文件不一致

按可能性排序排查：

1. **改的是 `src\main\resources\application.yml`，但没有重新打包** —— jar 内嵌的还是旧值。这个文件同时是开发默认值和生产模板，改了不打包对运行中的服务毫无影响
2. **改的是 jar 内置那份，但目标机上存在外部 `config\application.yml` 覆盖** —— 外部配置优先
3. **`<arguments>` 里有命令行覆盖** —— 命令行优先级最高
4. **外部配置文件里的值确实生效了，只是你看到的是另一份文件** —— 用启动日志的三行对，别猜

### 启动立即失败，提示配置校验错误

如果日志中出现类似"资金上限/档次配置不合法"的校验信息，是外部 `application.yml` 配置违反了启动自检规则。检查：

- 每档的 `max-amount-per-advertiser-per-day` 是否都 ≥ `amount`
- `max-amount-per-order-per-day` / `max-amount-per-round` 是否都 ≥ `amount`
- `delivery-time` 是否为 24/48/72/96/120/144/168 之一
- `advertiser-tiers` 是否为空、档次 `name` 是否为空、是否有账户同时出现在两个档次里
- 是否还在用旧版的顶层 `advertiser-ids` / `max-amount-per-advertiser-per-day`（已不再识别，需改写成 `advertiser-tiers`）
- `report.cron` 是否为合法的 6 段 cron（秒 分 时 日 月 周），且没留空 —— 留空或写错会直接启动失败

修正配置后重新启动。

### 某个账户突然不再追投了

先看启动日志的「分档:」那一行。最常见原因是**外部配置里的 `advertiser-tiers` 没写全**——列表是整体替换，漏写的账户会被静默丢弃、不报错。其次看轮次日志里该账户的标题行是否带「⚠️ 未配置档次」，那说明它不在任何档次里。

### 报表没生成，或者生成到了别的地方

按顺序查：

1. **`.out.log` 里有没有 `[日报]` 开头的行**（不是 `application.log`，业务日志都在 `.out.log`）。三种可能：
   - `[日报] 已停用` → `suixintui.report.enabled` 是 `false`
   - `[日报] 昨日（...）报表已存在，跳过补生成` → 正常，不需要重复生成
   - `[日报] 生成失败（...）：...` → 后面跟的就是原因，最常见是**模板路径不对**
2. **看启动横幅的「日报:」那一行**，它打印的是实际生效的 cron、输出示例路径、模板路径、计入状态。输出的目录和文件名以这一行为准，不要凭 `config\application.yml` 里的文字判断。
3. **确认 `template-path` 指向的文件真的在**。模板不存在不会阻止服务启动，只会让报表生成失败：

   ```powershell
   Test-Path "D:\oceanengine\data\currentreport\每日追投明细表模板.xls"
   ```
4. **确认目录写权限**。服务以 LocalSystem 运行，`D:\oceanengine\data` 需要可写。
5. **确认到点那一刻服务在运行**。00:01 时服务是停的就会漏这一天——下次启动时 `catch-up-on-start` 只会补「昨天」，**更早的缺口不会追**，需要手工把 `run-now` 打开并配 `run-now-date` 指定日期补。

### 报表里「投放账户」列显示的是数字（账户 ID）

说明这个账户没配到 `suixintui.report.advertiser-names`。日志里会有一行：

```text
[日报提醒] 以下账户没配投放账户名，报表里显示的是账户 ID：[1823379540880396]；请在 suixintui.report.advertiser-names 里补上。
```

按提示在外部配置里补上 `账户ID: 账户名` 即可，**不用重新打包**，改完 `restart-service.cmd`。

### 报表里的抖音名 / 抖音号是空的

抖音名、抖音号是**追投发生时**写进流水的。所以：

- 只有**本次升级之后**发生的追投才带这两个字段，升级前的历史流水是空的（属正常，补不回来）
- 若新发生的追投也是空的，说明上游订单列表接口没有返回 `aweme_info`，需要查接口返回

### 启动立即失败，报 "Management-specific server address cannot be configured"

```text
java.lang.IllegalStateException: Management-specific server address cannot be configured
as the management server is not listening on a separate port
```

说明有人把 `management.server.address` 单独加了进来，但没有同时给 actuator 配独立端口。两个改法二选一：

- **推荐**：删掉 `management.server.address`，改用 `server.address: 127.0.0.1`（本项目没有业务 HTTP 接口，这样就已经把健康检查收到本机了）
- 或者同时配 `management.server.port: 8081` 让它真的单独监听

### 端口 8080 被占用

```powershell
Get-NetTCPConnection -LocalPort 8080 -ErrorAction SilentlyContinue
```

可以停止占用端口的进程，或者在外部 `application.yml` 中修改 `server.port`。

### 不能获取 Token

确认目标电脑能够访问：

```text
https://aocilenda.cn/qianchuan/callback?action=token&state=shop_suixintui
```

同时确认环境变量值没有多余空格、换行或引号。

### 重启后出现重复或限额异常

确认 `record-store-path` 指向固定的 `D:\oceanengine\data\reinvest-records.jsonl`，不要使用随着工作目录变化的相对路径。

### 多台电脑同时运行

不要让同一套账户配置在两台电脑同时执行。当前文件锁主要用于同一台电脑上的实例互斥，不能替代跨机器分布式锁。

## 14. 推荐上线顺序

```text
安装 JDK 17
  -> 复制 JAR、外部配置和 deploy 下的服务文件
  -> 放置日报模板（data\currentreport\每日追投明细表模板.xls）
  -> 下载 WinSW-x64.exe 并重命名为 oceanengine-service.exe
  -> 复核关键配置（interval-ms、金额上限、delivery-time、账户分档 advertiser-tiers）
  -> 复核日报配置（report.template-path 指向的模板真的在、advertiser-names 覆盖了全部账户）
  -> 配置 Token（系统环境变量）
  -> dry-run 单轮（手动，第 8 节）
  -> 临时把 report.run-now 改成 true 再跑一次，确认日报能出表（见下方「验证日报」）
  -> 把 report.run-now 改回 false
  -> 安装并启动服务（install-service.cmd，此时仍是 dry-run）
  -> dry-run 常驻观察
  -> 备份流水文件
  -> 小额度真实单轮
  -> 改 dry-run: false 并重启服务
  -> 真实常驻运行
```

### 验证日报（推荐在 dry-run 阶段就做掉）

1. 确认模板在位：

   ```powershell
   Test-Path "D:\oceanengine\data\currentreport\每日追投明细表模板.xls"
   ```

2. 把外部配置里的 `report.run-now` 临时改成 `true`（`enabled` 保持 `true`），重启服务：

   ```powershell
   .\restart-service.cmd
   ```

3. 看 `.out.log` 里的 `[日报]` 行，应当出现：

   ```text
   [日报] 开始生成（run-now 手动触发）：2026-09-27
   [日报] 完成：日报 2026-09-27 | 订单 0 个 | 追投 0 次 | 合计 0 元 | 文件 D:\oceanengine\data\2026-09追投日报\2026-09-27追投明细表.xls
   ```

   （真实上线前还没有流水，订单 0 是正常的——重点看**文件有没有生成、路径对不对**。）

4. 打开生成的 `.xls`，确认表头在、`H2` 是合计、订单 ID 是文本（左上角有绿色三角或左对齐）。

5. **把 `report.run-now` 改回 `false` 并再次重启**，否则以后每次重启都会重新生成一次报表。

回滚方式：`.\oceanengine-service.exe stop`，把 `config\application.yml` 改回 `dry-run: true`，再 `start`。
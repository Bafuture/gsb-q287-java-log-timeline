# 多源日志时间线重建组件

Pair-wise GSB 标注任务仓库（第 17 批 / 287）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们排查问题时要把多个服务的日志合到一起看，但各机器时钟有偏差，顺序经常是错的。请从零实现一个多源日志时间线重建组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持从多个来源读取带时间戳与来源标识的日志条目，时间戳可能因时钟偏差而不一致；2) 支持时钟偏差估计：利用跨来源的因果标记（例如请求标识在多个服务中的先后）估算各来源相对基准的偏移；3) 支持时间线重建：按校正后的时间排序输出，同一请求的日志要保持因果顺序不被颠倒；4) 支持因果关系提取：同一请求标识下的条目之间建立先后关系，用于约束排序；5) 支持排序不确定性标注：校正后仍无法确定先后的条目要成组标注为并发，而不是随意排序；6) 支持统计：条目数、来源数、估计偏移量、被纠正的顺序数与不确定性组数；7) 说明该方案的局限（因果标记不足时无法校正），并给出最小因果标记量的建议；8) 测试覆盖偏移估计、时间线重建、因果顺序保持、不确定性标注、局限性说明与统计；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

## 实现说明：多源日志时间线重建

核心代码位于 `src/main/java/com/example/gsb/timeline/`，无任何外部依赖。

### 快速上手

```java
List<LogEntry> entries = List.of(
    new LogEntry(1000, "frontend", "r1", 1L, "send",    0),
    new LogEntry(6000, "backend",  "r1", 2L, "receive", 0)); // backend 时钟快 5000ms

Timeline timeline = new TimelineReconstructor().reconstruct(entries);
timeline.groups();          // 排序后的组（单元素=确定，多元素=并发）
timeline.skewEstimation();  // 各来源偏移（corrected = raw - offset）
timeline.stats();           // 条目数/来源数/偏移/被纠正顺序数/不确定性组数
```

也可以从多个 `LogSource` 直接合并读取（`InMemoryLogSource`、`TextLogSource`，
文本行格式：`epochMillis|source|requestId|hopIndex|message`，缺省字段用 `-`）。

### 算法

1. **因果提取（`CausalityGraph`）**：两类 happens-before 边——
   同一来源按源内 `sequence` 排序（单时钟自洽）；同一 `requestId` 按 `hopIndex`
   排序（如 client send → server receive）。
2. **偏差估计（`SkewEstimator`）**：对跨来源因果边 u(a, tA) → v(b, tB)，
   由 `corrected(u) <= corrected(v)` 得 `offset(b) - offset(a) <= tB - tA`；
   假定相邻跳的传播时延相对时钟偏差可忽略，则 `tB - tA` 是偏移差的一个样本。
   每对来源取**中位数**（抗异常值），再从基准源（默认条目最多者，可显式指定）
   在来源图上 BFS 传播，得到各源 offset。
3. **重建（`TimelineReconstructor`）**：`corrected = raw - offset`，
   然后做**受因果约束的拓扑排序**——就绪集合中永远选校正时间最小的条目，
   因此因果边不可能被颠倒，即使估计误差导致校正时间戳本身反转。
4. **不确定性标注**：输出序列中相邻条目若互不可达（无因果关系）且校正时间差
   在容差 `epsilon`（默认 0ms）内，则合并为一个 `TimelineGroup`，
   `isConcurrent()` 为 true，绝不随意排列。
5. **统计（`TimelineStats`）**：`totalEntries`、`sourceCount`、
   `estimatedOffsets`、`correctedOrderCount`（最终顺序与朴素原始时间戳排序不一致的
   跨来源条目对数）、`uncertaintyGroupCount`（并发组数）。

### 局限与最小因果标记量建议

本方案依赖请求标识提供的跨来源因果边，存在以下固有局限：

- **标记不足即无法校正**：某来源若没有任何因果路径连向基准源，其偏移在数学上
  不可辨识。组件会把它列入 `SkewEstimation.unestimableSources()`，保留 offset=0，
  不会给出看似精确实则错误的结果（见
  `SkewEstimatorTest#sourcesWithoutCausalMarkersCannotBeEstimated`）。
- **单向边只能给出单侧约束**：样本 `tB - tA` 包含 a→b 的传播时延，估计偏移偏差
  上界约等于该时延；只有请求/响应成对出现时才能夹住偏移的上下界。
- **只建模常量偏移**：不识别时钟漂移（drift）与 NTP 跳变；标记集中在很短时间窗内
  时尤其敏感。
- 中位数能抗少量异常样本，但系统性的传播时延偏差（如所有跨服务调用都有固定排队）
  会进入估计结果。

最小因果标记量建议：

- **下限（可校正）**：每个非基准来源至少有 **1 条**跨来源因果边，直接或经其他
  来源间接连到基准源；未连通的来源数量 = 无法校正的来源数量。
- **推荐（可信赖）**：每对有调用关系的来源至少 **5 条**因果样本，使中位数有意义、
  可抵御个别异常；并让标记在观测时间窗内**均匀分布**（例如每分钟若干条），
  以发现漂移。
- **工程实践**：为每个入口请求注入唯一 requestId（或 trace id），在各服务日志中
  透传并记录调用链层级（span/hop）；入口层服务天然适合作为基准源。

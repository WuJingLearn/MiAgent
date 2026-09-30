# SLS 数据查询能力

> 本文件维护所有通过 SLS（日志服务）获取数据的查询能力。当前包含：调用成功率、Prompt Cache 命中率、限流明细、TTFT耗时查询。

## 数据源配置

支持国内（`cn`）和海外（`intl`）双数据源。用户未特殊说明时默认使用 `cn`，指定海外时使用 `intl`。

| SITE | endpoint | region | project | logstore |
|------|----------|--------|---------|----------|
| `cn` | cn-wulanchabu-share.log.aliyuncs.com | cn-wulanchabu | dashscope-merged | dashscope-gateway-task-log |
| `intl` | ap-southeast-1-share.log.aliyuncs.com | ap-southeast-1 | ds-new-gw-sg-log | dashscope-gateway-task-log |

工具：`spectrum-mcp-server` / `sls_query`

调用时固定参数（根据 SITE 选择对应值）：

| 参数 | `cn` | `intl` |
|---|---|---|
| `workspace_id` | `33e6d810` | `33e6d810` |
| `project` | `dashscope-merged` | `ds-new-gw-sg-log` |
| `region` | `cn-wulanchabu` | `ap-southeast-1` |
| `logstore` | `dashscope-gateway-task-log` | `dashscope-gateway-task-log` |

> `endpoint` 列仅作参考，`sls_query` 工具通过 `region` 参数自动确定连接地址，无需单独传入。

**时间窗口**：`window` 仅支持 `5m/30m/1h/6h/24h`。如需其他区间（如最近 10 分钟）或指定起止时间，改用 `from_epoch` / `to_epoch`（epoch 秒），与 `window` 互斥。示例：最近 10 分钟 → `from_epoch = 当前时间 - 600`、`to_epoch = 当前时间`。

---

## 1. 调用成功率

基于 SLS 网关日志统计调用成功率（`status_code` 为 200 或 4xx 视为成功）。

**默认时间窗口**：`5m`（近实时）

**SQL 模板**（按需拼接 `uid` 和 `model` 过滤条件，未指定的维度省略对应条件）：

UID + 模型维度：

```sql
* and uid: "<uid>" and model: "<model>" | SELECT count(*) AS total, count_if(regexp_like(cast(status_code AS varchar), '^(4\d{2}|200)$')) AS success, round(count_if(regexp_like(cast(status_code AS varchar), '^(4\d{2}|200)$')) * 100.0 / count(*), 2) AS success_rate
```

仅模型维度（全局，省略 `uid` 条件）：

```sql
* and model: "<model>" | SELECT count(*) AS total, count_if(regexp_like(cast(status_code AS varchar), '^(4\d{2}|200)$')) AS success, round(count_if(regexp_like(cast(status_code AS varchar), '^(4\d{2}|200)$')) * 100.0 / count(*), 2) AS success_rate
```

**返回字段**：`total`（总调用）、`success`（成功次数，含 200 与 4xx）、`success_rate`（百分比）。

---

## 2. Prompt Cache 命中率

基于 SLS 网关日志统计 Prompt Cache 命中率（仅统计 `status_code: 200` 的请求）。

**默认时间窗口**：`30m`，按分钟分组

**时间窗口与粒度**：通过 `date_trunc('<granularity>', __time__)` 控制分组粒度，按 `window` 自动选择：

| `window` | 推荐 `granularity` | 数据点数 | 说明 |
|---|---|---|---|
| `5m` | `minute` | 5 | 近实时监控 |
| `30m` | `minute` | 30 | 短时趋势 |
| `1h` | `minute` | 60 | 小时级明细 |
| `6h` | `hour` | 6 | 半天趋势 |
| `24h` | `hour` | 24 | 全天趋势 |
| 多天（epoch） | `day` | 按天数 | 日级趋势 |

> 如需非标准窗口（如最近 10 分钟），使用 `from_epoch` / `to_epoch`（epoch 秒），配合 `minute` 粒度。示例：最近 10 分钟 → `from_epoch = 当前时间 - 600`、`to_epoch = 当前时间`，返回 10 个数据点。

**SQL 模板**（按需拼接 `uid` 和 `model` 过滤条件，未指定的维度省略对应条件；`<granularity>` 替换为 `minute` / `hour` / `day`）：

UID + 模型维度：

```sql
* and uid: "<uid>" and model: "<model>" and status_code: 200
| SELECT
    date_trunc('<granularity>', __time__) AS dt,
    SUM(COALESCE("usage.prompt_tokens_details.cached_tokens", 0)) AS total_cached,
    SUM(COALESCE("usage.input_tokens", 0)) AS total_input,
    ROUND(
      SUM(COALESCE("usage.prompt_tokens_details.cached_tokens", 0)) * 100.0
      / NULLIF(SUM(COALESCE("usage.input_tokens", 0)), 0), 2
    ) AS cache_hit_rate
  GROUP BY dt
  ORDER BY dt
```

仅模型维度（全局，省略 `uid` 条件）：

```sql
* and model: "<model>" and status_code: 200
| SELECT
    date_trunc('<granularity>', __time__) AS dt,
    SUM(COALESCE("usage.prompt_tokens_details.cached_tokens", 0)) AS total_cached,
    SUM(COALESCE("usage.input_tokens", 0)) AS total_input,
    ROUND(
      SUM(COALESCE("usage.prompt_tokens_details.cached_tokens", 0)) * 100.0
      / NULLIF(SUM(COALESCE("usage.input_tokens", 0)), 0), 2
    ) AS cache_hit_rate
  GROUP BY dt
  ORDER BY dt
```

**返回字段**：`dt`（时间桶）、`total_cached`（缓存命中 Token）、`total_input`（输入 Token）、`cache_hit_rate`（命中率百分比）。

---

## 3. 限流明细

基于 SLS 网关日志统计限流相关错误明细（`error_code` 匹配 `Throttling*` 或 `limit_requests`），按 `error_code` 和 `model` 分组统计次数。

**默认时间窗口**：`30m`

**SQL 模板**（按需拼接 `uid` 和 `model` 过滤条件，未指定的维度省略对应条件）：

UID + 模型维度：

```sql
* and uid: "<uid>" and model: "<model>" and (error_code: Throttling* or error_code: limit_requests)
| SELECT error_code, model, count(*) AS cnt
  GROUP BY error_code, model
  ORDER BY cnt DESC
```

仅 UID 维度（省略 `model` 条件）：

```sql
* and uid: "<uid>" and (error_code: Throttling* or error_code: limit_requests)
| SELECT error_code, model, count(*) AS cnt
  GROUP BY error_code, model
  ORDER BY cnt DESC
```

仅模型维度（省略 `uid` 条件）：

```sql
* and model: "<model>" and (error_code: Throttling* or error_code: limit_requests)
| SELECT error_code, model, count(*) AS cnt
  GROUP BY error_code, model
  ORDER BY cnt DESC
```

**返回字段**：`error_code`（限流错误码）、`model`（模型名）、`cnt`（限流次数）。

---

## 4. TTFT耗时查询

基于 SLS 网关日志统计首 Token 延迟（TTFT）及总请求延迟分位数，仅统计有效推理请求（`usage.total_tokens > 0`）。支持整体概览与按模型拆分两种视角。

**默认时间窗口**：`1h`

### 模板 A — 整体 TTFT 与延迟分位数

**SQL 模板**（按需拼接 `uid` 和 `model` 过滤条件，未指定的维度省略对应条件）：

UID + 模型维度：

```sql
* and uid: "<uid>" and model: "<model>" and usage.total_tokens > 0
| SELECT
    avg(first_output_duration) AS avg_ttft,
    approx_percentile(duration, 0.5) AS p50_ms,
    approx_percentile(duration, 0.9) AS p90_ms,
    approx_percentile(duration, 0.99) AS p99_ms
```

仅 UID 维度（省略 `model` 条件）：

```sql
* and uid: "<uid>" and usage.total_tokens > 0
| SELECT
    avg(first_output_duration) AS avg_ttft,
    approx_percentile(duration, 0.5) AS p50_ms,
    approx_percentile(duration, 0.9) AS p90_ms,
    approx_percentile(duration, 0.99) AS p99_ms
```

仅模型维度（省略 `uid` 条件）：

```sql
* and model: "<model>" and usage.total_tokens > 0
| SELECT
    avg(first_output_duration) AS avg_ttft,
    approx_percentile(duration, 0.5) AS p50_ms,
    approx_percentile(duration, 0.9) AS p90_ms,
    approx_percentile(duration, 0.99) AS p99_ms
```

**返回字段**：`avg_ttft`（平均首 Token 延迟）、`p50_ms`（中位延迟）、`p90_ms`（90 分位延迟）、`p99_ms`（99 分位延迟）。

### 模板 B — 按模型 TTFT 与延迟（Top 10 调用量）

按模型分组统计调用量及延迟，按调用量降序取 Top 10，用于定位高频慢模型。

仅 UID 维度（按模型分组）：

```sql
* and uid: "<uid>" and usage.total_tokens > 0
| SELECT
    model,
    count(*) AS cnt,
    approx_percentile(duration, 0.5) AS p50_ms,
    approx_percentile(duration, 0.99) AS p99_ms,
    avg(first_output_duration) AS avg_ttft
  GROUP BY model
  ORDER BY cnt DESC
  LIMIT 10
```

全局（按模型分组）：

```sql
* and usage.total_tokens > 0
| SELECT
    model,
    count(*) AS cnt,
    approx_percentile(duration, 0.5) AS p50_ms,
    approx_percentile(duration, 0.99) AS p99_ms,
    avg(first_output_duration) AS avg_ttft
  GROUP BY model
  ORDER BY cnt DESC
  LIMIT 10
```

**返回字段**：`model`（模型名）、`cnt`（调用量）、`p50_ms`（中位延迟）、`p99_ms`（99 分位延迟）、`avg_ttft`（平均首 Token 延迟）。

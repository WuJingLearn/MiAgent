# MCP 协议演进与传输层对比

本文系统梳理 Model Context Protocol（MCP）从 `2024-11-05` 到 `2026-07-28` 的传输层和会话模型演进，重点说明远程 MCP Server 的通信流程、设计动因及多副本部署影响。

> `2026-07-28` 将 MCP 的协议核心切换为无状态模型。本文以该版本作为“最新版本”进行对比；实现兼容时仍应以目标 Client/Server 实际声明的协议版本为准。

## 1. 演进总览

| 协议版本 | 远程传输模型 | 会话模型 | Server 到 Client 的交互 | 多副本主要影响 |
| --- | --- | --- | --- | --- |
| `2024-11-05` | HTTP with SSE | 依赖预建 SSE 连接的隐式连接态 | 通过长期 SSE 通道发送 JSON-RPC message | 长连接必须被原副本维持；通常需要粘滞路由 |
| `2025-03-26` | Streamable HTTP | `initialize` 协商后的可选显式 session | 可在某个请求的 SSE 响应流或独立 GET SSE 流上发送 request/notification | 可选 `MCP-Session-Id`；有状态实现需亲和或共享 session 存储 |
| `2025-11-25` | Streamable HTTP | 与 `2025-03-26` 相同的握手式 session 模型 | 同上；并支持 SSE 重连、事件重放和 session 管理 | 协议级 session 仍是扩缩容与故障迁移的负担 |
| `2026-07-28` | Streamable HTTP | 无状态；每个请求自包含元数据 | `InputRequiredResult` + Client 重试替代 Server 主动 JSON-RPC request | 新请求可任意路由；无协议级 session 亲和需求 |

所有版本的 MCP 消息本体均以 JSON-RPC 2.0 表达。不同之处主要在于消息如何封装、能力如何协商，以及一次调用是否依赖先前建立的连接或 session。

## 2. `2024-11-05`：HTTP with SSE

### 2.1 工作模式：双端点与长期 SSE 连接

该版本为远程 MCP 定义了两个端点：

1. **SSE endpoint**：Client 通过 HTTP GET 建立长期 Server-Sent Events 连接，用于接收 Server 的所有消息；
2. **POST endpoint**：Client 用普通 HTTP POST 向 Server 发送 JSON-RPC 消息。

Client 不能预先假定 POST endpoint 的地址。Client 建立 SSE 连接后，Server 必须首先发送 `endpoint` SSE event，事件数据中携带 Client 后续提交消息所需的 URI。之后，Server 的 JSON-RPC 消息均编码在 SSE `message` event 的 `data` 中。

```text
Client                                      Server
  |                                            |
  |-- GET /sse ------------------------------->|  建立长期 SSE 连接
  |<-- event: endpoint; data: /messages?... ---|  下发 Client POST 地址
  |                                            |
  |-- POST /messages?... (JSON-RPC request) ->|  Client 发起调用
  |<-- event: message; data: JSON-RPC ... -----|  Server 的 response/request/notification
  |<-- event: message; data: JSON-RPC ... -----|
  |                                            |
  |-- 关闭 SSE 连接 -------------------------->|  结束下行通道
```

### 2.2 协议特征

- Client 到 Server 为 HTTP POST；Server 到 Client 固定为长期 SSE 通道。
- Server 可以在 SSE 通道上发送 JSON-RPC response、notification，以及主动的 JSON-RPC request。
- 请求与响应的关联由 JSON-RPC `id` 完成，但下行消息发送到哪条 Client SSE 连接依赖 Server 持有的连接状态。
- 该规范未定义后来 Streamable HTTP 中的标准 `MCP-Session-Id` 管理模型。

### 2.3 存在的问题

1. **长连接成本与可用性要求高**：每个 Client 需要维持 SSE 长连接；反向代理空闲超时、网络波动和副本重启都可能中断下行链路。
2. **双端点增加实现和运维复杂度**：网关、鉴权、路由、监控均要同时处理 SSE endpoint 与动态 POST endpoint。
3. **多副本需要连接归属**：SSE 通道存在于建立它的实例；后续 POST 若被负载均衡到其他副本，该副本无法直接向原 SSE 通道回写。
4. **断线恢复能力弱**：早期模型没有统一的、面向请求恢复的标准会话和事件游标机制。
5. **简单调用也依赖 SSE 基础设施**：即便 `tools/list` 或简单 `tools/call` 只需要一次 JSON 返回，服务端仍需要具备 SSE 下行通道能力。

### 2.4 工程影响

远程多副本部署通常需要按 Client/连接维度进行粘滞路由，或通过共享消息总线和连接注册表将响应转发回拥有 SSE 连接的副本。SSE 连接层需设置 keep-alive、代理禁缓冲、连接数上限和断线处理策略。该模式不适合纯短请求或按请求弹性扩缩容的 Serverless 场景。

## 3. `2025-03-26` 与 `2025-11-25`：老版本 Streamable HTTP

`2025-03-26` 引入 Streamable HTTP，用单一 MCP endpoint 取代旧 HTTP with SSE 的固定双端点模型；`2025-11-25` 延续该传输模型和初始化会话模型，并补充/固化了 session、SSE 重连和协议版本 Header 等约束。

### 3.1 工作模式：单端点、JSON 内联返回、按需 SSE

Server 提供单一 MCP endpoint，例如 `POST /mcp`；在 `2025-11-25` 中该 endpoint 同时支持 GET，以便 Client 需要独立 SSE 流时使用。

每条 Client JSON-RPC 消息均通过新的 HTTP POST 发往 `/mcp`。如果输入是 JSON-RPC request，Server 可根据调用性质选择：

- 以 `application/json` 在该 HTTP 响应中一次性返回 JSON-RPC response；
- 以 `text/event-stream` 建立该请求关联的 SSE 响应流，先发送进度、notification 或 Server 发起的 JSON-RPC request，最后发送与原请求 ID 匹配的 response。

```text
Client                                      Server
  |                                            |
  |-- POST /mcp (JSON-RPC request) ----------->|
  |                                            |-- 处理
  |<-- 200 application/json + response --------|  简单调用
  |                                            |
  |-- POST /mcp (JSON-RPC request) ----------->|
  |<-- 200 text/event-stream ------------------|  流式调用
  |<-- SSE: progress / notification -----------|
  |<-- SSE: JSON-RPC response -----------------|
```

Client 也可以发起 `GET /mcp` 请求来创建独立 SSE 流，供不依附于当前 POST 请求的 Server 消息使用。Server 可以为 SSE event 附加 `id`；Client 断线后携带 `Last-Event-ID` 再次 GET，Server 可以回放同一流中漏掉的消息。

### 3.2 保留的握手式有状态模型

虽然传输层已“HTTP 化”，`2025-03-26` 和 `2025-11-25` 的 MCP 核心仍基于连接/会话生命周期：

```text
Client                                      Server
  |                                            |
  |-- POST /mcp: initialize ------------------>|  请求版本、Client capabilities、clientInfo
  |<-- InitializeResult -----------------------|  返回版本、Server capabilities、serverInfo
  |<-- Header: MCP-Session-Id: <id> -----------|  Server 可选建立状态会话
  |                                            |
  |-- POST /mcp: notifications/initialized --->|  回传 MCP-Session-Id
  |<-- 202 Accepted ---------------------------|
  |                                            |
  |-- POST /mcp: tools/list ------------------>|  带 MCP-Session-Id、MCP-Protocol-Version
  |<-- JSON 或 SSE response -------------------|
  |-- POST /mcp: tools/call ------------------>|  同一 session
  |<-- JSON 或 SSE response -------------------|
```

具体约束如下：

- `initialize` 必须是 Client 与 Server 的首次协议交互；Client 在收到 `InitializeResult` 后发送 `notifications/initialized`。
- `initialize` 中交换 `protocolVersion`、`capabilities`、`clientInfo` 和 Server capabilities。
- 对 HTTP transport，后续请求必须携带协商出的 `MCP-Protocol-Version` header。
- Server 若在 `InitializeResult` 的 HTTP 响应中返回 `MCP-Session-Id`，Client 后续所有 HTTP 请求都必须携带该 Header。
- Server 可以终止 session；对已终止 session ID 返回 HTTP `404`，Client 收到后必须重新执行不带 session ID 的 `initialize`。
- Client 可发送带 `MCP-Session-Id` 的 `DELETE /mcp` 请求主动结束 session；Server 可以拒绝该删除请求并返回 `405`。

### 3.3 相比旧 SSE 的改进

- **不再强制预建 SSE 通道**：简单调用可直接返回 JSON，服务端可只实现普通 HTTP 请求/响应。
- **统一入口**：不再要求 `/sse` 与动态 POST endpoint 的组合，网关和鉴权配置更简单。
- **按需流式化**：只有需要进度、分块结果或 Server 到 Client 消息时才返回 SSE。
- **恢复机制更明确**：可选的 SSE event ID 与 `Last-Event-ID` 使 Server 能够回放断线期间同一流的消息。
- **兼容路径清晰**：Client 可以先 POST `initialize` 探测 Streamable HTTP；失败时再退回旧 HTTP with SSE 的 GET + `endpoint` event 模型。

### 3.4 仍然存在的协议级问题

1. **会话状态仍是横向扩展障碍**：协商版本、capabilities 和 session 绑定在 `initialize` 之后。若 session 仅在内存中，后续请求必须路由到创建它的副本。
2. **需要粘滞路由或共享 Session 存储**：`MCP-Session-Id` 只是引用，不会自动让其他副本获得状态；生产环境需二选一或两者结合。
3. **Server 主动请求关联复杂**：Server 可在 SSE 中发送 sampling、elicitation、roots 等 JSON-RPC request，Client 再 POST JSON-RPC response。断线、超时、重试、多个并发 SSE 流时，需要保存待响应 request 与流的关联。
4. **协议语义不完全透明于网关**：方法名和工具名主要位于 JSON body；如需基于方法/工具进行路由、限流或策略控制，网关常需解析请求体。
5. **流和 session 生命周期耦合**：session 终止可能中断 SSE；副本发布、扩容或失效时，连接迁移和未完成调用的恢复需要额外设计。

### 3.5 工程影响

- **单副本或有状态工作负载**：内存 session 可简化实现，但实例重启即丢失会话和未完成流。
- **多副本**：需要基于 `MCP-Session-Id` 的粘滞路由，或将 session/capabilities、待处理的 Server request、SSE 重放事件存入 Redis、数据库或其他共享存储。
- **SSE 连接**：已建立的 SSE 流仍必须由处理该流的副本持续持有；代理需要关闭响应缓冲、配置超时和 keep-alive。
- **弹性与发布**：优雅下线需停止新 session、等待或迁移现有流，并确保重连副本能读取 session/事件状态。

## 4. `2026-07-28`：最新 Streamable HTTP 的无状态模型

### 4.1 工作模式：请求自包含

`2026-07-28` 保留 Streamable HTTP 的单 MCP endpoint，但移除了协议级 `initialize` / `initialized` 握手和 `MCP-Session-Id`。

每一个请求都在 JSON-RPC `params._meta` 中携带协议版本、Client 信息和 Client capabilities；HTTP transport 同时将部分元数据镜像到 Header，便于中间件处理。Server 对每个请求独立验证和处理，不能依赖同一连接、进程或前序请求的隐式状态。

```text
Client                                      任意 Server 副本
  |                                            |
  |-- POST /mcp ------------------------------>|
  |   MCP-Protocol-Version: 2026-07-28         |
  |   Mcp-Method: tools/call                   |
  |   Mcp-Name: search                         |
  |   body: JSON-RPC + _meta                   |
  |                                            |-- 独立验证版本与 capabilities
  |<-- JSON response 或请求范围 SSE ------------|
```

Client 可以可选调用 `server/discover` 获取 Server 支持的版本和 capabilities；也可以直接发出具体 RPC。如果版本不受支持，Server 返回 `UnsupportedProtocolVersionError` 并列出其支持版本，Client 再选择兼容版本重试。

### 4.2 `InputRequiredResult` 替代 Server 主动 request

旧版本中，Server 可在 SSE 上直接发送 JSON-RPC request 要求 Client 执行 sampling、elicitation 或 roots/list。最新协议将该模式替换为多轮请求（Multi Round-Trip Requests，MRTR）：

```text
Client -> Server: POST tools/call（原始参数与 _meta）
Server -> Client: resultType: "input_required"
                  inputRequests: { ... }
                  requestState: "..."
Client: 收集用户输入或模型结果
Client -> Server: POST tools/call（原始参数 + inputResponses + requestState）
Server -> Client: resultType: "complete" 的最终结果
```

`requestState` 与 `inputResponses` 作为显式数据随重试请求一起传递，因此任何副本都可以继续处理；不再需要保存“哪个 SSE 连接正在等待 Client 对某个 Server request 作答”的协议级状态。

### 4.3 SSE 的新定位

SSE 仍被保留，但用途收敛为：

- 对单个 Client request 返回进度与最终响应的请求范围流；
- `subscriptions/listen` 打开的长期通知流，例如工具列表或资源变更。

新的 Streamable HTTP 中，每个 Client JSON-RPC request 或 notification 是一条独立 HTTP POST。若 Server 选择 SSE，流只能携带与原始请求相关的 notification 和最终 response，Server 不可在流上发起独立 JSON-RPC request。`Last-Event-ID` 的可恢复 SSE 机制不再由该版本支持；订阅流断开后 Client 应重新建立订阅。

### 4.4 面向网关和可观测性的补强

- `Mcp-Method` 和 `Mcp-Name` Header 使负载均衡、限流、审计和路由可不解析 JSON body 即识别操作与工具名；Header 与 body 不一致时 Server 必须拒绝。
- tools/list、资源读取结果可携带 `ttlMs` 和 `cacheScope`，Client 可明确缓存有效期与可共享范围。
- W3C Trace Context 可在 `_meta` 中传播，使 Host、Client、MCP Server 与下游依赖的分布式追踪关联起来。
- 长任务转向显式 task handle；业务状态应由工具返回显式标识符，并在后续调用参数中回传。

### 4.5 工程影响

| 关注点 | `2025-11-25` 及更早的 Streamable HTTP | `2026-07-28` |
| --- | --- | --- |
| 负载均衡 | session 在内存时需要 session affinity | 新请求可轮询到任意副本 |
| 共享 session 存储 | 有状态实现通常需要 | 协议层不需要 |
| SSE 连接 | 流与 session、Server 主动 request 可能关联 | 当前流仍由单副本维持，但后续请求不依赖该副本 |
| 故障恢复 | 恢复 session、SSE 游标、待响应 Server request | Client 使用显式重试、requestState、业务 handle 或重新订阅 |
| 业务状态 | 容易隐含在协议 session 中 | 应外置为显式参数/handle，并存储在业务共享存储中 |
| 多副本扩展 | 要处理 session 一致性和亲和 | 协议调用天然适配无状态横向扩展 |

需要强调的是：协议无状态不等于业务无状态。工具若创建长期对象、后台任务、购物车、浏览器上下文或授权上下文，仍应生成显式 `taskId`、`browserId` 等 handle，并将相应数据放在可跨副本访问的业务存储中。

### 4.6 为什么旧版本需要状态，最新版本又取消状态

旧版本的有状态设计并非为了简单的 `tools/call`。对于“调用工具并一次性返回结构化结果”的最小链路，协议 session 本来不是必需条件；其状态模型主要服务于当时更接近持续双向连接的 MCP 使用方式：

- **一次性协商并复用**：`initialize` 交换协议版本、Client/Server capabilities 和实现信息，后续请求默认继承这些协商结果，避免每次重复携带。
- **Server 主动向 Client 发起请求**：Server 在工具执行中可能通过 SSE 发起 sampling、elicitation 或 roots/list，并等待 Client 以 JSON-RPC response 回答；Server 因而需要保存待响应请求、所属 Client/session 与下行 SSE 流的关联。
- **订阅与变化通知**：资源订阅、工具/Prompt/资源列表变化通知需要保存订阅关系，并将消息写入对应 Client 的长期 SSE 连接。
- **流恢复与事件重放**：旧 Streamable HTTP 可用 `Last-Event-ID` 回放同一 SSE 流的漏失事件，Server 需要维护 session、流和事件游标之间的映射。
- **本地子进程模型的自然延伸**：早期 MCP 借鉴 LSP 的连接生命周期；对 stdio 场景，一个 Client 启动并长期持有一个 Server 子进程时，初始化一次、持续双向通信的模型实现直接且自然。

这个设计的代价是，远程 HTTP Server 即使只提供无状态工具，也会因协议 session 和连接关联而增加粘滞路由、共享 session 存储、SSE 故障恢复及发布迁移的复杂度。

`2026-07-28` 取消协议级状态，是将上述“隐式连接状态”改造成“显式请求数据”：

- 每次请求携带协议版本、Client 信息和 capabilities，取代 `initialize` 后的隐式协商结果；
- `server/discover` 提供按需能力发现，取代必须先握手才能调用的前置条件；
- `InputRequiredResult`、`inputResponses` 和 `requestState` 组成多轮请求，取代 Server 在 SSE 流上主动发起 JSON-RPC request；任何副本都能处理带齐这些数据的重试请求；
- `subscriptions/listen` 仍保留长期 SSE 订阅流，但订阅是独立请求，普通工具调用不再依赖该流或 session；
- 长期业务对象改由工具返回显式 handle，在后续参数中回传，而非隐藏在 `MCP-Session-Id` 中。

因此，新版并非删除复杂交互能力，而是把状态从协议连接中移出：**一次性工具调用可成为纯无状态 RPC；确实需要跨调用状态的业务，使用显式 handle 和共享业务存储；确实需要实时通知的场景，使用独立的订阅 SSE 流。**

## 5. 版本选择与迁移建议

### 5.1 新建远程 MCP Server

优先实现 `2026-07-28`：

- 每个请求独立验证 `_meta` 与 HTTP Header；
- 不依赖连接、进程或 `MCP-Session-Id` 保存协议状态；
- 将跨调用状态设计为显式 handle；
- 对耗时操作使用请求范围 SSE、订阅流或 Tasks extension；
- 让负载均衡使用轮询或基于 `Mcp-Method` / `Mcp-Name` 的无状态路由。

### 5.2 兼容旧 Client

如果生态中仍存在 `2025-11-25` 或更早 Client，可实现 dual-era Server：

- 收到带现代 `_meta` 的请求时，以 `2026-07-28` 的无状态语义处理；
- 收到 `initialize` 时，以旧版 session 语义处理；
- 对旧版 session 使用粘滞路由或 Redis 等共享 session 存储；
- 在发布前验证 Client 是否支持新的 `server/discover`、MRTR 和 Header 约束。

### 5.3 迁移风险清单

- 旧版 Client 不能自动“前向升级”到仅支持 `2026-07-28` 的 Server；需要 Server 保留 legacy 行为或 Client 升级。
- 依赖 Server 在 SSE 中主动发送 JSON-RPC request 的实现，必须改为处理 `InputRequiredResult` 和重试调用。
- 依赖 `MCP-Session-Id` 传递业务状态的实现，必须将状态迁移为工具参数中的显式 handle。
- 旧 SSE 事件重放逻辑不能直接迁移到最新协议；订阅断开后的恢复策略应改为重新订阅，并由业务 API 补偿必要的数据读取。

## 6. 参考资料

- [MCP 2024-11-05：HTTP with SSE](https://modelcontextprotocol.io/specification/2024-11-05/basic/transports)
- [MCP 2025-03-26：Streamable HTTP](https://modelcontextprotocol.io/specification/2025-03-26/basic/transports)
- [MCP 2025-11-25：Lifecycle](https://modelcontextprotocol.io/specification/2025-11-25/basic/lifecycle)
- [MCP 2025-11-25：Transports 与 Session Management](https://modelcontextprotocol.io/specification/2025-11-25/basic/transports)
- [MCP 2026-07-28：Overview](https://modelcontextprotocol.io/specification/2026-07-28)
- [MCP 2026-07-28：Transports](https://modelcontextprotocol.io/specification/2026-07-28/basic/transports)
- [MCP 2026-07-28：Versioning and Compatibility](https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning)
- [MCP 2026-07-28 Release Candidate：无状态协议演进](https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/)
- [RFC #206：以 Streamable HTTP 替代 HTTP+SSE](https://github.com/modelcontextprotocol/modelcontextprotocol/pull/206)

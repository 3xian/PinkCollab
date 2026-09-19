# PinkCollab 协议 v1

[English](protocol.md) · [简体中文](protocol_cn.md)

Android 只依赖本协议。OMP 适配依据 [官方 RPC 定义](https://github.com/can1357/oh-my-pi/blob/main/docs/rpc.md) 与 [rpc-types.ts](https://github.com/can1357/oh-my-pi/blob/main/packages/coding-agent/src/modes/rpc/rpc-types.ts)。当前使用 v1 NDJSON（每帧最多 1 MiB），不协商 OMP v2 chunk 传输。

除配对外，每个 REST 请求与 WebSocket 握手均须携带 `Authorization: Bearer <credential>`。凭据不支持 URL query 传入，不启用浏览器跨域访问。所有响应 `Cache-Control: no-store`。

## REST

| 方法 | 地址 | 请求 / 响应 |
| --- | --- | --- |
| POST | `/api/v1/pair` | `{token,name}` → `{clientId,credential,host,protocolVersion}`，201 |
| GET | `/api/v1/host` | 本机 Host |
| GET | `/api/v1/workspaces` | `[{name,path}]` |
| GET | `/api/v1/fs/list?path=...` | `{path,parent?,directories:[{name,path}],git?:{branch,status}}` |
| GET | `/api/v1/sessions` | Session 数组，按更新时间倒序 |
| GET | `/api/v1/sessions/:id` | `{session,timeline,model?}` |
| POST | `/api/v1/sessions` | `{hostId,cwd,prompt,title?}` → Session，201 |
| DELETE | `/api/v1/sessions/:id` | 只删除已无 runtime 的管理元数据，204；不删除 OMP 原始数据 |
| POST | `/api/v1/sessions/:id/prompt` | `{message}`，运行中自动指定 OMP streamingBehavior=steer |
| POST | `/api/v1/sessions/:id/interrupt` | 无请求字段，OMP abort，进入 idle |
| POST | `/api/v1/sessions/:id/stop` | 无请求字段，关闭 stdin，3 秒后未退出则终止 OMP |
| POST | `/api/v1/sessions/:id/respond` | 见下方输入回复 |
| POST | `/api/v1/sessions/:id/model/cycle` | 在 OMP 的模型范围中循环，并返回 `{model:{provider,id,name}}` |

除上表已注明响应内容的接口外，命令成功返回 `{ok:true}`。命令拒绝返回 409，创建拒绝返回 422，目录越界返回 403，鉴权失败返回 401。业务错误返回 `{error:string}`；JSON 解码错误由 HTTP 框架返回 400/415/422。请求体上限 512 KiB，Prompt 和输入值最多 256 KiB。

模型状态由 Gateway 持有，通过 `GET /api/v1/sessions/:id` 的 `model` 字段下发；仅当 runtime 仍连接且 OMP 报告了当前模型时存在。客户端必须把 `model` 视为可选字段（未返回即表示该 Gateway 不提供模型控制），并通过 `model.updated` 事件跟进非本机发起的切换。循环切换要求 Session 仍连接 runtime，使用 OMP 的 `cycle_model` 命令：已配置模型范围时在该范围中循环，未配置时在所有可用模型中循环。OMP 没有其他可用模型时返回 409。

## Session

```json
{
  "id": "sess_xxx",
  "hostId": "host_xxx",
  "cwd": "/home/user/projects/shop",
  "title": "Fix checkout bug",
  "status": "running",
  "activity": "Testing",
  "needsAttention": false,
  "runtimeAttached": true,
  "createdAt": "2026-09-14T08:00:00Z",
  "updatedAt": "2026-09-14T08:00:30Z"
}
```

状态：starting、running、needs_input、idle、completed、failed、stopped、offline。只有 `agent_end` 完成整个 Agent 执行，`turn_end` 不结束任务。`runtimeAttached` 表示当前仍能向 runtime 发送指令；completed 任务可能仍有 runtime，Gateway 重启后此值为 false。

有输入请求时包含 `attention: {id,type,text,options}`。`type` 为 select / confirm / input / editor，`needsAttention=true`。响应须带原请求 id，过期或不匹配的 id 被拒绝。

```json
{"id":"request-id","value":"selected option or input text"}
```

```json
{"id":"request-id","confirmed":true}
```

```json
{"id":"request-id","cancelled":true}
```

select 的 value 必须是 options 中的原字符串。输入完成后重新进入 running；存在未回答的输入请求时不能发送 Prompt。

## WebSocket

连接 `/api/v1/events` 后，Gateway 先注册订阅，再发送 snapshot，消除初始 REST 与增量流之间的空窗。

```json
{
  "sequence": 0,
  "type": "snapshot",
  "timestamp": "2026-09-14T08:00:00Z",
  "payload": {"host":{},"sessions":[],"protocolVersion":1}
}
```

后续事件统一 `{sequence,type,timestamp,payload}`，sequence 是当前 Gateway 进程内递增序号；snapshot 使用 0。

| type | payload |
| --- | --- |
| session.updated | 完整 Session |
| session.deleted | `{sessionId}` |
| timeline.updated | `{sessionId,item:{id,kind,text,detail,tool?,timestamp}}` |
| message.delta | `{sessionId,text}`，当前助手消息的文本增量 |
| model.updated | `{sessionId,model}`，OMP 没有当前模型时 `model` 为 null |
| attention.created | `{sessionId,attention}` |

客户端以 session/item id 合并事件，按 updatedAt 防止快照之后排队的旧状态回退。timeline 同 id 更新是 upsert；助手最终消息覆盖当前流式草稿。Timeline kind 为 user / assistant / tool / subagent / error / notice。工具项可能带有 `tool:{callId,name,arguments,result,isError,completed}`；开始和结果更新共享 `callId`，客户端无需解析展示文本即可投影视图。

Gateway 每 25 秒发送 Ping，70 秒未收到心跳回复则断开。慢客户端超过 256 条积压时断开，客户端应重新连接获取快照，并重新加载打开的任务详情。本版本不提供持久事件重放。

## 数据所有权

SQLite 只保存 Host ID、客户端 token 哈希、配对令牌哈希、Session 管理元数据和 OMP session 文件定位信息。供应商凭据由 OMP 保管。前台实时 Timeline 最近 500 项只放在内存；历史对话（包括已配对的工具调用与结果）按 OMP entry 的 parentId 重建当前分支。

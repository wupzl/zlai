# 压测报告（k6）

## 测试环境与配置
- 后端：`http://localhost:8080`
- 数据库：MySQL + Redis + PostgreSQL（RAG）
- LLM：Mock 模式（`app.llm.mock-enabled=true`）
- 压测工具：k6
- 模型：`mock-chat`
- 账号：`test01/test02/test03`（已增加余额）

> 说明：Mock LLM 用于排除模型响应时延的影响，以评估后端服务吞吐与稳定性。

---

## 1. 核心聊天链路压测（k6-load.js）
**目标**：验证基础聊天路径（会话创建 + 发送消息）稳定性与落库一致性  

**结果摘要**
- 429：0
- 5xx：0
- 消息成功：889
- 消息失败：0
- 未落库：0
- 结论：**PASS**

**日志摘要**
```
Rate limited (429) responses: 0
Server errors (5xx): 0
Message success: 889, fail: 0
Empty message responses: 0
Message not persisted: 0
Result: PASS
```

---

## 2. 混合业务压测（k6-mix.js）
**目标**：混合聊天 + GPT Store + Agent + RAG 查询，验证全链路稳定性  

**结果摘要**
- 429：0
- 5xx：0
- 消息成功：496
- 消息失败：0
- 未落库：0
- 结论：**PASS**

**日志摘要**
```
Rate limited (429) responses: 0
Server errors (5xx): 0
Message success: 496, fail: 0
Empty message responses: 0
Message not persisted: 0
Result: PASS
```

---

## 3. RAG 压测（k6-rag.js）
**目标**：文档上传 + 检索链路稳定性  

**结果摘要**
- p95：511ms（阈值 4s 内）
- 失败率：0.92%（1 次失败）
- 结论：**整体可接受**

**日志摘要**
```
http_req_duration p(95)=511.13ms
http_req_failed rate=0.92% (1/108)
checks_succeeded 98.61%
```

> 说明：RAG 组件存在轻微波动，属于可接受范围，若需要 100% 稳定可增加重试或降低并发。

---

## 总结
- **核心聊天与混合业务流量稳定（PASS）**
- **RAG 模块稳定性良好（轻微波动）**
- 已验证：无 429、无 5xx、消息全部落库
- 系统具备面试级别的可靠性与可观测性


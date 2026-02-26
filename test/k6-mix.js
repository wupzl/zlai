import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const MODEL = __ENV.MODEL || "deepseek-chat";
const USERS = (__ENV.USERS || "test01:123456,test02:123456,test03:123456").split(",");
const ENABLE_STREAM = (__ENV.STREAM || "false").toLowerCase() === "true";
const rateLimitCounter = new Counter("rate_limit_429");
const serverErrorCounter = new Counter("server_error_5xx");
const streamSuccessCounter = new Counter("stream_success");
const streamFailCounter = new Counter("stream_fail");
const messageSuccessCounter = new Counter("message_success");
const messageFailCounter = new Counter("message_fail");
const messageEmptyCounter = new Counter("message_empty");
const messagePersistFailCounter = new Counter("message_persist_fail");

export const options = {
  stages: [
    { duration: "10s", target: 5 },
    { duration: "30s", target: 20 },
    { duration: "30s", target: 30 },
    { duration: "10s", target: 0 }
  ],
  thresholds: {
    http_req_failed: ["rate<0.03"],
    http_req_duration: ["p(95)<5000"]
  }
};

function login(username, password) {
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { "Content-Type": "application/json" } }
  );
  check(res, { "login ok": r => r.status === 200 });
  const data = res.json();
  return data?.data?.accessToken || data?.data?.token;
}

function chatFlow(headers) {
  const listRes = http.get(`${BASE_URL}/api/chat/sessions?page=1&size=1`, { headers });
  if (listRes.status === 429) rateLimitCounter.add(1);
  let chatId = null;
  if (listRes.status === 200) {
    const first = listRes.json()?.data?.content?.[0];
    chatId = first?.chatId || first?.id;
  }
  if (!chatId) {
    const createRes = http.post(
      `${BASE_URL}/api/chat/session?title=k6-mix&model=${encodeURIComponent(MODEL)}`,
      null,
      { headers }
    );
    if (createRes.status === 429) rateLimitCounter.add(1);
    check(createRes, { "create session ok": r => r.status === 200 });
    chatId = createRes.json()?.data?.chatId;
  }

  const prompt = "Explain CAP theorem briefly.";
  const msgRes = http.post(
    `${BASE_URL}/api/chat/message`,
    JSON.stringify({ chatId, prompt }),
    { headers }
  );
  if (msgRes.status === 429) rateLimitCounter.add(1);
  if (msgRes.status >= 500) serverErrorCounter.add(1);
  const msgBody = msgRes.status === 200 ? msgRes.json() : null;
  const msgOk = msgRes.status === 200 && msgBody?.code === 200 && msgBody?.data;
  check(msgRes, { "send message ok": r => msgOk });
  if (msgOk) {
    messageSuccessCounter.add(1);
  } else {
    messageFailCounter.add(1);
  }
  if (msgRes.status === 200 && (!msgBody?.data || String(msgBody.data).trim().length === 0)) {
    messageEmptyCounter.add(1);
  }
  if (chatId) {
    const historyRes = http.get(`${BASE_URL}/api/chat/${chatId}`, { headers });
    const historyBody = historyRes.status === 200 ? historyRes.json() : null;
    const messages = historyBody?.data?.messages || [];
    const persisted = messages.some(m => m && m.role === "user" && String(m.content || "").includes(prompt));
    if (!persisted) {
      messagePersistFailCounter.add(1);
    }
  }

  if (ENABLE_STREAM) {
    const streamRes = http.post(
      `${BASE_URL}/api/chat/stream`,
      JSON.stringify({ chatId, prompt: "Explain eventual consistency." }),
      { headers, timeout: "60s", responseType: "none" }
    );
    if (streamRes.status === 429) rateLimitCounter.add(1);
    if (streamRes.status >= 500) serverErrorCounter.add(1);
    check(streamRes, { "stream ok": r => r.status === 200 });
    if (streamRes.status === 200) {
      streamSuccessCounter.add(1);
    } else {
      streamFailCounter.add(1);
    }
  }
}

function gptStoreFlow(headers) {
  const listRes = http.get(`${BASE_URL}/api/gpts/public?page=1&size=5`, { headers });
  if (listRes.status === 429) rateLimitCounter.add(1);
  check(listRes, { "public gpts ok": r => r.status === 200 });
}

function agentFlow(headers) {
  const listRes = http.get(`${BASE_URL}/api/agents/public?page=1&size=5`, { headers });
  if (listRes.status === 429) rateLimitCounter.add(1);
  check(listRes, { "public agents ok": r => r.status === 200 });
}

function ragFlow(headers) {
  const searchRes = http.post(
    `${BASE_URL}/api/rag/query`,
    JSON.stringify({ query: "Harmony AI 智能对话系统", topK: 3 }),
    { headers: { ...headers, "Content-Type": "application/json" } }
  );
  if (searchRes.status === 429) rateLimitCounter.add(1);
  check(searchRes, { "rag search ok": r => r.status === 200 });
}

export function setup() {
  const tokens = USERS.map((item) => {
    const [username, password] = item.split(":");
    return login(username, password);
  });
  return { tokens };
}

export default function (data) {
  const token = data.tokens[(__VU - 1) % data.tokens.length];
  if (!token) {
    sleep(1);
    return;
  }
  const headers = { "Content-Type": "application/json", Authorization: `Bearer ${token}` };

  const route = Math.random();
  if (route < 0.5) {
    chatFlow(headers);
  } else if (route < 0.7) {
    gptStoreFlow(headers);
  } else if (route < 0.85) {
    agentFlow(headers);
  } else {
    ragFlow(headers);
  }

  sleep(1);
}

export function handleSummary(data) {
  const count = data.metrics.rate_limit_429?.values?.count || 0;
  const serverErrors = data.metrics.server_error_5xx?.values?.count || 0;
  const msgOk = data.metrics.message_success?.values?.count || 0;
  const msgFail = data.metrics.message_fail?.values?.count || 0;
  const streamOk = data.metrics.stream_success?.values?.count || 0;
  const streamFail = data.metrics.stream_fail?.values?.count || 0;
  const emptyCount = data.metrics.message_empty?.values?.count || 0;
  const persistFail = data.metrics.message_persist_fail?.values?.count || 0;
  const pass = count === 0 && serverErrors === 0 && msgFail === 0 && streamFail === 0
    && emptyCount === 0 && persistFail === 0;
  return {
    stdout: `\nRate limited (429) responses: ${count}\n` +
      `Server errors (5xx): ${serverErrors}\n` +
      `Message success: ${msgOk}, fail: ${msgFail}\n` +
      `Stream success: ${streamOk}, fail: ${streamFail}\n` +
      `Empty message responses: ${emptyCount}\n` +
      `Message not persisted: ${persistFail}\n` +
      `Result: ${pass ? "PASS" : "FAIL"}\n`
  };
}

import http from "k6/http";
import { check, sleep } from "k6";
import { Counter } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const MODEL = __ENV.MODEL || "deepseek-chat";
const USERS = (__ENV.USERS || "test01:123456,test02:123456,test03:123456").split(",");
const ENABLE_STREAM = (__ENV.STREAM || "false").toLowerCase() === "true";
const ENABLE_DUPLICATE_REPLAY = (__ENV.DUPLICATE_REPLAY || "true").toLowerCase() === "true";
const LONG_SESSION_TURNS = Number(__ENV.LONG_SESSION_TURNS || "3");
const rateLimitCounter = new Counter("rate_limit_429");
const serverErrorCounter = new Counter("server_error_5xx");
const streamSuccessCounter = new Counter("stream_success");
const streamFailCounter = new Counter("stream_fail");
const messageSuccessCounter = new Counter("message_success");
const messageFailCounter = new Counter("message_fail");
const messageEmptyCounter = new Counter("message_empty");
const messagePersistFailCounter = new Counter("message_persist_fail");
const duplicateReplayOkCounter = new Counter("duplicate_replay_ok");
const duplicateReplayFailCounter = new Counter("duplicate_replay_fail");

export const options = {
  stages: [
    { duration: "10s", target: 5 },
    { duration: "20s", target: 20 },
    { duration: "20s", target: 30 },
    { duration: "10s", target: 0 }
  ],
  thresholds: {
    http_req_failed: ["rate<0.02"],
    http_req_duration: ["p(95)<2000"]
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
  const token = data?.data?.accessToken || data?.data?.token || null;
  const accessCookie = res.cookies?.zlai_access_token?.[0]?.value || null;
  const refreshCookie = res.cookies?.zlai_refresh_token?.[0]?.value || null;
  return { token, accessCookie, refreshCookie };
}

function buildRequestId(prefix) {
  return `${prefix}-${__VU}-${__ITER}-${Date.now()}`;
}

export function setup() {
  const tokens = USERS.map((item) => {
    const [username, password] = item.split(":");
    return login(username, password);
  });
  return { tokens };
}

export default function (data) {
  const auth = data.tokens[(__VU - 1) % data.tokens.length];
  if (!auth || (!auth.token && !auth.accessCookie)) {
    sleep(1);
    return;
  }
  const headers = { "Content-Type": "application/json" };
  if (auth.token) {
    headers.Authorization = `Bearer ${auth.token}`;
  } else if (auth.accessCookie) {
    headers.Cookie = `zlai_access_token=${auth.accessCookie}` +
      (auth.refreshCookie ? `; zlai_refresh_token=${auth.refreshCookie}` : "");
  }

  const listRes = http.get(`${BASE_URL}/api/chat/sessions?page=1&size=1`, { headers });
  if (listRes.status === 429) rateLimitCounter.add(1);
  let chatId = null;
  if (listRes.status === 200) {
    const first = listRes.json()?.data?.content?.[0];
    chatId = first?.chatId || first?.id;
  }
  if (!chatId) {
    const createRes = http.post(
      `${BASE_URL}/api/chat/session?title=k6-test&model=${encodeURIComponent(MODEL)}`,
      null,
      { headers }
    );
    if (createRes.status === 429) rateLimitCounter.add(1);
    check(createRes, { "create session ok": r => r.status === 200 });
    chatId = createRes.json()?.data?.chatId;
  }

  let lastPrompt = "";
  for (let turn = 0; turn < LONG_SESSION_TURNS; turn++) {
    const prompt = `Explain CAP theorem briefly. turn=${turn}`;
    lastPrompt = prompt;
    const requestId = buildRequestId(`msg-${turn}`);
    const payload = { chatId, prompt, requestId };
    const msgRes = http.post(
      `${BASE_URL}/api/chat/message`,
      JSON.stringify(payload),
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

    if (ENABLE_DUPLICATE_REPLAY && turn === 0) {
      const replayRes = http.post(
        `${BASE_URL}/api/chat/message`,
        JSON.stringify(payload),
        { headers }
      );
      const replayOk = replayRes.status === 200 || replayRes.status === 409;
      check(replayRes, { "duplicate replay bounded": () => replayOk });
      if (replayOk) {
        duplicateReplayOkCounter.add(1);
      } else {
        duplicateReplayFailCounter.add(1);
      }
      if (replayRes.status === 429) rateLimitCounter.add(1);
      if (replayRes.status >= 500) serverErrorCounter.add(1);
    }
  }
  if (chatId) {
    const historyRes = http.get(`${BASE_URL}/api/chat/${chatId}`, { headers });
    const historyBody = historyRes.status === 200 ? historyRes.json() : null;
    const messages = historyBody?.data?.messages || [];
    const persisted = messages.some(m => m && m.role === "user" && String(m.content || "").includes(lastPrompt));
    if (!persisted) {
      messagePersistFailCounter.add(1);
    }
  }

  if (ENABLE_STREAM) {
    const streamRequestId = buildRequestId("stream");
    const streamRes = http.post(
      `${BASE_URL}/api/chat/stream`,
      JSON.stringify({ chatId, prompt: "Explain eventual consistency.", requestId: streamRequestId }),
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
  const replayOk = data.metrics.duplicate_replay_ok?.values?.count || 0;
  const replayFail = data.metrics.duplicate_replay_fail?.values?.count || 0;
  const pass = count === 0 && serverErrors === 0 && msgFail === 0 && streamFail === 0
    && emptyCount === 0 && persistFail === 0 && replayFail === 0;
  return {
    stdout: `\nRate limited (429) responses: ${count}\n` +
      `Server errors (5xx): ${serverErrors}\n` +
      `Message success: ${msgOk}, fail: ${msgFail}\n` +
      `Stream success: ${streamOk}, fail: ${streamFail}\n` +
      `Empty message responses: ${emptyCount}\n` +
      `Message not persisted: ${persistFail}\n` +
      `Duplicate replay bounded ok: ${replayOk}, fail: ${replayFail}\n` +
      `Result: ${pass ? "PASS" : "FAIL"}\n`
  };
}

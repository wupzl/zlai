import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const USERS = (__ENV.USERS || "test01:123456,test02:123456").split(",");
const DOC_PATH = __ENV.DOC_PATH || "D:\\\\Code\\\\zlAI-v2\\\\readme.txt";
const FILE_BIN = open(DOC_PATH, "b");
const FILE_NAME = DOC_PATH.split("\\").pop() || "doc.txt";

export const options = {
  stages: [
    { duration: "10s", target: 2 },
    { duration: "20s", target: 5 },
    { duration: "10s", target: 0 }
  ],
  thresholds: {
    http_req_failed: ["rate<0.05"],
    http_req_duration: ["p(95)<4000"]
  }
};

function pickUser() {
  const idx = Math.floor(Math.random() * USERS.length);
  const [username, password] = USERS[idx].split(":");
  return { username, password };
}

function login() {
  const { username, password } = pickUser();
  const res = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username, password }),
    { headers: { "Content-Type": "application/json" } }
  );
  const data = res.json();
  return data?.data?.accessToken || data?.data?.token;
}

export default function () {
  const token = login();
  if (!token) {
    sleep(1);
    return;
  }
  const headers = { Authorization: `Bearer ${token}` };

  const res = http.post(
    `${BASE_URL}/api/rag/ingest/file-upload`,
    { file: http.file(FILE_BIN, FILE_NAME) },
    { headers }
  );
  check(res, { "rag ingest ok": r => r.status === 200 });

  const queryRes = http.post(
    `${BASE_URL}/api/rag/query`,
    JSON.stringify({ query: "介绍一下Harmony AI智能对话系统", topK: 5 }),
    { headers: { ...headers, "Content-Type": "application/json" } }
  );
  check(queryRes, { "rag search ok": r => r.status === 200 });

  sleep(2);
}

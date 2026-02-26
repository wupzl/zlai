import { API_BASE, getToken } from "../api";

export async function streamChat(path, payload, onEvent) {
  const controller = new AbortController();
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${getToken()}`
    },
    body: JSON.stringify(payload),
    signal: controller.signal
  });
  if (!res.ok || !res.body) {
    throw new Error("Stream request failed");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  function emitChunk(rawChunk) {
    const chunk = rawChunk.trim();
    if (!chunk) return;
    const lines = chunk.split("\n");
    let event = "message";
    let data = "";
    for (const line of lines) {
      if (line.startsWith("event:")) {
        event = line.replace("event:", "").trim();
      } else if (line.startsWith("data:")) {
        const part = line.slice(5).trimStart();
        data = data ? `${data}\n${part}` : part;
      }
    }
    onEvent?.(event, data);
  }

  async function read() {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, "\n");
      let idx;
      while ((idx = buffer.indexOf("\n\n")) !== -1) {
        const chunk = buffer.slice(0, idx);
        buffer = buffer.slice(idx + 2);
        emitChunk(chunk);
      }
    }
    const finalChunk = buffer.trim();
    if (finalChunk) {
      emitChunk(finalChunk);
    }
  }

  const promise = read();
  return {
    close() {
      controller.abort();
    },
    done: promise
  };
}

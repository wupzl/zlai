import { authFetch } from "../api";

export async function streamChat(path, payload, onEvent) {
  const controller = new AbortController();
  let closedByClient = false;
  const res = await authFetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload),
    signal: controller.signal
  });
  if (!res.ok || !res.body) {
    const message = await res.text().catch(() => "");
    throw new Error(message || "Stream request failed");
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  let buffer = "";

  function parseChunk(rawChunk) {
    const chunk = rawChunk.trim();
    if (!chunk) return null;
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
    return { event, data };
  }

  function emitChunk(rawChunk) {
    const parsed = parseChunk(rawChunk);
    if (!parsed) return null;
    onEvent?.(parsed.event, parsed.data);
    return parsed;
  }

  function resolveStreamError(parsed) {
    if (!parsed || parsed.event !== "error") {
      return null;
    }
    try {
      const payload = parsed.data ? JSON.parse(parsed.data) : {};
      return payload.error || "Stream failed";
    } catch {
      return "Stream failed";
    }
  }

  async function read() {
    let streamError = null;
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        buffer = buffer.replace(/\r\n/g, "\n");
        let idx;
        while ((idx = buffer.indexOf("\n\n")) !== -1) {
          const chunk = buffer.slice(0, idx);
          buffer = buffer.slice(idx + 2);
          const parsed = emitChunk(chunk);
          streamError = streamError || resolveStreamError(parsed);
        }
      }
    } catch (error) {
      if (closedByClient || error?.name === "AbortError") {
        const aborted = new Error("Stream aborted");
        aborted.name = "StreamAborted";
        throw aborted;
      }
      throw error;
    }
    const finalChunk = buffer.trim();
    if (finalChunk) {
      const parsed = emitChunk(finalChunk);
      streamError = streamError || resolveStreamError(parsed);
    }
    if (streamError) {
      throw new Error(streamError);
    }
  }

  const promise = read();
  return {
    close() {
      closedByClient = true;
      controller.abort();
    },
    done: promise
  };
}

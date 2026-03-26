import { authFetch } from "../api";

/**
 * T004: Robust SSE Streaming Parser
 * Handles fragmented TCP packets and line-based SSE format (\n\n as message delimiter)
 */
class SSEParser {
  constructor(onEvent) {
    this.onEvent = onEvent;
    this.buffer = "";
  }

  feed(chunk) {
    this.buffer += chunk;
    this.buffer = this.buffer.replace(/\r\n/g, "\n");

    // SSE messages are delimited by double newlines (\n\n)
    let boundary;
    while ((boundary = this.buffer.indexOf("\n\n")) !== -1) {
      const messageBlock = this.buffer.slice(0, boundary);
      this.buffer = this.buffer.slice(boundary + 2);
      this.parseMessage(messageBlock);
    }
  }

  parseMessage(block) {
    if (!block.trim()) return;

    const lines = block.split("\n");
    let eventType = "message";
    let dataBuffer = "";

    for (const line of lines) {
      if (line.startsWith("event:")) {
        eventType = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        const dataPart = line.slice(5).trimStart();
        dataBuffer = dataBuffer ? `${dataBuffer}\n${dataPart}` : dataPart;
      }
    }

    if (dataBuffer) {
      this.onEvent?.(eventType, dataBuffer);
    }
  }

  flush() {
    if (this.buffer.trim()) {
      this.parseMessage(this.buffer);
      this.buffer = "";
    }
  }
}

/**
 * streamChat implementation with AbortController and robust buffer handling
 */
export async function streamChat(path, payload, onEvent) {
  const controller = new AbortController();
  let isClosed = false;

  const res = await authFetch(path, {
    method: "POST",
    headers: {
      "Content-Type": "application/json"
    },
    body: JSON.stringify(payload),
    signal: controller.signal
  });

  if (!res.ok || !res.body) {
    const errorText = await res.text().catch(() => "Stream initiation failed");
    throw new Error(errorText || `HTTP ${res.status}`);
  }

  const reader = res.body.getReader();
  const decoder = new TextDecoder("utf-8");
  const parser = new SSEParser(onEvent);

  const processStream = async () => {
    try {
      while (true) {
        const { value, done } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        parser.feed(chunk);
      }
      parser.flush();
    } catch (err) {
      if (isClosed || err?.name === "AbortError") {
        const abortErr = new Error("Stream aborted by user");
        abortErr.name = "StreamAborted";
        throw abortErr;
      }
      throw err;
    } finally {
      reader.releaseLock();
    }
  };

  return {
    close() {
      isClosed = true;
      controller.abort();
    },
    done: processStream()
  };
}

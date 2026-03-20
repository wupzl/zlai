export function buildChildMap(messages) {
  const map = {};
  (messages || []).forEach((message) => {
    const parentKey = message.parentMessageId || "__root__";
    if (!map[parentKey]) {
      map[parentKey] = [];
    }
    map[parentKey].push(message);
  });
  Object.keys(map).forEach((key) => {
    map[key].sort((left, right) => new Date(left.createdAt) - new Date(right.createdAt));
  });
  return map;
}

export function initBranchSelections(messages, leafMessageId) {
  const byId = indexMessages(messages);
  const selections = {};
  let current = byId[leafMessageId];
  while (current) {
    const parentKey = current.parentMessageId || "__root__";
    selections[parentKey] = current.messageId;
    current = current.parentMessageId ? byId[current.parentMessageId] : null;
  }
  return selections;
}

export function resolveBranchMessages(messages, activeMessageId) {
  if (!activeMessageId) {
    return [];
  }
  const byId = indexMessages(messages);
  const path = [];
  let current = byId[activeMessageId];
  while (current) {
    path.push(current);
    current = current.parentMessageId ? byId[current.parentMessageId] : null;
  }
  return path.reverse();
}

export function resolveDefaultLeaf(childMap, selectedBranchMap, messageId) {
  let currentId = messageId;
  while (currentId) {
    const children = childMap[currentId] || [];
    if (!children.length) {
      break;
    }
    const selected = selectedBranchMap[currentId];
    if (selected && children.find((item) => item.messageId === selected)) {
      currentId = selected;
    } else {
      currentId = children[0].messageId;
    }
  }
  return currentId;
}

export function stripInterruptedMarkers(content) {
  const text = content == null ? "" : String(content);
  return text
    .replace(/\r\n/g, "\n")
    .replace(/\n\s*\[Interrupted by client]\s*$/i, "")
    .replace(/\n\s*\[Interrupted]\s*$/i, "")
    .trim();
}

export function normalizeInterruptedMessages(messages) {
  return (messages || []).map((message) => {
    if (message && message.role === "assistant" && message.status === "INTERRUPTED") {
      return {
        ...message,
        content: stripInterruptedMarkers(message.content)
      };
    }
    return message;
  });
}

function indexMessages(messages) {
  return (messages || []).reduce((acc, message) => {
    acc[message.messageId] = message;
    return acc;
  }, {});
}

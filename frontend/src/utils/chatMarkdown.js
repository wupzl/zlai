import { marked } from "marked";
import katex from "katex";
import DOMPurify from "dompurify";

export function normalizeMathBlocks(text) {
  if (!text) return "";
  let out = text;
  out = out.split("\\[").join("$$");
  out = out.split("\\]").join("$$");
  out = out.split("\\(").join("$");
  out = out.split("\\)").join("$");
  out = out.replace(/^\s*\[\s*$/gm, "$$");
  out = out.replace(/^\s*\]\s*$/gm, "$$");
  out = out.replace(/^\s*\$\s*$/gm, "");
  return out;
}

export function renderChatMarkdown(content) {
  const raw = normalizeMathBlocks(content || "");
  const blockHtml = [];
  const inlineHtml = [];
  let prepared = raw;
  prepared = prepared.replace(/\$\$([\s\S]+?)\$\$/g, (m, expr) => {
    const html = katex.renderToString(expr.trim(), { throwOnError: false, displayMode: true });
    const key = `@@KATEX_BLOCK_${blockHtml.length}@@`;
    blockHtml.push(html);
    return key;
  });
  prepared = prepared.replace(/\$([^\n$]+?)\$/g, (m, expr) => {
    const html = katex.renderToString(expr.trim(), { throwOnError: false, displayMode: false });
    const key = `@@KATEX_INLINE_${inlineHtml.length}@@`;
    inlineHtml.push(html);
    return key;
  });
  let html = marked.parse(prepared, { gfm: true, breaks: true });
  blockHtml.forEach((item, index) => {
    html = html.replace(`@@KATEX_BLOCK_${index}@@`, item);
  });
  inlineHtml.forEach((item, index) => {
    html = html.replace(`@@KATEX_INLINE_${index}@@`, item);
  });
  return DOMPurify.sanitize(html);
}

import { describe, expect, it } from "vitest";
import { mount } from "@vue/test-utils";
import ChatMessageList from "./ChatMessageList.vue";

describe("ChatMessageList citation-bearing messages", () => {
  it("renders assistant markdown content without regression when additive citation metadata exists", () => {
    const wrapper = mount(ChatMessageList, {
      props: {
        messages: [
          {
            messageId: "assistant-1",
            parentMessageId: "user-1",
            role: "assistant",
            content: "**CAP theorem** explanation",
            citations: [
              {
                docId: "doc-1",
                title: "distributed-systems.md",
                sourcePath: "notes/distributed-systems.md",
                excerpt: "CAP theorem excerpt"
              }
            ],
            grounding: {
              status: "grounded",
              groundingScore: 0.9
            }
          }
        ],
        childMap: {},
        activeMessageId: "assistant-1",
        editingMessageId: "",
        editedContent: "",
        isStreaming: false
      }
    });

    expect(wrapper.text()).toContain("assistant");
    expect(wrapper.text()).toContain("Current");
    expect(wrapper.html()).toContain("<strong>CAP theorem</strong>");
    expect(wrapper.text()).toContain("Grounded");
    expect(wrapper.text()).toContain("Sources");
    expect(wrapper.text()).toContain("distributed-systems.md");
    expect(wrapper.text()).toContain("notes/distributed-systems.md");
    expect(wrapper.text()).toContain("CAP theorem excerpt");
    expect(wrapper.find(".message.assistant").exists()).toBe(true);
  });
});

import type { ChatMessage, ToolStatus } from "@/types/chat";
import styles from "./chat.module.css";

interface MessageBubbleProps {
  message: ChatMessage;
}

const TOOL_STATUS_LABEL: Record<ToolStatus, string> = {
  calling: "生成参数",
  running: "执行中",
  done: "完成",
  error: "失败",
};

export function MessageBubble({ message }: MessageBubbleProps) {
  const roleClass = message.role === "user" ? styles.user : styles.assistant;
  const hasBody = message.content.length > 0 || message.thinking.length > 0 || message.tools.length > 0;

  return (
    <div className={`${styles.message} ${roleClass}`}>
      {message.thinking.length > 0 && (
        <details className={styles.thinking}>
          <summary>思考过程</summary>
          <div className={styles.thinkingBody}>{message.thinking}</div>
        </details>
      )}

      {message.tools.length > 0 && (
        <div className={styles.tools}>
          {message.tools.map((tool) => (
            <details key={tool.toolCallId} className={styles.tool}>
              <summary className={styles.toolSummary}>
                <span className={styles.toolName}>{tool.name}</span>
                <span
                  className={`${styles.toolStatus} ${
                    tool.status === "error" ? styles.toolError : styles[tool.status]
                  }`}
                >
                  {TOOL_STATUS_LABEL[tool.status]}
                </span>
              </summary>
              {tool.phase && <div className={styles.toolMeta}>阶段：{tool.phase}</div>}
              {tool.argsText.length > 0 && <pre className={styles.toolBody}>{tool.argsText}</pre>}
              {tool.output.length > 0 && <pre className={styles.toolBody}>{tool.output}</pre>}
            </details>
          ))}
        </div>
      )}

      {message.content.length > 0 && <div>{message.content}</div>}
      {!hasBody && message.status === "streaming" && <div>...</div>}

      {message.status === "aborted" && <div className={styles.messageState}>已中断</div>}
      {message.status === "error" && <div className={styles.messageStateError}>回复失败</div>}
    </div>
  );
}

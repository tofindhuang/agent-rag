import type { ChatMessage } from "@/types/chat";
import styles from "./chat.module.css";

interface MessageBubbleProps {
  message: ChatMessage;
}

export function MessageBubble({ message }: MessageBubbleProps) {
  const roleClass = message.role === "user" ? styles.user : styles.assistant;
  return <div className={`${styles.message} ${roleClass}`}>{message.content || "..."}</div>;
}

import { useEffect, useRef } from "react";

import { Button } from "@/components/ui/Button";
import type { ContextUsageData } from "@/types/gateway";
import { ContextUsageMeter } from "./ContextUsageMeter";
import styles from "./chat.module.css";

interface ComposerProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  disabled: boolean;
  busy: boolean;
  contextUsage: ContextUsageData | null;
  contextCompacted: boolean;
}

const MAX_INPUT_HEIGHT = 200;

export function Composer({
  value,
  onChange,
  onSend,
  onStop,
  disabled,
  busy,
  contextUsage,
  contextCompacted,
}: ComposerProps) {
  const inputRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const input = inputRef.current;
    if (!input) return;
    input.style.height = "auto";
    input.style.height = `${Math.min(input.scrollHeight, MAX_INPUT_HEIGHT)}px`;
  }, [value]);

  return (
    <div className={styles.composer}>
      <div className={styles.composerInner}>
        <textarea
          ref={inputRef}
          className={styles.input}
          value={value}
          rows={1}
          placeholder="输入消息，Enter 发送，Shift+Enter 换行"
          aria-label="消息输入"
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
              event.preventDefault();
              onSend();
            }
          }}
          disabled={disabled}
        />
        {busy ? (
          <Button variant="secondary" onClick={onStop}>
            停止
          </Button>
        ) : (
          <Button onClick={onSend} disabled={disabled || !value.trim()}>
            发送
          </Button>
        )}
      </div>
      {contextUsage ? (
        <div className={styles.composerFooter}>
          <ContextUsageMeter usage={contextUsage} compacted={contextCompacted} />
        </div>
      ) : null}
    </div>
  );
}

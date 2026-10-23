import styles from "./chat.module.css";

interface ComposerProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  onStop: () => void;
  disabled: boolean;
  busy: boolean;
}

export function Composer({ value, onChange, onSend, onStop, disabled, busy }: ComposerProps) {
  return (
    <div className={styles.composer}>
      <div className={styles.composerInner}>
        <input
          className={styles.input}
          value={value}
          placeholder="输入消息，Enter 发送"
          aria-label="消息输入"
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.nativeEvent.isComposing) onSend();
          }}
          disabled={disabled}
        />
        {busy ? (
          <button type="button" className={styles.button} onClick={onStop}>
            停止
          </button>
        ) : (
          <button
            type="button"
            className={styles.button}
            onClick={onSend}
            disabled={disabled || !value.trim()}
          >
            发送
          </button>
        )}
      </div>
    </div>
  );
}

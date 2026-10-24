import type { ContextUsageData } from "@/types/gateway";
import styles from "./chat.module.css";

/** Live context occupancy; hidden until the gateway sends context_usage. */
export function ContextUsageMeter({
  usage,
  compacted,
}: {
  usage: ContextUsageData;
  compacted: boolean;
}) {
  const percent = usage.percent;
  const warning =
    usage.compactionEnabled && percent != null && percent >= usage.compactionThresholdPercent;
  const title =
    percent == null
      ? "压缩后等待下一条回复重新统计"
      : `${usage.tokens ?? "?"} / ${usage.contextWindow} tokens`;

  let label: string;
  if (!usage.compactionEnabled) {
    label =
      percent == null
        ? "压缩已关闭"
        : `上下文 ${percent}% · 压缩已关闭`;
  } else if (percent == null) {
    label = "上下文统计中";
  } else {
    label = `上下文 ${percent}% · 压缩阈值 ${usage.compactionThresholdPercent}%`;
  }

  return (
    <span
      className={`${styles.contextMeter}${warning ? ` ${styles.contextMeterWarn}` : ""}`}
      title={title}
      aria-label={label}
    >
      {percent != null ? (
        <span className={styles.contextBar} aria-hidden="true">
          <span
            className={styles.contextBarFill}
            style={{ width: `${Math.min(100, Math.max(0, percent))}%` }}
          />
        </span>
      ) : null}
      <span>
        {label}
        {compacted ? <span className={styles.contextCompacted}>已压缩</span> : null}
      </span>
    </span>
  );
}

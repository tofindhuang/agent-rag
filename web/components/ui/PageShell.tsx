import Link from "next/link";

import styles from "./page-shell.module.css";

interface PageShellProps {
  title: string;
  subtitle?: string;
  backHref?: string;
  backLabel?: string;
  children: React.ReactNode;
}

/**
 * Shared chrome for standalone pages (settings, knowledge): a sticky header
 * with a back affordance plus a centered content column, matching the chat
 * page's header and spacing tokens.
 */
export function PageShell({
  title,
  subtitle,
  backHref = "/",
  backLabel = "返回聊天",
  children,
}: PageShellProps) {
  return (
    <div className={styles.shell}>
      <header className={styles.header}>
        <div className={styles.headerInner}>
          <Link className={styles.back} href={backHref}>
            ← {backLabel}
          </Link>
          <div className={styles.titles}>
            <h1 className={styles.title}>{title}</h1>
            {subtitle ? <p className={styles.subtitle}>{subtitle}</p> : null}
          </div>
        </div>
      </header>
      <main className={styles.content}>{children}</main>
    </div>
  );
}

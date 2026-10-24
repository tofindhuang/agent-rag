"use client";

import { useEffect, useId, useRef, useState } from "react";
import { createPortal } from "react-dom";

import styles from "./ui.module.css";

interface DialogProps {
  title: string;
  description?: string;
  children?: React.ReactNode;
  actions?: React.ReactNode;
  onClose: () => void;
  /** Irreversible confirmations keep the overlay click from closing. */
  closeOnOverlay?: boolean;
}

const FOCUSABLE =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Modal dialog (ticket visual-02): Esc closes, focus starts inside, Tab stays
 * inside, and closing returns focus to the element that opened it.
 */
export function Dialog({
  title,
  description,
  children,
  actions,
  onClose,
  closeOnOverlay = true,
}: DialogProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const descriptionId = useId();
  // Captured during the first render: autoFocus moves focus into the dialog
  // during commit, before passive effects run, so reading it there is too late.
  const [trigger] = useState<HTMLElement | null>(() =>
    typeof document === "undefined" ? null : (document.activeElement as HTMLElement | null),
  );
  // Portaling to <body> keeps fixed positioning relative to the viewport: a
  // sticky/backdrop-filtered ancestor would otherwise become its containing
  // block and push the panel off-screen.
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  useEffect(() => {
    if (!mounted) return;
    const panel = panelRef.current;
    const focusables = panel ? [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)] : [];
    (focusables[0] ?? panel)?.focus();
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";

    return () => {
      document.body.style.overflow = previousOverflow;
      if (trigger?.isConnected) trigger.focus();
    };
  }, [trigger, mounted]);

  const onKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    if (event.key === "Escape") {
      event.stopPropagation();
      onClose();
      return;
    }
    if (event.key !== "Tab") return;

    const panel = panelRef.current;
    if (!panel) return;
    const focusables = [...panel.querySelectorAll<HTMLElement>(FOCUSABLE)];
    if (focusables.length === 0) {
      event.preventDefault();
      return;
    }
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    const active = document.activeElement;
    if (!event.shiftKey && active === last) {
      event.preventDefault();
      first.focus();
    } else if (event.shiftKey && (active === first || active === panel)) {
      event.preventDefault();
      last.focus();
    }
  };

  if (!mounted) return null;

  return createPortal(
    <div
      className={styles.overlay}
      onMouseDown={(event) => {
        if (closeOnOverlay && event.target === event.currentTarget) onClose();
      }}
    >
      <div
        ref={panelRef}
        className={styles.panel}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <h2 id={titleId} className={styles.title}>
          {title}
        </h2>
        {description ? (
          <p id={descriptionId} className={styles.description}>
            {description}
          </p>
        ) : null}
        {children ? <div className={styles.dialogBody}>{children}</div> : null}
        {actions ? <div className={styles.actions}>{actions}</div> : null}
      </div>
    </div>,
    document.body,
  );
}

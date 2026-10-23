"use client";

import Link from "next/link";
import { useEffect, useRef, useState } from "react";
import { useAuth } from "./AuthProvider";
import styles from "./account-menu.module.css";

/** Avatar in the chat header: identity, settings and logout in one menu. */
export function AccountMenu() {
  const { user, logout } = useAuth();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);
  const buttonRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!rootRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== "Escape") return;
      setOpen(false);
      buttonRef.current?.focus();
    };
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  const initial = (user?.email?.trim()[0] ?? "?").toUpperCase();

  return (
    <div className={styles.root} ref={rootRef}>
      <button
        ref={buttonRef}
        className={styles.avatar}
        type="button"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="账号菜单"
        onClick={() => setOpen((previous) => !previous)}
      >
        {initial}
      </button>

      {open ? (
        <div className={styles.menu} role="menu" aria-label="账号">
          <div className={styles.identity}>
            <span className={styles.email} title={user?.email}>
              {user?.email}
            </span>
            <span className={styles.caption}>已登录</span>
          </div>
          <Link
            className={styles.item}
            role="menuitem"
            href="/settings"
            onClick={() => setOpen(false)}
          >
            设置
          </Link>
          <button
            className={styles.itemDanger}
            role="menuitem"
            type="button"
            onClick={() => {
              setOpen(false);
              void logout();
            }}
          >
            退出登录
          </button>
        </div>
      ) : null}
    </div>
  );
}

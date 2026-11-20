"use client";

import { useEffect, useState } from "react";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import uiStyles from "@/components/ui/ui.module.css";
import {
  fetchProfileCollections,
  fetchSessionCollections,
  setSessionCollections,
} from "@/lib/api/profile";
import { userMessage } from "@/lib/api/errors";
import type { ProfileCollection } from "@/types/profile";
import styles from "./chat.module.css";

interface SessionMemorySelectProps {
  sessionId: string;
  disabled: boolean;
}

/** Header control for the memory collections injected into this session. */
export function SessionMemorySelect({ sessionId, disabled }: SessionMemorySelectProps) {
  const [collections, setCollections] = useState<ProfileCollection[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [open, setOpen] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let active = true;
    Promise.all([fetchProfileCollections(), fetchSessionCollections(sessionId)])
      .then(([nextCollections, nextSelected]) => {
        if (!active) return;
        setCollections(nextCollections);
        setSelected(new Set(nextSelected));
      })
      .catch(() => {
        // Header remains usable; the session keeps its current selection.
      });
    return () => {
      active = false;
    };
  }, [sessionId]);

  const selectedNames = collections
    .filter((collection) => selected.has(collection.id))
    .map((collection) => collection.name);
  const label =
    selectedNames.length === 0
      ? "通用"
      : selectedNames.length <= 2
        ? selectedNames.join("、")
        : `${selectedNames[0]} 等 ${selectedNames.length} 个`;

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      await setSessionCollections(sessionId, [...selected]);
      setOpen(false);
    } catch (cause: unknown) {
      setError(userMessage(cause));
    } finally {
      setSaving(false);
    }
  };

  return (
    <>
      <button
        type="button"
        className={styles.memoryTag}
        title="当前会话使用的长期记忆集"
        disabled={disabled}
        onClick={() => setOpen(true)}
      >
        记忆：{label}
      </button>
      {open ? (
        <Dialog
          title="会话记忆集"
          description="只注入选中集合中的长期记忆；「通用」默认启用，收件箱需手动选择。"
          onClose={() => setOpen(false)}
          actions={
            <>
              <Button variant="secondary" onClick={() => setOpen(false)} disabled={saving}>
                取消
              </Button>
              <Button onClick={() => void save()} disabled={saving}>
                {saving ? "保存中…" : "保存"}
              </Button>
            </>
          }
        >
          <div className={uiStyles.checkboxGroup}>
            {collections.map((collection) => (
              <label key={collection.id} className={uiStyles.checkboxRow}>
                <input
                  type="checkbox"
                  checked={selected.has(collection.id)}
                  onChange={(event) => {
                    setSelected((previous) => {
                      const next = new Set(previous);
                      if (event.target.checked) next.add(collection.id);
                      else next.delete(collection.id);
                      return next;
                    });
                  }}
                />
                {collection.name}
                {collection.systemKey === "inbox" ? "（默认不注入）" : ""}
              </label>
            ))}
          </div>
          {collections.length === 0 ? (
            <p className={uiStyles.hint}>还没有记忆集。</p>
          ) : null}
          {error ? <p className={styles.memoryError}>{error}</p> : null}
        </Dialog>
      ) : null}
    </>
  );
}

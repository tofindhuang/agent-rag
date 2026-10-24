"use client";

import { useEffect, useMemo, useState } from "react";
import {
  deleteProfileFact,
  fetchProfileFacts,
  fetchProfileSettings,
  updateProfileFact,
  updateProfileSettings,
} from "@/lib/api/profile";
import { userMessage } from "@/lib/api/errors";
import type { ProfileFact } from "@/types/profile";
import styles from "./settings.module.css";

const CATEGORIES = ["preference", "project", "profile", "other"];

export function MemorySettings() {
  const [enabled, setEnabled] = useState(true);
  const [facts, setFacts] = useState<ProfileFact[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [draftText, setDraftText] = useState("");
  const [draftCategory, setDraftCategory] = useState("preference");

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextEnabled, all] = await Promise.all([
        fetchProfileSettings(),
        fetchProfileFacts("all"),
      ]);
      setEnabled(nextEnabled.enabled);
      setFacts(all);
    } catch (cause: unknown) {
      setError(userMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const active = useMemo(
    () => facts.filter((fact) => fact.status === "active"),
    [facts],
  );
  const history = useMemo(
    () => facts.filter((fact) => fact.status !== "active"),
    [facts],
  );

  const toggle = async (next: boolean) => {
    setEnabled(next);
    setError(null);
    try {
      await updateProfileSettings(next);
    } catch (cause: unknown) {
      setEnabled(!next);
      setError(userMessage(cause));
    }
  };

  const startEdit = (fact: ProfileFact) => {
    setEditingId(fact.id);
    setDraftText(fact.factText);
    setDraftCategory(fact.category);
  };

  const saveEdit = async (fact: ProfileFact) => {
    if (!draftText.trim()) return;
    setError(null);
    try {
      const updated = await updateProfileFact(fact.id, {
        factText: draftText.trim(),
        category: draftCategory,
      });
      setFacts((previous) =>
        previous.map((item) => (item.id === updated.id ? updated : item)),
      );
      setEditingId(null);
    } catch (cause: unknown) {
      setError(userMessage(cause));
    }
  };

  const remove = async (fact: ProfileFact) => {
    if (!window.confirm("删除这条长期记忆？后续会话将不再使用它。")) return;
    setError(null);
    try {
      await deleteProfileFact(fact.id);
      setFacts((previous) =>
        previous.map((item) =>
          item.id === fact.id ? { ...item, status: "deleted" } : item,
        ),
      );
    } catch (cause: unknown) {
      setError(userMessage(cause));
    }
  };

  return (
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2>长期记忆</h2>
        <label className={styles.toggle}>
          <input
            type="checkbox"
            checked={enabled}
            onChange={(event) => void toggle(event.target.checked)}
          />
          启用记忆
        </label>
      </div>
      <p className={styles.hint}>
        稳定偏好与项目背景会在后续会话中作为背景信息使用；关闭后停止提取与注入，既有事实仍保留。
      </p>
      {error ? <p className={styles.error}>{error}</p> : null}
      {loading ? <p className={styles.hint}>加载中…</p> : null}

      {!loading && active.length === 0 ? (
        <p className={styles.hint}>还没有长期记忆。</p>
      ) : null}
      <ul className={styles.memoryList}>
        {active.map((fact) => (
          <li key={fact.id} className={styles.memoryItem}>
            {editingId === fact.id ? (
              <div className={styles.memoryEditor}>
                <textarea
                  className={styles.memoryInput}
                  value={draftText}
                  onChange={(event) => setDraftText(event.target.value)}
                  rows={2}
                />
                <div className={styles.memoryActions}>
                  <select
                    className={styles.input}
                    value={draftCategory}
                    onChange={(event) => setDraftCategory(event.target.value)}
                  >
                    {CATEGORIES.map((category) => (
                      <option key={category} value={category}>
                        {category}
                      </option>
                    ))}
                  </select>
                  <button type="button" onClick={() => void saveEdit(fact)}>
                    保存
                  </button>
                  <button type="button" onClick={() => setEditingId(null)}>
                    取消
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className={styles.memoryText}>
                  <span className={styles.tag}>{fact.category}</span>
                  <span>{fact.factText}</span>
                </div>
                <div className={styles.memoryActions}>
                  <span className={styles.hint}>
                    {fact.updatedAt ? new Date(fact.updatedAt).toLocaleString() : ""}
                  </span>
                  <button type="button" onClick={() => startEdit(fact)}>
                    编辑
                  </button>
                  <button type="button" onClick={() => void remove(fact)}>
                    删除
                  </button>
                </div>
              </>
            )}
          </li>
        ))}
      </ul>

      {history.length > 0 ? (
        <details className={styles.history}>
          <summary>历史版本（{history.length}）</summary>
          <ul className={styles.memoryList}>
            {history.map((fact) => (
              <li key={fact.id} className={styles.memoryItem}>
                <div className={styles.memoryText}>
                  <span className={styles.tag}>{fact.status}</span>
                  <span>{fact.factText}</span>
                </div>
                <span className={styles.hint}>
                  {fact.updatedAt ? new Date(fact.updatedAt).toLocaleString() : ""}
                </span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </section>
  );
}

"use client";

import { useEffect, useMemo, useState } from "react";
import {
  createProfileCollection,
  deleteProfileCollection,
  deleteProfileFact,
  fetchProfileCollections,
  fetchProfileFacts,
  fetchProfileSettings,
  moveProfileFacts,
  renameProfileCollection,
  updateProfileFact,
  updateProfileSettings,
} from "@/lib/api/profile";
import { userMessage } from "@/lib/api/errors";
import type { ProfileCollection, ProfileFact } from "@/types/profile";
import styles from "./settings.module.css";

const CATEGORIES = ["preference", "project", "profile", "other"];

interface EditDraft {
  id: string;
  factTitle: string;
  factText: string;
  category: string;
  collectionId: string;
}

export function MemorySettings() {
  const [enabled, setEnabled] = useState(true);
  const [facts, setFacts] = useState<ProfileFact[]>([]);
  const [collections, setCollections] = useState<ProfileCollection[]>([]);
  const [filter, setFilter] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editing, setEditing] = useState<EditDraft | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [moveTarget, setMoveTarget] = useState("");
  const [newCollectionName, setNewCollectionName] = useState("");

  const load = async () => {
    setLoading(true);
    setError(null);
    try {
      const [nextEnabled, nextCollections, all] = await Promise.all([
        fetchProfileSettings(),
        fetchProfileCollections(),
        fetchProfileFacts("all"),
      ]);
      setEnabled(nextEnabled.enabled);
      setCollections(nextCollections);
      setFacts(all);
      setMoveTarget((current) => current || nextCollections[0]?.id || "");
    } catch (cause: unknown) {
      setError(userMessage(cause));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void load();
  }, []);

  const collectionName = (id: string) =>
    collections.find((collection) => collection.id === id)?.name ?? "未知集合";

  const visible = useMemo(
    () =>
      facts.filter(
        (fact) => fact.status === "active" && (filter === "all" || fact.collectionId === filter),
      ),
    [facts, filter],
  );
  const history = useMemo(
    () =>
      facts.filter(
        (fact) => fact.status !== "active" && (filter === "all" || fact.collectionId === filter),
      ),
    [facts, filter],
  );

  const toggleMemory = async (next: boolean) => {
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
    setEditing({
      id: fact.id,
      factTitle: fact.factTitle,
      factText: fact.factText,
      category: fact.category,
      collectionId: fact.collectionId,
    });
  };

  const saveEdit = async () => {
    if (!editing || !editing.factText.trim() || !editing.factTitle.trim()) return;
    setError(null);
    try {
      const updated = await updateProfileFact(editing.id, {
        factTitle: editing.factTitle.trim(),
        factText: editing.factText.trim(),
        category: editing.category,
        collectionId: editing.collectionId,
      });
      setFacts((previous) =>
        previous.map((item) => (item.id === updated.id ? updated : item)),
      );
      setEditing(null);
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

  const toggleSelected = (id: string) => {
    setSelectedIds((previous) => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const moveSelected = async () => {
    if (selectedIds.size === 0 || !moveTarget) return;
    setError(null);
    try {
      await moveProfileFacts([...selectedIds], moveTarget);
      setSelectedIds(new Set());
      await load();
    } catch (cause: unknown) {
      setError(userMessage(cause));
    }
  };

  const addCollection = async () => {
    const name = newCollectionName.trim();
    if (!name) return;
    setError(null);
    try {
      const created = await createProfileCollection(name);
      setCollections((previous) => [...previous, created]);
      setNewCollectionName("");
    } catch (cause: unknown) {
      setError(userMessage(cause));
    }
  };

  const renameCollection = async (collection: ProfileCollection) => {
    const name = window.prompt("新的集合名称", collection.name)?.trim();
    if (!name || name === collection.name) return;
    setError(null);
    try {
      const updated = await renameProfileCollection(collection.id, name);
      setCollections((previous) =>
        previous.map((item) => (item.id === updated.id ? updated : item)),
      );
    } catch (cause: unknown) {
      setError(userMessage(cause));
    }
  };

  const removeCollection = async (collection: ProfileCollection) => {
    if (!window.confirm(`删除「${collection.name}」？其中的记忆会移回收件箱。`)) return;
    setError(null);
    try {
      await deleteProfileCollection(collection.id);
      if (filter === collection.id) setFilter("all");
      await load();
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
            onChange={(event) => void toggleMemory(event.target.checked)}
          />
          启用记忆
        </label>
      </div>
      <p className={styles.hint}>
        稳定偏好与项目背景会在后续会话中作为背景信息使用；关闭后停止提取与注入，既有事实仍保留。
      </p>
      {error ? <p className={styles.error}>{error}</p> : null}
      {loading ? <p className={styles.hint}>加载中…</p> : null}

      <div className={styles.collectionBar}>
        <button
          type="button"
          className={filter === "all" ? styles.collectionActive : styles.collectionChip}
          onClick={() => setFilter("all")}
        >
          全部
        </button>
        {collections.map((collection) => (
          <span key={collection.id} className={styles.collectionChipWrap}>
            <button
              type="button"
              className={filter === collection.id ? styles.collectionActive : styles.collectionChip}
              onClick={() => setFilter(collection.id)}
            >
              {collection.name}（{collection.factCount}）
            </button>
            {collection.systemKey == null ? (
              <>
                <button
                  type="button"
                  className={styles.collectionAction}
                  onClick={() => void renameCollection(collection)}
                  title="重命名集合"
                >
                  ✎
                </button>
                <button
                  type="button"
                  className={styles.collectionAction}
                  onClick={() => void removeCollection(collection)}
                  title="删除集合"
                >
                  ×
                </button>
              </>
            ) : null}
          </span>
        ))}
      </div>

      <div className={styles.row}>
        <input
          className={styles.input}
          placeholder="新建记忆集（例如：项目 A）"
          value={newCollectionName}
          onChange={(event) => setNewCollectionName(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") void addCollection();
          }}
        />
        <button type="button" onClick={() => void addCollection()} disabled={!newCollectionName.trim()}>
          新建集合
        </button>
      </div>

      {selectedIds.size > 0 ? (
        <div className={styles.row}>
          <span className={styles.hint}>已选 {selectedIds.size} 条</span>
          <select
            className={styles.input}
            value={moveTarget}
            onChange={(event) => setMoveTarget(event.target.value)}
          >
            {collections.map((collection) => (
              <option key={collection.id} value={collection.id}>
                {collection.name}
              </option>
            ))}
          </select>
          <button type="button" onClick={() => void moveSelected()}>
            移动
          </button>
        </div>
      ) : null}

      {!loading && visible.length === 0 ? (
        <p className={styles.hint}>这个集合里还没有长期记忆。</p>
      ) : null}
      <ul className={styles.memoryList}>
        {visible.map((fact) => (
          <li key={fact.id} className={styles.memoryItem}>
            <input
              type="checkbox"
              checked={selectedIds.has(fact.id)}
              onChange={() => toggleSelected(fact.id)}
              aria-label={`选择 ${fact.factTitle}`}
            />
            {editing?.id === fact.id ? (
              <div className={styles.memoryEditor}>
                <input
                  className={styles.input}
                  value={editing.factTitle}
                  maxLength={80}
                  onChange={(event) =>
                    setEditing({ ...editing, factTitle: event.target.value })
                  }
                  placeholder="标题"
                />
                <textarea
                  className={styles.memoryInput}
                  value={editing.factText}
                  onChange={(event) =>
                    setEditing({ ...editing, factText: event.target.value })
                  }
                  rows={2}
                />
                <div className={styles.memoryActions}>
                  <select
                    className={styles.input}
                    value={editing.category}
                    onChange={(event) =>
                      setEditing({ ...editing, category: event.target.value })
                    }
                  >
                    {CATEGORIES.map((category) => (
                      <option key={category} value={category}>
                        {category}
                      </option>
                    ))}
                  </select>
                  <select
                    className={styles.input}
                    value={editing.collectionId}
                    onChange={(event) =>
                      setEditing({ ...editing, collectionId: event.target.value })
                    }
                  >
                    {collections.map((collection) => (
                      <option key={collection.id} value={collection.id}>
                        {collection.name}
                      </option>
                    ))}
                  </select>
                  <button type="button" onClick={() => void saveEdit()}>
                    保存
                  </button>
                  <button type="button" onClick={() => setEditing(null)}>
                    取消
                  </button>
                </div>
              </div>
            ) : (
              <>
                <div className={styles.memoryText}>
                  <span className={styles.tag}>{fact.category}</span>
                  <span className={styles.memoryTitle}>{fact.factTitle}</span>
                  <span className={styles.hint}>{fact.factText}</span>
                  <span className={styles.hint}>· {collectionName(fact.collectionId)}</span>
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
                  <span className={styles.memoryTitle}>{fact.factTitle}</span>
                  <span className={styles.hint}>{collectionName(fact.collectionId)}</span>
                </div>
                <span className={styles.hint}>
                  {fact.supersedesId ? `取代 ${fact.supersedesId.slice(0, 8)} · ` : ""}
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

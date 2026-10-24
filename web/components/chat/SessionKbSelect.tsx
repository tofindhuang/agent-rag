"use client";

import { useEffect, useState } from "react";
import { fetchKnowledgeBases } from "@/lib/api/knowledge";
import type { KbKnowledgeBase } from "@/types/knowledge";
import styles from "./chat.module.css";

interface SessionKbSelectProps {
  currentKbId: string | null;
  disabled: boolean;
  onChange: (kbId: string | null) => void;
}

/** Header selector; changing only affects future kb_search calls. */
export function SessionKbSelect({ currentKbId, disabled, onChange }: SessionKbSelectProps) {
  const [knowledgeBases, setKnowledgeBases] = useState<KbKnowledgeBase[]>([]);

  useEffect(() => {
    let active = true;
    fetchKnowledgeBases()
      .then((items) => {
        if (active) setKnowledgeBases(items);
      })
      .catch(() => {
        // The header stays usable without a list; the session keeps its KB.
      });
    return () => {
      active = false;
    };
  }, []);

  return (
    <label className={styles.kbTag} title="当前会话使用的知识库">
      <span>知识库</span>
      <select
        className={styles.kbSelect}
        value={currentKbId ?? ""}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value || null)}
      >
        <option value="">不使用</option>
        {knowledgeBases.map((kb) => (
          <option key={kb.id} value={kb.id}>
            {kb.name}
          </option>
        ))}
      </select>
    </label>
  );
}

"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { TextField } from "@/components/ui/TextField";
import {
  fetchKbSettings,
  knowledgeError,
  probeKbSettings,
  saveKbSettings,
} from "@/lib/api/knowledge";
import type {
  KbProbeResult,
  KbSettings,
  KbSettingsInput,
} from "@/types/knowledge";
import styles from "./knowledge.module.css";

interface Draft {
  embeddingBaseUrl: string;
  embeddingApiKey: string;
  embeddingModel: string;
  embeddingDimension: string;
  embeddingPath: string;
  rerankEnabled: boolean;
  rerankBaseUrl: string;
  rerankApiKey: string;
  rerankModel: string;
  rerankPath: string;
}

const SOURCE_LABEL = {
  user: "用户配置",
  system: "部署默认",
  none: "未配置",
} as const;

const RERANK_SOURCE_LABEL = {
  ...SOURCE_LABEL,
  disabled: "已关闭",
} as const;

function toDraft(settings: KbSettings): Draft {
  return {
    embeddingBaseUrl: settings.embedding.baseUrl ?? "",
    embeddingApiKey: "",
    embeddingModel: settings.embedding.model ?? "",
    embeddingDimension:
      settings.embedding.dimension == null ? "" : String(settings.embedding.dimension),
    embeddingPath: settings.embedding.path ?? "",
    rerankEnabled: settings.rerank.enabled,
    rerankBaseUrl: settings.rerank.baseUrl ?? "",
    rerankApiKey: "",
    rerankModel: settings.rerank.model ?? "",
    rerankPath: settings.rerank.path ?? "",
  };
}

function toInput(draft: Draft): KbSettingsInput | string {
  const baseUrl = draft.embeddingBaseUrl.trim();
  const model = draft.embeddingModel.trim();
  const dimension = Number(draft.embeddingDimension);
  if (baseUrl.length === 0 || model.length === 0) {
    return "请填写 embedding 的 Base URL 与模型";
  }
  if (!Number.isInteger(dimension) || dimension <= 0) {
    return "向量维度必须是正整数";
  }
  const embedding: KbSettingsInput["embedding"] = {
    baseUrl,
    model,
    dimension,
    path: draft.embeddingPath.trim() || undefined,
  };
  const key = draft.embeddingApiKey.trim();
  if (key.length > 0) embedding.apiKey = key;

  if (!draft.rerankEnabled) {
    return { embedding, rerank: { enabled: false } };
  }
  const rerankBaseUrl = draft.rerankBaseUrl.trim();
  const rerankModel = draft.rerankModel.trim();
  if (rerankBaseUrl.length === 0 || rerankModel.length === 0) {
    return "启用 rerank 时需填写 Base URL 与模型";
  }
  const rerankKey = draft.rerankApiKey.trim();
  return {
    embedding,
    rerank: {
      enabled: true,
      baseUrl: rerankBaseUrl,
      model: rerankModel,
      path: draft.rerankPath.trim() || undefined,
      ...(rerankKey.length > 0 ? { apiKey: rerankKey } : {}),
    },
  };
}

/** Embedding/rerank form with probe and source markers (FR-2). */
export function SettingsPanel() {
  const [settings, setSettings] = useState<KbSettings | null>(null);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState<"probe" | "save" | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [probe, setProbe] = useState<KbProbeResult | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchKbSettings()
      .then((loaded) => {
        if (cancelled) return;
        setSettings(loaded);
        setDraft(toDraft(loaded));
      })
      .catch((cause: unknown) => {
        if (!cancelled) setError(knowledgeError(cause));
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const update = <K extends keyof Draft>(key: K, value: Draft[K]) => {
    setDraft((previous) => (previous ? { ...previous, [key]: value } : previous));
    setProbe(null);
    setNotice(null);
  };

  const run = async (mode: "probe" | "save") => {
    if (!draft) return;
    const input = toInput(draft);
    setError(null);
    setNotice(null);
    if (typeof input === "string") {
      setError(input);
      return;
    }
    setBusy(mode);
    try {
      if (mode === "probe") {
        setProbe(await probeKbSettings(input));
        return;
      }
      const changed =
        settings !== null &&
        (settings.embedding.model !== draft.embeddingModel.trim() ||
          settings.embedding.dimension !== Number(draft.embeddingDimension));
      const saved = await saveKbSettings(input);
      setSettings(saved);
      setDraft(toDraft(saved));
      setProbe(null);
      setNotice(
        changed
          ? "设置已保存。embedding 变化会把现有文档标记为需重建，请在文档列表执行「全部重建」。"
          : "设置已保存。",
      );
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setBusy(null);
    }
  };

  if (loading) {
    return (
      <section className={styles.section}>
        <p className={styles.hint} role="status">
          设置加载中…
        </p>
      </section>
    );
  }

  return (
    <section className={styles.section} aria-labelledby="kb-settings-title">
      <div className={styles.sectionHeader}>
        <h2 id="kb-settings-title">Embedding 与重排</h2>
      </div>

      {settings ? (
        <p className={styles.hint}>
          Embedding：{SOURCE_LABEL[settings.embedding.source]}
          {settings.embedding.apiKeyHint
            ? ` · 已存密钥 ${settings.embedding.apiKeyHint}`
            : ""}
          {" · "}
          Rerank：{RERANK_SOURCE_LABEL[settings.rerank.source]}
          {settings.rerank.apiKeyHint ? ` · 已存密钥 ${settings.rerank.apiKeyHint}` : ""}
        </p>
      ) : null}

      {draft ? (
        <form
          className={styles.form}
          onSubmit={(event) => {
            event.preventDefault();
            void run("save");
          }}
        >
          <h3 className={styles.hint}>Embedding</h3>
          <div className={styles.formGrid}>
            <TextField
              label="Base URL"
              value={draft.embeddingBaseUrl}
              onChange={(event) => update("embeddingBaseUrl", event.target.value)}
              placeholder="https://api.example.com/v1"
              maxLength={1000}
              required
            />
            <TextField
              label="API Key"
              type="password"
              value={draft.embeddingApiKey}
              onChange={(event) => update("embeddingApiKey", event.target.value)}
              placeholder={settings?.embedding.apiKeyHint ?? "留空则保留已存密钥"}
              maxLength={4096}
              autoComplete="off"
            />
            <TextField
              label="模型"
              value={draft.embeddingModel}
              onChange={(event) => update("embeddingModel", event.target.value)}
              placeholder="BAAI/bge-m3"
              maxLength={200}
              required
            />
            <TextField
              label="向量维度"
              type="number"
              min={1}
              max={65535}
              value={draft.embeddingDimension}
              onChange={(event) => update("embeddingDimension", event.target.value)}
              placeholder="1024"
              required
            />
            <TextField
              label="路径"
              value={draft.embeddingPath}
              onChange={(event) => update("embeddingPath", event.target.value)}
              placeholder="/embeddings"
              maxLength={255}
            />
          </div>

          <label className={styles.checkboxRow}>
            <input
              type="checkbox"
              checked={draft.rerankEnabled}
              onChange={(event) => update("rerankEnabled", event.target.checked)}
            />
            启用 rerank（检索后重排）
          </label>

          {draft.rerankEnabled ? (
            <div className={styles.formGrid}>
              <TextField
                label="Rerank Base URL"
                value={draft.rerankBaseUrl}
                onChange={(event) => update("rerankBaseUrl", event.target.value)}
                placeholder="https://api.example.com/v1"
                maxLength={1000}
                required
              />
              <TextField
                label="Rerank API Key"
                type="password"
                value={draft.rerankApiKey}
                onChange={(event) => update("rerankApiKey", event.target.value)}
                placeholder={settings?.rerank.apiKeyHint ?? "留空则保留已存密钥"}
                maxLength={4096}
                autoComplete="off"
              />
              <TextField
                label="Rerank 模型"
                value={draft.rerankModel}
                onChange={(event) => update("rerankModel", event.target.value)}
                placeholder="BAAI/bge-reranker-v2-m3"
                maxLength={200}
                required
              />
              <TextField
                label="Rerank 路径"
                value={draft.rerankPath}
                onChange={(event) => update("rerankPath", event.target.value)}
                placeholder="/rerank"
                maxLength={255}
              />
            </div>
          ) : null}

          <p className={styles.hint}>
            保存前会先探测端点并校验向量维度。修改 embedding 模型或维度会把现有文档标记为需重建。
          </p>

          <div className={styles.formActions}>
            <Button
              variant="secondary"
              type="button"
              disabled={busy !== null}
              onClick={() => void run("probe")}
            >
              {busy === "probe" ? "探测中…" : "探测"}
            </Button>
            <Button type="submit" disabled={busy !== null}>
              {busy === "save" ? "保存中…" : "保存设置"}
            </Button>
          </div>

          {probe ? (
            <ul className={styles.probeList} aria-live="polite">
              {probe.embedding ? (
                <li
                  className={probe.embedding.ok ? styles.probeOk : styles.probeFail}
                >
                  Embedding：
                  {probe.embedding.ok
                    ? `正常，维度 ${probe.embedding.dimension}，${probe.embedding.latencyMs} ms`
                    : `失败：${probe.embedding.error ?? "未知错误"}`}
                </li>
              ) : null}
              {probe.rerank ? (
                <li className={probe.rerank.ok ? styles.probeOk : styles.probeFail}>
                  Rerank：
                  {probe.rerank.ok
                    ? `正常，样例分数 ${probe.rerank.score?.toFixed(3) ?? "—"}，${probe.rerank.latencyMs} ms`
                    : `失败：${probe.rerank.error ?? "未知错误"}`}
                </li>
              ) : null}
            </ul>
          ) : null}

          {error ? <Banner tone="error">{error}</Banner> : null}
          {notice ? <Banner tone="notice">{notice}</Banner> : null}
        </form>
      ) : (
        error && <Banner tone="error">{error}</Banner>
      )}
    </section>
  );
}

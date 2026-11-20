"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import { TextField } from "@/components/ui/TextField";
import {
  fetchKbModels,
  knowledgeError,
  probeKbRerank,
  probeKbSettings,
  saveKbRerank,
  saveKbSettings,
} from "@/lib/api/knowledge";
import type {
  KbEmbeddingProbe,
  KbModelType,
  KbProbeResult,
  KbRerankProbe,
  KbSettings,
} from "@/types/knowledge";
import styles from "./knowledge.module.css";

const DEFAULT_PATHS: Record<KbModelType, string> = {
  embedding: "/embeddings",
  rerank: "/rerank",
};

interface ServiceDialogProps {
  kind: KbModelType;
  initial: {
    baseUrl: string;
    apiKeyHint: string | null;
    model: string;
    dimension: number | null;
    path: string;
  };
  onClose: () => void;
  onSaved: (settings: KbSettings) => void;
}

/** Add/edit one embedding or rerank service; the model is picked or typed. */
export function ServiceDialog({ kind, initial, onClose, onSaved }: ServiceDialogProps) {
  const isEmbedding = kind === "embedding";
  const [baseUrl, setBaseUrl] = useState(initial.baseUrl);
  const [apiKey, setApiKey] = useState("");
  const [model, setModel] = useState(initial.model);
  const [path, setPath] = useState(initial.path || DEFAULT_PATHS[kind]);
  const [dimension, setDimension] = useState<number | null>(initial.dimension);
  const [models, setModels] = useState<string[]>([]);
  const [manual, setManual] = useState(false);
  const [listMessage, setListMessage] = useState<string | null>(null);
  const [probe, setProbe] = useState<KbProbeResult | null>(null);
  const [busy, setBusy] = useState<"models" | "probe" | "save" | null>(null);
  const [error, setError] = useState<string | null>(null);

  const endpoint = () => baseUrl.trim();
  const key = apiKey.trim();

  const loadModels = async () => {
    setError(null);
    setListMessage(null);
    if (endpoint().length === 0) {
      setError("请先填写 Base URL");
      return;
    }
    setBusy("models");
    try {
      const result = await fetchKbModels({
        type: kind,
        baseUrl: endpoint(),
        ...(key.length > 0 ? { apiKey: key } : {}),
      });
      setModels(result.models);
      setListMessage(result.message);
      if (result.models.length === 0) setManual(true);
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
      setManual(true);
    } finally {
      setBusy(null);
    }
  };

  const runProbe = async (candidate?: string) => {
    const selected = (candidate ?? model).trim();
    setError(null);
    if (selected.length === 0) {
      setError("请选择或输入模型 ID");
      return;
    }
    setBusy("probe");
    try {
      if (isEmbedding) {
        const result = await probeKbSettings({
          embedding: {
            baseUrl: endpoint(),
            model: selected,
            path,
            ...(key.length > 0 ? { apiKey: key } : {}),
          },
        });
        setProbe(result);
        if (result.embedding?.ok && result.embedding.dimension != null) {
          setDimension(result.embedding.dimension);
        } else {
          setDimension(null);
        }
      } else {
        const result = await probeKbRerank({
          baseUrl: endpoint(),
          model: selected,
          path,
          ...(key.length > 0 ? { apiKey: key } : {}),
        });
        setProbe(result);
      }
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
      setProbe(null);
    } finally {
      setBusy(null);
    }
  };

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    if (endpoint().length === 0 || model.trim().length === 0) {
      setError("请填写 Base URL 与模型");
      return;
    }
    if (isEmbedding && dimension == null) {
      setError("请先探测模型维度");
      return;
    }
    setBusy("save");
    try {
      const saved = isEmbedding
        ? await saveKbSettings({
            embedding: {
              baseUrl: endpoint(),
              model: model.trim(),
              dimension: dimension ?? undefined,
              path,
              ...(key.length > 0 ? { apiKey: key } : {}),
            },
          })
        : await saveKbRerank({
            enabled: true,
            baseUrl: endpoint(),
            model: model.trim(),
            path,
            ...(key.length > 0 ? { apiKey: key } : {}),
          });
      onSaved(saved);
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setBusy(null);
    }
  };

  const embeddingProbe: KbEmbeddingProbe | null = probe?.embedding ?? null;
  const rerankProbe: KbRerankProbe | null = probe?.rerank ?? null;
  const probed =
    (isEmbedding && embeddingProbe?.ok === true) || (!isEmbedding && rerankProbe?.ok === true);

  return (
    <Dialog
      title={`${initial.baseUrl.length > 0 ? "编辑" : "添加"} ${isEmbedding ? "Embedding" : "Rerank"} 服务`}
      description={
        isEmbedding
          ? "填写服务地址与密钥，获取模型后选择；维度由探测自动得到。"
          : "填写服务地址与密钥，选择或手填模型后探测；保存后立即启用。"
      }
      onClose={onClose}
      closeOnOverlay={busy === null}
    >
      <form className={styles.form} onSubmit={(event) => void submit(event)}>
        <TextField
          label="Base URL"
          value={baseUrl}
          onChange={(event) => {
            setBaseUrl(event.target.value);
            setModels([]);
            setProbe(null);
            if (isEmbedding) setDimension(null);
          }}
          placeholder="https://api.example.com/v1"
          maxLength={1000}
          required
        />
        <TextField
          label="API Key"
          type="password"
          value={apiKey}
          onChange={(event) => setApiKey(event.target.value)}
          placeholder={
            initial.apiKeyHint
              ? `已存密钥 ${initial.apiKeyHint}，留空保留`
              : "留空则使用已存密钥（部署密钥不会下发）"
          }
          maxLength={4096}
          autoComplete="off"
        />

        <div className={styles.serviceActions}>
          <Button
            variant="secondary"
            size="sm"
            type="button"
            disabled={busy !== null || baseUrl.trim().length === 0}
            onClick={() => void loadModels()}
          >
            {busy === "models" ? "获取中…" : "获取模型"}
          </Button>
          {models.length > 0 ? (
            <Button
              variant="ghost"
              size="sm"
              type="button"
              onClick={() => setManual((previous) => !previous)}
            >
              {manual ? "从列表选择" : "手动输入模型"}
            </Button>
          ) : null}
        </div>

        {listMessage ? <p className={styles.hint}>{listMessage}</p> : null}

        {models.length > 0 && !manual ? (
          <label className={styles.label}>
            <span>模型</span>
            <select
              className={styles.select}
              value={model}
              onChange={(event) => {
                const value = event.target.value;
                setModel(value);
                setProbe(null);
                setDimension(null);
                void runProbe(value);
              }}
            >
              <option value="">请选择模型</option>
              {model.length > 0 && !models.includes(model) ? (
                <option value={model}>{model}（当前）</option>
              ) : null}
              {models.map((candidate) => (
                <option key={candidate} value={candidate}>
                  {candidate}
                </option>
              ))}
            </select>
          </label>
        ) : (
          <div className={styles.uploadRow}>
            <TextField
              className={styles.urlInput}
              label="模型 ID"
              value={model}
              onChange={(event) => {
                setModel(event.target.value);
                setProbe(null);
                setDimension(null);
              }}
              placeholder={isEmbedding ? "BAAI/bge-m3" : "BAAI/bge-reranker-v2-m3"}
              maxLength={200}
            />
            <Button
              variant="secondary"
              size="sm"
              type="button"
              disabled={busy !== null || model.trim().length === 0}
              onClick={() => void runProbe()}
            >
              {busy === "probe" ? "探测中…" : "探测"}
            </Button>
          </div>
        )}

        {probed ? (
          <p className={styles.probeOk} aria-live="polite">
            {isEmbedding
              ? `Embedding 正常 · 维度 ${embeddingProbe?.dimension} · ${embeddingProbe?.latencyMs} ms`
              : `Rerank 正常 · 样例分数 ${rerankProbe?.score?.toFixed(3) ?? "—"} · ${rerankProbe?.latencyMs} ms`}
          </p>
        ) : null}
        {probe && !probed ? (
          <p className={styles.probeFail} aria-live="polite">
            {isEmbedding
              ? `Embedding 失败：${embeddingProbe?.error ?? "未知错误"}`
              : `Rerank 失败：${rerankProbe?.error ?? "未知错误"}`}
          </p>
        ) : null}

        <details className={styles.advanced}>
          <summary>高级</summary>
          <TextField
            label="路径"
            value={path}
            onChange={(event) => {
              setPath(event.target.value);
              setProbe(null);
              if (isEmbedding) setDimension(null);
            }}
            placeholder={DEFAULT_PATHS[kind]}
            maxLength={255}
          />
        </details>

        {error ? <Banner tone="error">{error}</Banner> : null}

        <div className={styles.formActions}>
          <Button variant="secondary" type="button" disabled={busy !== null} onClick={onClose}>
            取消
          </Button>
          <Button type="submit" disabled={busy !== null || !probed}>
            {busy === "save" ? "保存中…" : "保存"}
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

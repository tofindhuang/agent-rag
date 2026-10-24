"use client";

import { useEffect, useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Dialog } from "@/components/ui/Dialog";
import {
  fetchKbSettings,
  knowledgeError,
  resetKbEmbedding,
  resetKbRerank,
  setKbRerankEnabled,
  testKbRetrieval,
} from "@/lib/api/knowledge";
import type {
  KbModelType,
  KbRetrievalTestResult,
  KbSettings,
} from "@/types/knowledge";
import { ServiceDialog } from "./ServiceDialog";
import styles from "./knowledge.module.css";

const EMBEDDING_SOURCE_LABEL = {
  user: "用户配置",
  system: "部署默认",
  none: "未配置",
} as const;

const RERANK_SOURCE_LABEL = {
  ...EMBEDDING_SOURCE_LABEL,
  disabled: "已关闭",
} as const;

const SOURCE_CLASS = {
  user: styles.sourceUser,
  system: styles.sourceSystem,
  none: styles.sourceNone,
  disabled: styles.sourceDisabled,
} as const;

/** Embedding/rerank management cards; services and model choices are separate. */
export function SettingsPanel() {
  const [settings, setSettings] = useState<KbSettings | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<KbModelType | null>(null);
  const [resetTarget, setResetTarget] = useState<KbModelType | null>(null);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<KbRetrievalTestResult | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchKbSettings()
      .then((loaded) => {
        if (!cancelled) setSettings(loaded);
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

  const apply = async (action: () => Promise<KbSettings>, success: string) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      setSettings(await action());
      setTestResult(null);
      setNotice(success);
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setBusy(false);
    }
  };

  const runTest = async () => {
    setTesting(true);
    setError(null);
    setNotice(null);
    try {
      setTestResult(await testKbRetrieval());
    } catch (cause: unknown) {
      console.error(cause);
      setError(knowledgeError(cause));
    } finally {
      setTesting(false);
    }
  };

  const toggleRerank = (enabled: boolean) =>
    apply(
      () => setKbRerankEnabled(enabled),
      enabled ? "已启用重排" : "已关闭重排；现有配置保留，可随时重新开启",
    );

  const confirmReset = async () => {
    const target = resetTarget;
    setResetTarget(null);
    if (!target) return;
    await apply(
      target === "embedding" ? resetKbEmbedding : resetKbRerank,
      target === "embedding"
        ? "已恢复 embedding 部署默认"
        : "已恢复 rerank 部署默认",
    );
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

  if (!settings) {
    return (
      <section className={styles.section}>
        {error ? <Banner tone="error">{error}</Banner> : null}
        <p className={styles.hint}>设置暂时不可用。</p>
      </section>
    );
  }

  const embedding = settings.embedding;
  const rerank = settings.rerank;
  const rerankCanToggle =
    rerank.source !== "none" && !(rerank.source === "system" && !rerank.enabled);
  const dialogInitial =
    dialog === null
      ? null
      : {
          baseUrl:
            (dialog === "embedding" ? embedding.baseUrl : rerank.baseUrl) ?? "",
          apiKeyHint:
            dialog === "embedding" ? embedding.apiKeyHint : rerank.apiKeyHint,
          model: (dialog === "embedding" ? embedding.model : rerank.model) ?? "",
          dimension:
            dialog === "embedding" ? embedding.dimension : null,
          path:
            (dialog === "embedding" ? embedding.path : rerank.path) ??
            (dialog === "embedding" ? "/embeddings" : "/rerank"),
        };

  return (
    <section className={styles.section} aria-labelledby="kb-settings-title">
      <div className={styles.sectionHeader}>
        <h2 id="kb-settings-title">Embedding 与重排</h2>
        <Button
          variant="secondary"
          size="sm"
          type="button"
          disabled={testing || busy}
          onClick={() => void runTest()}
        >
          {testing ? "测试中…" : "测试检索"}
        </Button>
      </div>
      <p className={styles.hint}>
        先添加服务并选择模型；维度由探测自动得到。修改 embedding 模型或维度后需在文档列表「全部重建」。
      </p>

      {testResult ? (
        <div className={styles.testResult} role="status">
          <div>
            Embedding：{testResult.embeddingModel} · 维度 {testResult.dimension} ·{" "}
            {testResult.embeddingLatencyMs} ms
          </div>
          <div>
            Rerank：
            {testResult.reranked
              ? `${testResult.rerankModel ?? "已启用"} · ${testResult.rerankLatencyMs ?? "—"} ms`
              : testResult.rerankModel
                ? "已配置，但本次没有候选"
                : "未启用"}
          </div>
          <div>
            命中：{testResult.hits} 段（候选 {testResult.candidates}）
          </div>
        </div>
      ) : null}

      {error ? <Banner tone="error">{error}</Banner> : null}
      {notice ? <Banner tone="notice">{notice}</Banner> : null}

      <div className={styles.serviceList}>
        <article className={styles.serviceCard}>
          <div className={styles.serviceHeader}>
            <div className={styles.serviceHeading}>
              <h3>Embedding</h3>
              <span className={`${styles.sourceBadge} ${SOURCE_CLASS[embedding.source]}`}>
                {EMBEDDING_SOURCE_LABEL[embedding.source]}
              </span>
            </div>
            <div className={styles.serviceActions}>
              {embedding.source === "none" ? (
                <Button size="sm" type="button" onClick={() => setDialog("embedding")}>
                  添加服务
                </Button>
              ) : null}
              {embedding.source === "system" ? (
                <Button
                  variant="secondary"
                  size="sm"
                  type="button"
                  disabled={busy}
                  onClick={() => setDialog("embedding")}
                >
                  覆盖为我的配置
                </Button>
              ) : null}
              {embedding.source === "user" ? (
                <>
                  <Button
                    variant="secondary"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => setDialog("embedding")}
                  >
                    更换模型 / 编辑
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => setResetTarget("embedding")}
                  >
                    恢复部署默认
                  </Button>
                </>
              ) : null}
            </div>
          </div>

          {embedding.source === "none" ? (
            <p className={styles.hint}>
              还没有配置 embedding 服务。添加后才能上传文档和检索。
            </p>
          ) : (
            <dl className={styles.serviceMeta}>
              <div>
                <dt>服务</dt>
                <dd>{embedding.baseUrl}</dd>
              </div>
              <div>
                <dt>模型</dt>
                <dd>{embedding.model}</dd>
              </div>
              <div>
                <dt>维度</dt>
                <dd>{embedding.dimension ?? "—"}</dd>
              </div>
              <div>
                <dt>密钥</dt>
                <dd>
                  {embedding.apiKeyHint ??
                    (embedding.source === "system" ? "部署默认" : "未设置")}
                </dd>
              </div>
            </dl>
          )}
        </article>

        <article className={styles.serviceCard}>
          <div className={styles.serviceHeader}>
            <div className={styles.serviceHeading}>
              <h3>Rerank</h3>
              <span className={`${styles.sourceBadge} ${SOURCE_CLASS[rerank.source]}`}>
                {RERANK_SOURCE_LABEL[rerank.source]}
              </span>
            </div>
            <div className={styles.serviceActions}>
              <label className={styles.switchRow}>
                <input
                  type="checkbox"
                  checked={rerank.enabled}
                  disabled={busy || !rerankCanToggle}
                  onChange={(event) => void toggleRerank(event.target.checked)}
                />
                <span>启用重排</span>
              </label>
              {rerank.source === "none" ? (
                <Button size="sm" type="button" onClick={() => setDialog("rerank")}>
                  添加服务
                </Button>
              ) : null}
              {rerank.source === "system" ? (
                <Button
                  variant="secondary"
                  size="sm"
                  type="button"
                  disabled={busy}
                  onClick={() => setDialog("rerank")}
                >
                  覆盖为我的配置
                </Button>
              ) : null}
              {rerank.source === "user" || rerank.source === "disabled" ? (
                <>
                  <Button
                    variant="secondary"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => setDialog("rerank")}
                  >
                    编辑
                  </Button>
                  <Button
                    variant="ghost"
                    size="sm"
                    type="button"
                    disabled={busy}
                    onClick={() => setResetTarget("rerank")}
                  >
                    恢复部署默认
                  </Button>
                </>
              ) : null}
            </div>
          </div>

          {rerank.source === "disabled" ? (
            <p className={styles.hint}>
              已关闭；检索按向量分数返回。重新开启会恢复已存配置（或部署默认）。
            </p>
          ) : rerank.source === "none" ? (
            <p className={styles.hint}>
              未配置 rerank；检索仍可用，只是不做二次重排。
            </p>
          ) : (
            <dl className={styles.serviceMeta}>
              <div>
                <dt>服务</dt>
                <dd>{rerank.baseUrl}</dd>
              </div>
              <div>
                <dt>模型</dt>
                <dd>{rerank.model}</dd>
              </div>
              <div>
                <dt>密钥</dt>
                <dd>
                  {rerank.apiKeyHint ??
                    (rerank.source === "system" ? "部署默认" : "未设置")}
                </dd>
              </div>
            </dl>
          )}
        </article>
      </div>

      {dialog && dialogInitial ? (
        <ServiceDialog
          kind={dialog}
          initial={dialogInitial}
          onClose={() => setDialog(null)}
          onSaved={(saved) => {
            const changedEmbedding =
              dialog === "embedding" &&
              (settings.embedding.model !== saved.embedding.model ||
                settings.embedding.dimension !== saved.embedding.dimension);
            setSettings(saved);
            setDialog(null);
            setError(null);
            setNotice(
              changedEmbedding
                ? "设置已保存。embedding 变化会把现有文档标记为需重建，请在文档列表执行「全部重建」。"
                : "设置已保存。",
            );
          }}
        />
      ) : null}

      {resetTarget ? (
        <Dialog
          title={resetTarget === "embedding" ? "恢复 Embedding 部署默认" : "恢复 Rerank 部署默认"}
          description={
            resetTarget === "embedding"
              ? "将清除你的 embedding 服务、密钥与模型覆盖；现有文档会标记为需重建。部署未配置默认时会变为未配置。"
              : "将清除你的 rerank 服务、密钥与模型覆盖；检索仍可用。部署未配置默认时会变为未配置。"
          }
          closeOnOverlay={false}
          onClose={() => setResetTarget(null)}
          actions={
            <>
              <Button
                variant="secondary"
                type="button"
                disabled={busy}
                onClick={() => setResetTarget(null)}
              >
                取消
              </Button>
              <Button
                variant="danger"
                type="button"
                disabled={busy}
                onClick={() => void confirmReset()}
              >
                {busy ? "处理中…" : "确认恢复"}
              </Button>
            </>
          }
        >
          <p className={styles.hint}>
            只影响{" "}
            <strong>{resetTarget === "embedding" ? "Embedding" : "Rerank"}</strong>{" "}
            区块，另一个配置保持不变。
          </p>
        </Dialog>
      ) : null}
    </section>
  );
}

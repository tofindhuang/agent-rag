"use client";

import { useState } from "react";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import type { ModelCatalog, ModelProvider } from "@/types/models";
import { ProviderForm } from "./ProviderForm";
import styles from "./settings.module.css";

export function ModelSettings() {
  const {
    catalog,
    loading,
    error,
    notice,
    changeDefault,
    removeProvider,
    probe,
    saveProvider,
  } = useModelCatalog();
  const [editing, setEditing] = useState<ModelProvider | "new" | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<number | null>(null);
  const lockedModelId =
    catalog?.defaultModelId == null
      ? undefined
      : catalog.providers
          .flatMap((provider) => provider.models)
          .find((model) => model.id === catalog.defaultModelId)?.modelId;

  return (
    <section className={styles.section}>
      <div className={styles.sectionHeader}>
        <h2>模型供应商</h2>
        <button
          className={styles.primary}
          type="button"
          onClick={() => setEditing("new")}
          disabled={editing !== null}
        >
          添加供应商
        </button>
      </div>

      {loading ? (
        <p role="status" className={styles.hint}>
          加载中…
        </p>
      ) : null}
      {error ? (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      ) : null}
      {notice ? (
        <p role="status" className={styles.notice}>
          {notice}
        </p>
      ) : null}

      {catalog ? (
        <>
          <p className={styles.hint}>
            {catalog.defaultModelId != null
              ? `当前默认：${modelLabel(catalog, catalog.defaultModelId)}`
              : catalog.systemDefault.available
                ? "当前默认：系统默认模型"
                : "当前没有可用模型，请先添加供应商"}
          </p>
          {catalog.systemDefault.available ? (
            <button
              className={styles.ghost}
              type="button"
              onClick={() => void changeDefault(null)}
              disabled={catalog.defaultModelId == null}
            >
              使用系统默认模型
            </button>
          ) : null}

          {catalog.providers.length === 0 ? (
            <p className={styles.hint}>还没有供应商。添加后即可在新建会话时选择模型。</p>
          ) : (
            <ul className={styles.providerList}>
              {catalog.providers.map((provider) => (
                <li key={provider.id} className={styles.provider}>
                  <div className={styles.providerHeader}>
                    <div>
                      <strong>{provider.name}</strong>
                      <span className={styles.tag}>{provider.api}</span>
                      <div className={styles.hint}>{provider.baseUrl}</div>
                    </div>
                    <div className={styles.providerActions}>
                      <button
                        className={styles.ghost}
                        type="button"
                        onClick={() => setEditing(provider)}
                        disabled={editing !== null}
                      >
                        编辑
                      </button>
                      {confirmDelete === provider.id ? (
                        <>
                          <button
                            className={styles.danger}
                            type="button"
                            onClick={() => {
                              setConfirmDelete(null);
                              void removeProvider(provider.id);
                            }}
                          >
                            确认删除
                          </button>
                          <button
                            className={styles.ghost}
                            type="button"
                            onClick={() => setConfirmDelete(null)}
                          >
                            取消
                          </button>
                        </>
                      ) : (
                        <button
                          className={styles.dangerGhost}
                          type="button"
                          onClick={() => setConfirmDelete(provider.id)}
                        >
                          删除
                        </button>
                      )}
                    </div>
                  </div>
                  <div className={styles.hint}>API Key：{provider.apiKeyHint}</div>
                  <ul className={styles.modelList}>
                    {provider.models.map((model) => (
                      <li key={model.id} className={styles.modelItem}>
                        <span>{model.displayName}</span>
                        <span className={styles.hint}>{model.modelId}</span>
                        {catalog.defaultModelId === model.id ? (
                          <span className={styles.badge}>默认</span>
                        ) : (
                          <button
                            className={styles.ghost}
                            type="button"
                            onClick={() => void changeDefault(model.id)}
                          >
                            设为默认
                          </button>
                        )}
                      </li>
                    ))}
                  </ul>
                </li>
              ))}
            </ul>
          )}
        </>
      ) : null}

      {editing !== null ? (
        <ProviderForm
          initial={editing === "new" ? undefined : editing}
          lockedModelId={lockedModelId}
          probe={probe}
          save={saveProvider}
          onCancel={() => setEditing(null)}
          onSaved={() => setEditing(null)}
        />
      ) : null}
    </section>
  );
}

function modelLabel(catalog: ModelCatalog, modelId: number): string {
  for (const provider of catalog.providers) {
    const model = provider.models.find((candidate) => candidate.id === modelId);
    if (model) return `${provider.name} / ${model.displayName}`;
  }
  return `模型 #${modelId}`;
}

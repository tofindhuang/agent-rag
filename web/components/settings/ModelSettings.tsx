"use client";

import { useState } from "react";
import { userMessage } from "@/lib/api/errors";
import { createProvider, probeModels, updateProvider } from "@/lib/api/models";
import { useModelCatalog } from "@/hooks/useModelCatalog";
import type {
  ModelCatalog,
  ModelProvider,
  ProbeModel,
  ProviderApi,
  ProviderModelInput,
} from "@/types/models";
import styles from "./settings.module.css";

interface DraftModel {
  modelId: string;
  displayName: string;
  contextWindow: string;
  reasoning: boolean;
  vision: boolean;
  enabled: boolean;
}

function toDraft(provider?: ModelProvider): DraftModel[] {
  return (provider?.models ?? []).map((model) => ({
    modelId: model.modelId,
    displayName: model.displayName,
    contextWindow: model.contextWindow == null ? "" : String(model.contextWindow),
    reasoning: model.reasoning,
    vision: model.vision,
    enabled: true,
  }));
}

export function ModelSettings() {
  const { catalog, loading, error, notice, setNotice, reload, changeDefault, removeProvider } =
    useModelCatalog();
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
          onCancel={() => setEditing(null)}
          onSaved={async (message) => {
            setEditing(null);
            setNotice(message);
            await reload();
          }}
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

function ProviderForm({
  initial,
  lockedModelId,
  onCancel,
  onSaved,
}: {
  initial?: ModelProvider;
  lockedModelId?: string;
  onCancel: () => void;
  onSaved: (message: string) => Promise<void>;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [api, setApi] = useState<ProviderApi>(initial?.api ?? "openai-completions");
  const [baseUrl, setBaseUrl] = useState(initial?.baseUrl ?? "");
  const [apiKey, setApiKey] = useState("");
  const [drafts, setDrafts] = useState<DraftModel[]>(() => toDraft(initial));
  const [probes, setProbes] = useState<ProbeModel[]>([]);
  const [manualId, setManualId] = useState("");
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const probe = async () => {
    setError(null);
    setBusy(true);
    try {
      const key = apiKey.trim();
      const models = await probeModels({
        api,
        baseUrl,
        apiKey: key.length > 0 ? key : undefined,
        providerId: initial?.id,
      });
      setProbes(models);
      setDrafts((previous) => {
        const known = new Map(previous.map((draft) => [draft.modelId, draft]));
        const next = models.map(
          (model): DraftModel =>
            known.get(model.id) ?? {
              modelId: model.id,
              displayName: model.name || model.id,
              contextWindow: "",
              reasoning: false,
              vision: false,
              // On edit, newly discovered models stay disabled until chosen.
              enabled: initial === undefined,
            },
        );
        for (const draft of previous) {
          if (!next.some((candidate) => candidate.modelId === draft.modelId)) next.push(draft);
        }
        return next;
      });
      setMessage(`探测到 ${models.length} 个模型，勾选要启用的项`);
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  const addManual = () => {
    const modelId = manualId.trim();
    if (modelId.length === 0 || drafts.some((draft) => draft.modelId === modelId)) return;
    setDrafts((previous) => [
      ...previous,
      {
        modelId,
        displayName: modelId,
        contextWindow: "",
        reasoning: false,
        vision: false,
        enabled: true,
      },
    ]);
    setManualId("");
  };

  const updateDraft = (modelId: string, update: (draft: DraftModel) => DraftModel) => {
    setDrafts((previous) =>
      previous.map((draft) => (draft.modelId === modelId ? update(draft) : draft)),
    );
  };

  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);

    if (name.trim().length === 0 || baseUrl.trim().length === 0) {
      setError("请填写供应商名称与 Base URL");
      return;
    }
    const key = apiKey.trim();
    if (!initial && key.length === 0) {
      setError("请填写 API Key");
      return;
    }
    const selected = drafts.filter((draft) => draft.enabled && draft.modelId.length > 0);
    if (selected.length === 0) {
      setError("请至少启用一个模型（可手填模型 ID）");
      return;
    }

    const models: ProviderModelInput[] = selected.map((draft) => ({
      modelId: draft.modelId,
      displayName: draft.displayName.trim() || draft.modelId,
      contextWindow:
        draft.contextWindow.trim().length > 0 ? Number(draft.contextWindow) : undefined,
      reasoning: draft.reasoning,
      vision: draft.vision,
    }));
    if (
      models.some(
        (model) =>
          model.contextWindow != null &&
          (!Number.isInteger(model.contextWindow) || model.contextWindow < 1),
      )
    ) {
      setError("上下文窗口必须是正整数");
      return;
    }

    setBusy(true);
    try {
      if (initial) {
        await updateProvider(initial.id, {
          name: name.trim(),
          api,
          baseUrl: baseUrl.trim(),
          apiKey: key.length > 0 ? key : undefined,
          models,
        });
        await onSaved("供应商已更新");
      } else {
        await createProvider({
          name: name.trim(),
          api,
          baseUrl: baseUrl.trim(),
          apiKey: key,
          models,
        });
        await onSaved("供应商已添加，API Key 只显示末四位");
      }
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className={styles.form} onSubmit={(event) => void save(event)}>
      <h3>{initial ? "编辑供应商" : "添加供应商"}</h3>

      <label className={styles.label}>
        名称
        <input
          className={styles.input}
          value={name}
          onChange={(event) => setName(event.target.value)}
          maxLength={100}
          required
        />
      </label>
      <label className={styles.label}>
        API 类型
        <select
          className={styles.input}
          value={api}
          onChange={(event) => setApi(event.target.value as ProviderApi)}
        >
          <option value="openai-completions">OpenAI-compatible</option>
          <option value="anthropic-messages">Anthropic Messages</option>
        </select>
      </label>
      <label className={styles.label}>
        Base URL
        <input
          className={styles.input}
          value={baseUrl}
          onChange={(event) => setBaseUrl(event.target.value)}
          placeholder="https://api.example.com/v1"
          maxLength={1000}
          required
        />
      </label>
      <label className={styles.label}>
        API Key
        <input
          className={styles.input}
          type="password"
          autoComplete="new-password"
          value={apiKey}
          onChange={(event) => setApiKey(event.target.value)}
          placeholder={initial ? `留空保持原 key（${initial.apiKeyHint}）` : ""}
          maxLength={4096}
        />
      </label>

      <div className={styles.row}>
        <button
          className={styles.ghost}
          type="button"
          onClick={() => void probe()}
          disabled={busy}
        >
          探测模型
        </button>
        {probes.length > 0 ? (
          <span className={styles.hint}>探测到 {probes.length} 个模型</span>
        ) : null}
      </div>

      <div className={styles.row}>
        <input
          className={styles.input}
          value={manualId}
          onChange={(event) => setManualId(event.target.value)}
          placeholder="手填模型 ID"
          aria-label="手填模型 ID"
          maxLength={200}
        />
        <button className={styles.ghost} type="button" onClick={addManual}>
          添加
        </button>
      </div>

      {drafts.length > 0 ? (
        <ul className={styles.draftList}>
          {drafts.map((draft) => (
            <li key={draft.modelId} className={styles.draft}>
              <label className={styles.checkbox}>
                <input
                  type="checkbox"
                  checked={draft.enabled}
                  disabled={draft.modelId === lockedModelId}
                  title={draft.modelId === lockedModelId ? "默认模型不可停用" : undefined}
                  onChange={(event) =>
                    updateDraft(draft.modelId, (current) => ({
                      ...current,
                      enabled: event.target.checked,
                    }))
                  }
                />
                <span>{draft.modelId}</span>
              </label>
              <input
                className={styles.input}
                value={draft.displayName}
                aria-label={`${draft.modelId} 显示名称`}
                onChange={(event) =>
                  updateDraft(draft.modelId, (current) => ({
                    ...current,
                    displayName: event.target.value,
                  }))
                }
              />
              <details className={styles.advanced}>
                <summary className={styles.hint}>高级元数据</summary>
                <div className={styles.draft}>
                  <input
                    className={styles.contextInput}
                    value={draft.contextWindow}
                    inputMode="numeric"
                    aria-label={`${draft.modelId} 上下文窗口`}
                    placeholder="上下文窗口（可选）"
                    onChange={(event) =>
                      updateDraft(draft.modelId, (current) => ({
                        ...current,
                        contextWindow: event.target.value,
                      }))
                    }
                  />
                  <label className={styles.checkbox}>
                    <input
                      type="checkbox"
                      checked={draft.reasoning}
                      onChange={(event) =>
                        updateDraft(draft.modelId, (current) => ({
                          ...current,
                          reasoning: event.target.checked,
                        }))
                      }
                    />
                    reasoning
                  </label>
                  <label className={styles.checkbox}>
                    <input
                      type="checkbox"
                      checked={draft.vision}
                      onChange={(event) =>
                        updateDraft(draft.modelId, (current) => ({
                          ...current,
                          vision: event.target.checked,
                        }))
                      }
                    />
                    vision
                  </label>
                </div>
              </details>
            </li>
          ))}
        </ul>
      ) : null}

      {message ? (
        <p role="status" className={styles.notice}>
          {message}
        </p>
      ) : null}
      {error ? (
        <p role="alert" className={styles.error}>
          {error}
        </p>
      ) : null}

      <div className={styles.row}>
        <button className={styles.primary} type="submit" disabled={busy}>
          {initial ? "保存修改" : "保存供应商"}
        </button>
        <button className={styles.ghost} type="button" onClick={onCancel} disabled={busy}>
          取消
        </button>
      </div>
    </form>
  );
}

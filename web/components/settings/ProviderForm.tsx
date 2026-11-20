"use client";

import { useState } from "react";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Select } from "@/components/ui/Select";
import { TextField } from "@/components/ui/TextField";
import { userMessage } from "@/lib/api/errors";
import type {
  ModelProvider,
  ProbeModel,
  ProbeRequest,
  ProviderApi,
  ProviderInput,
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

export function ProviderForm({
  initial,
  lockedModelId,
  probe: probeRequest,
  save,
  onCancel,
  onSaved,
}: {
  initial?: ModelProvider;
  lockedModelId?: string;
  probe: (request: ProbeRequest) => Promise<ProbeModel[]>;
  save: (id: number | null, input: ProviderInput) => Promise<void>;
  onCancel: () => void;
  onSaved: () => void;
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
      const models = await probeRequest({
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

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
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
      await save(initial?.id ?? null, {
        name: name.trim(),
        api,
        baseUrl: baseUrl.trim(),
        apiKey: key.length > 0 ? key : undefined,
        models,
      });
      onSaved();
    } catch (cause: unknown) {
      console.error(cause);
      setError(userMessage(cause));
    } finally {
      setBusy(false);
    }
  };

  return (
    <form className={styles.form} onSubmit={(event) => void submit(event)}>
      <h3>{initial ? "编辑供应商" : "添加供应商"}</h3>

      <TextField
        label="名称"
        value={name}
        onChange={(event) => setName(event.target.value)}
        maxLength={100}
        required
      />
      <Select
        label="API 类型"
        value={api}
        onChange={(event) => setApi(event.target.value as ProviderApi)}
      >
        <option value="openai-completions">OpenAI-compatible</option>
        <option value="anthropic-messages">Anthropic Messages</option>
      </Select>
      <TextField
        label="Base URL"
        value={baseUrl}
        onChange={(event) => setBaseUrl(event.target.value)}
        placeholder="https://api.example.com/v1"
        maxLength={1000}
        required
      />
      <TextField
        label="API Key"
        type="password"
        autoComplete="new-password"
        value={apiKey}
        onChange={(event) => setApiKey(event.target.value)}
        placeholder={initial ? `留空保持原 key（${initial.apiKeyHint}）` : ""}
        maxLength={4096}
      />

      <div className={styles.row}>
        <Button variant="secondary" onClick={() => void probe()} disabled={busy}>
          探测模型
        </Button>
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
        <Button variant="secondary" onClick={addManual}>
          添加
        </Button>
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

      {message ? <Banner tone="notice">{message}</Banner> : null}
      {error ? <Banner tone="error">{error}</Banner> : null}

      <div className={styles.row}>
        <Button type="submit" disabled={busy}>
          {initial ? "保存修改" : "保存供应商"}
        </Button>
        <Button variant="secondary" onClick={onCancel} disabled={busy}>
          取消
        </Button>
      </div>
    </form>
  );
}

"use client";

import type { ModelCatalog } from "@/types/models";

/** Select value encoding: "system" for the system default, else the model id. */
export const SYSTEM_MODEL_VALUE = "system";

/** Initial picker value: the user's default model, else the system default. */
export function defaultModelValue(catalog: ModelCatalog): string {
  if (catalog.defaultModelId != null) return String(catalog.defaultModelId);
  if (catalog.systemDefault.available) return SYSTEM_MODEL_VALUE;
  const first = catalog.providers.flatMap((provider) => provider.models)[0];
  return first ? String(first.id) : "";
}

/** Options for the shared Select primitive; grouped by provider. */
export function ModelOptions({ catalog }: { catalog: ModelCatalog }) {
  return (
    <>
      {catalog.systemDefault.available ? (
        <option value={SYSTEM_MODEL_VALUE}>
          {catalog.systemDefault.label
            ? `系统默认 · ${catalog.systemDefault.label}`
            : "系统默认"}
        </option>
      ) : null}
      {catalog.providers.map((provider) => (
        <optgroup key={provider.id} label={provider.name}>
          {provider.models.map((model) => (
            <option key={model.id} value={String(model.id)}>
              {model.displayName}
            </option>
          ))}
        </optgroup>
      ))}
    </>
  );
}

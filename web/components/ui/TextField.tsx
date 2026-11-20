"use client";

import { useId } from "react";
import styles from "./ui.module.css";

interface TextFieldProps extends React.InputHTMLAttributes<HTMLInputElement> {
  label: string;
  error?: string | null;
  hint?: string;
}

export function TextField({ label, error, hint, id, className, ...rest }: TextFieldProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const describedBy = [error ? `${fieldId}-error` : null, hint ? `${fieldId}-hint` : null]
    .filter(Boolean)
    .join(" ");

  return (
    <label className={[styles.field, className].filter(Boolean).join(" ")} htmlFor={fieldId}>
      <span>{label}</span>
      <input
        id={fieldId}
        className={styles.input}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy.length > 0 ? describedBy : undefined}
        {...rest}
      />
      {hint ? (
        <span id={`${fieldId}-hint`} className={styles.hint}>
          {hint}
        </span>
      ) : null}
      {error ? (
        <span id={`${fieldId}-error`} className={styles.error} role="alert">
          {error}
        </span>
      ) : null}
    </label>
  );
}

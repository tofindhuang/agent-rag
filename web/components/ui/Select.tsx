"use client";

import { useId } from "react";
import styles from "./ui.module.css";

interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {
  label: string;
  hint?: string;
}

export function Select({ label, hint, id, className, children, ...rest }: SelectProps) {
  const generatedId = useId();
  const fieldId = id ?? generatedId;
  const hintId = hint ? `${fieldId}-hint` : undefined;

  return (
    <label className={[styles.field, className].filter(Boolean).join(" ")} htmlFor={fieldId}>
      <span>{label}</span>
      <select
        id={fieldId}
        className={styles.input}
        aria-describedby={hintId}
        {...rest}
      >
        {children}
      </select>
      {hint ? (
        <span id={hintId} className={styles.hint}>
          {hint}
        </span>
      ) : null}
    </label>
  );
}

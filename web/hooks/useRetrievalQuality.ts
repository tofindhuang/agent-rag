"use client";

import { useCallback, useEffect, useState } from "react";
import {
  createKbEvalLabel,
  deleteKbEvalLabel,
  fetchKbEvalLabels,
  fetchKbEvalRun,
  fetchKbEvalRuns,
  knowledgeError,
  runKbEval,
} from "@/lib/api/knowledge";
import type {
  KbEvalLabel,
  KbEvalLabelList,
  KbEvalRun,
  KbEvalRunSummary,
} from "@/types/knowledge";

export interface NewLabelInput {
  query: string;
  documentId: string;
  seq: number;
}

/**
 * Labels, runs and history for one KB. Kept out of the panel so the debug
 * query state and the evaluation state can evolve independently.
 */
export function useRetrievalQuality(kbId: string | null) {
  const [labels, setLabels] = useState<KbEvalLabelList | null>(null);
  const [labelBusy, setLabelBusy] = useState(false);
  const [labelError, setLabelError] = useState<string | null>(null);
  const [runs, setRuns] = useState<KbEvalRunSummary[]>([]);
  const [evalBusy, setEvalBusy] = useState(false);
  const [evalError, setEvalError] = useState<string | null>(null);
  const [lastRun, setLastRun] = useState<KbEvalRun | null>(null);
  const [expandedRunId, setExpandedRunId] = useState<number | null>(null);
  const [runDetails, setRunDetails] = useState<Record<number, KbEvalRun>>({});
  const [detailBusy, setDetailBusy] = useState(false);

  const refreshLabels = useCallback(async () => {
    if (!kbId) {
      setLabels(null);
      return;
    }
    setLabels(await fetchKbEvalLabels(kbId));
  }, [kbId]);

  const refreshRuns = useCallback(async () => {
    if (!kbId) {
      setRuns([]);
      return;
    }
    setRuns((await fetchKbEvalRuns(kbId)).items);
  }, [kbId]);

  useEffect(() => {
    let cancelled = false;
    setLabelError(null);
    setEvalError(null);
    setLastRun(null);
    setExpandedRunId(null);
    setRunDetails({});
    if (!kbId) {
      setLabels(null);
      setRuns([]);
      return () => {
        cancelled = true;
      };
    }
    fetchKbEvalLabels(kbId)
      .then((loaded) => {
        if (!cancelled) setLabels(loaded);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setLabelError(knowledgeError(cause));
      });
    fetchKbEvalRuns(kbId)
      .then((list) => {
        if (!cancelled) setRuns(list.items);
      })
      .catch((cause: unknown) => {
        if (!cancelled) setEvalError(knowledgeError(cause));
      });
    return () => {
      cancelled = true;
    };
  }, [kbId]);

  const createLabel = useCallback(
    async (input: NewLabelInput) => {
      if (!kbId) return;
      setLabelBusy(true);
      setLabelError(null);
      try {
        await createKbEvalLabel({ kbId, ...input });
        await refreshLabels();
      } catch (cause: unknown) {
        console.error(cause);
        setLabelError(knowledgeError(cause));
      } finally {
        setLabelBusy(false);
      }
    },
    [kbId, refreshLabels],
  );

  const removeLabel = useCallback(
    async (label: KbEvalLabel) => {
      setLabelBusy(true);
      setLabelError(null);
      try {
        await deleteKbEvalLabel(label.id);
        await refreshLabels();
      } catch (cause: unknown) {
        console.error(cause);
        setLabelError(knowledgeError(cause));
      } finally {
        setLabelBusy(false);
      }
    },
    [refreshLabels],
  );

  const startEvaluation = useCallback(async () => {
    if (!kbId) return;
    setEvalBusy(true);
    setEvalError(null);
    try {
      const report = await runKbEval(kbId);
      setLastRun(report);
      setRunDetails((previous) => ({ ...previous, [report.runId]: report }));
      setExpandedRunId(report.runId);
      await refreshRuns();
      await refreshLabels();
    } catch (cause: unknown) {
      console.error(cause);
      setEvalError(knowledgeError(cause));
    } finally {
      setEvalBusy(false);
    }
  }, [kbId, refreshLabels, refreshRuns]);

  const ensureDetail = useCallback(
    async (runId: number) => {
      if (runDetails[runId]) return;
      setDetailBusy(true);
      try {
        const detail = await fetchKbEvalRun(runId);
        setRunDetails((previous) => ({ ...previous, [runId]: detail }));
      } catch (cause: unknown) {
        console.error(cause);
        setEvalError(knowledgeError(cause));
      } finally {
        setDetailBusy(false);
      }
    },
    [runDetails],
  );

  const toggleRun = useCallback(
    async (run: KbEvalRunSummary) => {
      if (expandedRunId === run.runId) {
        setExpandedRunId(null);
        return;
      }
      setExpandedRunId(run.runId);
      await ensureDetail(run.runId);
      const baseline = previousCompleteRun(runs, run.runId);
      if (baseline) {
        await ensureDetail(baseline.runId);
      }
    },
    [ensureDetail, expandedRunId, runs],
  );

  const validLabels = labels?.items.filter((label) => label.state === "ok").length ?? 0;

  return {
    labels,
    labelBusy,
    labelError,
    runs,
    evalBusy,
    evalError,
    lastRun,
    expandedRunId,
    runDetails,
    detailBusy,
    validLabels,
    createLabel,
    removeLabel,
    startEvaluation,
    toggleRun,
  };
}

/** The newest complete run older than {@code runId}, or null. */
export function previousCompleteRun(
  runs: KbEvalRunSummary[],
  runId: number,
): KbEvalRunSummary | null {
  const index = runs.findIndex((run) => run.runId === runId);
  if (index < 0) return null;
  for (let i = index + 1; i < runs.length; i++) {
    if (runs[i].complete) return runs[i];
  }
  return null;
}

export type RetrievalQuality = ReturnType<typeof useRetrievalQuality>;

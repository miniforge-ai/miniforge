(ns ai.miniforge.policy-calibration.interface
  "Gate-readiness calibration: measure whether a semantic policy rule is reliable
   enough to be a hard gate — zero clean false-positives + adequate recall over a
   seeded corpus, across independent runs. The judge is injected (the bb task
   wires the real LLM judge; tests pass deterministic cells)."
  (:require [ai.miniforge.policy-calibration.core :as core]
            [ai.miniforge.policy-calibration.judge :as judge]
            [ai.miniforge.policy-calibration.runner :as runner]
            [ai.miniforge.policy-calibration.gate-check :as gate-check]))

;; ---- judge cell constructors / accessors (one judge call on one fixture) ----
(def cell-success
  "Successful cell: (cell-success fired-rule-ids {rule-id message}). The set of
   rules the judge flagged, plus per-rule reasoning for iteration feedback."
  core/cell-success)
(def backend-error
  "Cell for a judge backend failure (:unavailable anomaly). (backend-error msg data)."
  core/backend-error)
(def format-error
  "Cell for unparseable judge output (:invalid-input anomaly). (format-error msg data)."
  core/format-error)
(def cell-ok?   "True when the judge successfully evaluated the cell." core/cell-ok?)
(def cell-fired "The set of rule-ids a successful cell flagged."        core/cell-fired)
(def cell-why   "The {rule-id message} map of a successful cell."       core/cell-why)

;; ---- scoring + aggregation ----
(def score-rule
  "Score one rule against a single run's cells; each fixture's verdict is the
   majority vote across its OK trials.
   (score-rule rule fixtures trials gate-bar cell-at) -> per-run score map."
  core/score-rule)
(def aggregate
  "Combine per-run scores into a consensus verdict.
   (aggregate per-run runs trials) -> verdict map. gate-ready? iff a strict
   majority of the EVALUATED runs pass AND a strict majority of the configured
   runs actually evaluated (crashed/no-cell runs are excluded, not counted as
   failures); otherwise :inconclusive?. :stable? reports unanimity among the
   evaluated runs."
  core/aggregate)

;; ---- top-level ----
(def calibrate
  "Run the calibration and return the aggregated per-rule gate-ready record.
   opts: {:rules :fixtures :judge-fn :runs :trials :max-parallel :gate-bar}.
   :judge-fn is (fn [rules fixture] -> cell)."
  core/calibrate)

(def make-judge
  "Build the live LLM calibration judge (fn [rules fixture] -> cell).
   (make-judge llm-client complete-fn)."
  judge/make-judge)

(def run-calibration!
  "Run the full calibration from config (load rules + corpus, build the LLM
   judge, calibrate, write the record). Returns the record. This is the body of
   the `policy:calibrate` bb task."
  runner/run-calibration!)

;; ---- build-time gate-readiness check ----
(def gate-requires-calibration?
  "True iff a rule gates via the semantic judge (acting action + semantic detector)."
  gate-check/requires-calibration?)
(def gate-check
  "(gate-check rules record) -> {:ok? bool :ungated [rule-id ...]} — acting
   semantic rules (:hard-halt or :require-approval) lacking a passing
   calibration record."
  gate-check/check)
(def gate-check-shipped
  "Load the shipped packs + committed calibration record and run gate-check.
   Body of the build-time gate-readiness check."
  gate-check/check-shipped)

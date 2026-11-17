;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.phase-software-factory.implement-result
  "Classification of the implementer agent's result for `leave-implement`.

   Infra signals (rate limit, network drop, backend timeout, a suspiciously
   short run that wrote no files), unverified `:already-implemented`
   claims, the structured phase error, and the lightweight curated
   artifact, consultation and metrics kept in phase results. Split out of
   `implement` (rule 210 layer budget)."
  (:require
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.implement-context :as impl-context]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private rate-limit-pattern
  "Lightweight rate limit / infra-transient detection for the phase level.
   Avoids a dependency on workflow/dag-resilience.

   Widened 2026-04-17 after a dogfood failure where the LLM backend
   returned near-empty content in ~4s (quota-exhausted-adjacent infra
   failure) that the prior narrow regex missed. Added: HTTP 503, 'too
   many requests', 'overloaded', 'try again', 'capacity', 'throttled',
   and a broader apostrophe class for 'you've'."
  #"(?i)you['\u2019]ve hit your (usage |)limit|rate.?limit|429|503|quota.?exceeded|resets? \d+[ap]m|too many requests|overloaded|try again (?:later|in)|at capacity|throttled")

(def ^{:stratum 0} ^:private suspicious-short-duration-ms
  "Threshold below which an implement phase is too fast to have done real
   work. A legitimate implementer LLM call takes minutes; anything under
   30s that ALSO failed to produce files is almost always an infra
   failure (auth, quota, transient backend error) rather than a real
   task failure. Routing these through the rate-limit branch lets the
   workflow pause and resume rather than terminating permanently."
  30000)

(defn ^{:stratum 0} network-drop-in-result?
  "True when the agent result carries a network-drop signal from
   PR-B's `network-monitor`. Three locations may carry it:

   1. `[:error :data :timeout :type]` — the canonical agent result
      shape. `streaming-error-response` builds an llm-error map with
      `:timeout` on the `:error`; `result-boundary/error-response`
      then merges that map into `[:error :data]` via `response/error`.
      This is the path the implement phase actually sees from a
      production network-drop.
   2. `[:timeout :type]` — back-compat for raw exec-result maps that
      bypass the result-boundary wrapping (test harnesses, direct
      stream-exec-fn captures).
   3. `[:error :message]` text containing the formatted
      `Adaptive timeout: ... (type: network-drop, ...)` produced by
      `format-timeout-error` — defensive fallback for paths that
      surface the timeout via the error channel as a plain string.

   The message-channel regex anchors on the explicit `type:
   network-drop` marker emitted by `format-timeout-error` so an
   unrelated message that happens to mention 'network drop' in prose
   does not trip the predicate.

   Distinct from `rate-limit-in-result?` — the network monitor detects
   raw TCP/TLS reachability loss (no provider response at all), while
   rate-limit detection keys off provider-emitted 429/quota messages.
   The two anomalies want different recovery strategies: rate-limit
   pauses + waits; network-drop retries from the last persisted
   checkpoint (PR-C of the network-resilience stack)."
  [result]
  (or (= :network-drop (get-in result [:error :data :timeout :type]))
      (= :network-drop (get-in result [:timeout :type]))
      (let [msg (get-in result [:error :message])]
        (and (string? msg)
             (re-find #"(?i)type:\s*network-drop" msg)))))

(defn ^{:stratum 0} backend-timeout-in-result?
  "True when the agent result carries an adaptive-timeout signal that
   is NOT `:network-drop` — i.e. the LLM call hit a stream-idle,
   stagnation, or hard-limit timeout. Mirrors
   `network-drop-in-result?`'s three-location path priority so the
   two timeout families stay symmetrically shaped (matching the
   2026-06-06 phase-timeout PR-A primitives in result-boundary).

   The 2026-06-05 dogfood (`adhoc-944448986`) revealed the gap: the
   reviewer LLM stream-idle'd at 360s and the workflow had no
   per-phase verdict to distinguish that infra timeout from a real
   defect. PR-A/B fixed the review-phase side; this is the implement-
   phase parity.

   Distinct from `network-drop-in-result?` — the network monitor
   detects TCP/TLS reachability loss; this catches the cases where
   the provider stayed connected but stopped producing output (or
   ran past a hard wall-clock cutoff). The two arms never both fire
   on the same result because the `:type` is mutually exclusive.

   Distinct from `rate-limit-in-result?` — that keys off provider-
   emitted 429/quota messages; this keys off the LLM client's own
   adaptive-timeout taxonomy."
  [result]
  (let [timeout-types #{:stream-idle :stagnation :hard-limit}
        boundary-type (or (get-in result [:error :data :timeout :type])
                          (get-in result [:timeout :type]))]
    (or (contains? timeout-types boundary-type)
        (let [msg (get-in result [:error :message])]
          (and (string? msg)
               (re-find #"(?i)type:\s*(stream-idle|stagnation|hard-limit)" msg))))))

(defn ^{:stratum 0} extract-error-message
  "Extract the most relevant error message from an agent result."
  [result default-msg]
  (or (not-empty (get-in result [:error :message]))
      (not-empty (get-in result [:output :error]))
      default-msg))

(defn- ^{:stratum 0} prior-verify-failure?
  "Return true when the previous verify phase recorded a failure."
  [ctx]
  (let [verify-phase (get-in ctx [:execution/phase-results :verify])]
    (contains? #{:failed :failure :error}
               (or (:status verify-phase)
                   (get-in verify-phase [:result :status])))))

(defn ^{:stratum 0} successful-curated-artifact
  "Extract the curated artifact from a successful implement phase result."
  [result]
  (or (:artifact result)
      (:output result)))

(defn ^{:stratum 0} with-implementer-spend
  "Put the implementer turn's `:tokens` and `:cost-usd` back on `result`'s
   metrics. The curator spends no implementer tokens, but its response
   builder fills `:tokens 0`; wherever its result or metrics stand in for
   the implementer's, the turn's measured spend must survive, on success
   and on failure alike."
  [result impl-result]
  (let [spend (select-keys (:metrics impl-result) [:tokens :cost-usd])]
    (cond-> result
      (seq spend) (update :metrics merge spend))))

(defn ^{:stratum 0} raw-agent-output
  "The agent's scalar output text, whether it still sits at :output or
   was preserved under [:output :agent/raw-output] when
   codex-pin/attach-consultation normalized a scalar into a map. Every
   classifier that reads the raw text (rate-limit detection, error
   message, summary fallback) must go through this accessor or it goes
   blind after normalization."
  [result]
  (let [out (:output result)]
    (cond
      (string? out) out
      (map? out) (let [raw (:agent/raw-output out)]
                   (when (string? raw) raw)))))

(defn ^{:stratum 0} lightweight-curated-artifact
  "Persist only lightweight curated metadata in phase results.
   Serialized code stays in the worktree and can be rehydrated later."
  [artifact]
  (when artifact
    (let [files (:code/files artifact)]
      (cond-> (dissoc artifact :code/files)
        (seq files)
        (assoc :code/file-paths (mapv :path files)
               :code/file-actions (mapv :action files)
               :code/file-count (count files))))))

(defn ^{:stratum 0} preserve-consultation
  "Carry :codex/consultation from the full phase result onto the
   lightweight success result. The gap instrument (T2 s3.2) and the gap
   report read consultation provenance from the durable phase record;
   replacing the result wholesale at leave destroyed it, leaving
   successful DAG-task implements with no evidence delivery happened."
  [lightweight result]
  (if-let [consultation (get-in result [:output :codex/consultation])]
    (assoc-in lightweight [:output :codex/consultation] consultation)
    lightweight))

(defn ^{:stratum 0} ensure-valid-metrics
  "Phase-input boundary validation for the agent result's `:metrics`. The
   response builders guarantee non-nil numeric `:tokens`/`:duration-ms`, but a
   producer that bypasses them (or a future regression) could send a nil that
   then throws a message-less NPE in `implement`'s cost/accumulation arithmetic.
   Validate against `schema/Metrics`; on violation, log loudly and coerce to a
   valid shape (telemetry must not crash the phase) rather than fail silently."
  [metrics logger]
  (if (schema/valid? schema/Metrics metrics)
    metrics
    ;; Resilient to ANY invalid shape — a non-map :metrics (string/vector) must
    ;; coerce too, not throw before we can log/recover.
    (let [base    (if (map? metrics) metrics {})
          coerced (-> base
                      (update :tokens #(if (nat-int? %) % 0))
                      (update :duration-ms #(if (nat-int? %) % 0)))]
      (when logger
        (log/warn logger :implement :metrics/boundary-invalid
                  {:data {:received metrics :coerced coerced}}))
      coerced)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} suspicious-short-termination?
  "Heuristic: implement phase completed in <30s AND produced no files.
   Observed 2026-04-17 dogfood pattern — LLM stream returned immediately
   with empty content; agent-status :error but no rate-limit keywords in
   the error message. Almost certainly an infra failure that masqueraded
   as a task failure."
  [result]
  (let [duration (get-in result [:metrics :duration-ms] 0)
        error-code (get-in result [:error :data :code])
        no-files? (= :curator/no-files-written error-code)]
    (and no-files?
         (pos? duration)
         (< duration suspicious-short-duration-ms))))

(defn ^{:stratum 1} unverified-already-implemented?
  "Return true when implement claims :already-implemented while responding to
   known review or verify failures."
  [ctx result]
  (and (= :already-implemented (:status result))
       (or (impl-context/resolve-review-feedback ctx)
           (prior-verify-failure? ctx))))

(defn ^{:stratum 1} build-unverified-already-implemented-error
  "Build a structured error for an unsupported :already-implemented repair outcome."
  [ctx result iterations]
  {:message (messages/t :implement/unverified-already-implemented)
   :agent-status (:status result)
   :rate-limited? false
   :iterations iterations
   :review-feedback? (boolean (impl-context/resolve-review-feedback ctx))
   :verify-failure? (prior-verify-failure? ctx)})

(defn ^{:stratum 1} build-phase-error
  "Build a structured phase error map from an agent result."
  [result agent-status rate-limited? iterations]
  {:message (or (extract-error-message result nil)
                (not-empty (raw-agent-output result))
                (messages/t :implement/exhausted-retries))
   :agent-status agent-status
   :rate-limited? (boolean rate-limited?)
   :iterations iterations})

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} rate-limit-in-result?
  "Check if an agent result indicates an infrastructure failure that
   should pause/resume rather than terminate. Two signals:
     1. Known rate-limit / quota / transient-backend keywords in error text.
     2. Suspiciously short termination with no files produced (observed
        symptom of LLM-backend failure that returned empty content fast)."
  [result]
  (or (some (fn [text]
              (and (string? text) (re-find rate-limit-pattern text)))
            [(get-in result [:error :message])
             (raw-agent-output result)])
      (suspicious-short-termination? result)))

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

(ns ai.miniforge.gate.policy-pack
  "Phase-scoped policy-pack gate.

   Closes PR #979's enforcement gap: the only pack-derived gate (`:behavioral`)
   was wired solely into the `:observe` phase, so a normal canonical-sdlc run
   NEVER evaluated the standards pack on the path a PR actually takes. This
   gate runs the shipped standards pack's enabled rules — those whose
   `:applies-to {:phases}` includes the current phase — against the phase
   artifact, and BLOCKS on `:hard-halt` enforcement (a failed gate routes to
   the existing repair/redirect path via `apply-gate-validation`).

   Registered as two phase-baked gates, `:policy-verify` and `:policy-review`,
   so the generic `apply-gate-validation` path runs the right rule subset per
   phase. The whole evaluation delegates to
   `policy-pack/check-artifact`, which already does phase filtering,
   detection, and severity/enforcement classification — this gate adds no
   parallel enforcement channel (N4 reuse constraint).

   Pack source: `ctx :policy-packs` when provided (tests / repo packs), else
   the classpath-shipped `packs/miniforge-standards.pack.edn`.

   Semantic seam: when the run carries an `:llm-client` and `:complete-fn` in
   ctx, this gate INJECTS `semantic-analyzer/analyze-rule` under
   `:semantic-analyze-fn` so heuristic (`:custom` rules with no resolvable
   `:custom-fn`) reach the LLM judge. Absent that wiring such rules no-op
   gracefully (see `policy-pack.detection/detect-semantic`) — they default to
   non-blocking enforcement, so a run is never hard-blocked on a
   non-deterministic judge by default.

   Fail-safe: a check that throws is converted to a failed gate by
   `gate.interface/check-gate` (mirrors the reviewer fix in #977) — never a
   silent pass."
  (:require
   [ai.miniforge.event-stream.interface :as event-stream]
   [ai.miniforge.gate.messages :as msg]
   [ai.miniforge.gate.registry :as registry]
   [ai.miniforge.policy-pack.interface :as policy-pack]
   [ai.miniforge.semantic-analyzer.interface :as semantic]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0
;; Pack source

(def ^:private standards-pack-resource
  "Classpath location of the compiled standards pack (emitted by
   `bb standards:pack`, placed on the classpath at build time)."
  "packs/miniforge-standards.pack.edn")

(defn- read-standards-pack
  "Read the shipped standards pack manifest from the classpath.

   Returns nil ONLY when the resource is absent (a repo legitimately without a
   compiled pack). A present-but-malformed/unreadable pack THROWS — so
   `gate.interface/check-gate` converts it into a failed gate (fail-closed),
   rather than letting a corrupt pack masquerade as 'no pack' and silently
   skip enforcement."
  []
  (when-let [res (io/resource standards-pack-resource)]
    (edn/read-string (slurp res))))

(def ^:private standards-pack
  "Memoized shipped pack — read once per JVM (the classpath is stable for a
   process). A read failure is cached as a thrown deref, keeping enforcement
   fail-closed on every evaluation."
  (delay (read-standards-pack)))

(defn- packs-for-gate
  "Packs to evaluate. When ctx carries an explicit `:policy-packs` key, that
   value is authoritative — even an empty vector (a run that disabled all
   packs is respected, not silently re-seeded). When the key is absent (the
   normal SDLC path), the shipped standards pack is used so the gate actually
   evaluates. Empty when no pack resource is present."
  [ctx]
  (if (contains? ctx :policy-packs)
    (vec (:policy-packs ctx))
    (if-let [pack @standards-pack] [pack] [])))

;------------------------------------------------------------------------------ Layer 1
;; Context wiring

(defn- with-semantic-wiring
  "Inject the LLM-judge seam when the run carries an `:llm-client` and
   `:complete-fn` but no explicit `:semantic-analyze-fn`. Also normalizes the
   repo path the judge scans (`:repo-path`, falling back to the execution
   worktree). When the LLM seam is absent, leaves ctx untouched — semantic
   rules then no-op in detection."
  [ctx]
  (let [ctx (assoc ctx :repo-path (or (:repo-path ctx)
                                      (:execution/worktree-path ctx)
                                      "."))]
    (if (and (:llm-client ctx)
             (:complete-fn ctx)
             (not (:semantic-analyze-fn ctx)))
      (assoc ctx :semantic-analyze-fn semantic/analyze-rule)
      ctx)))

(defn- no-policy-packs-warning
  []
  {:type :no-policy-packs
   :message (msg/t :policy-pack/no-policy-packs)})

;------------------------------------------------------------------------------ Layer 1.5
;; Per-rule application evidence

(defn- violation-summary
  "Non-sensitive summary of a violation for an evidence event. Drops :matches
   — which can carry source lines or secret material (e.g. a no-secrets rule's
   matched token) and must never reach a stored event. Keeps the message, the
   artifact path, and a match count."
  [violation]
  (when violation
    {:message       (:message violation)
     :artifact-path (:artifact-path violation)
     :match-count   (count (:matches violation))}))

(defn- classify-rules
  "Per-rule evidence: classify every enabled rule across `packs` for `phase`.
   `violations` are the {:rule :violation} maps from `check-artifact`.

     :skipped-by-phase — the rule's :applies-to {:phases} excludes `phase`
     :not-applicable   — phase matches but file-glob/task-type excludes it
     :failed           — considered and violated (carries a violation summary)
     :passed           — considered and clean

   Rules are resolved first (same as `check-artifact`), so override-by-id is
   honored: evidence is 1:1 with the rules actually evaluated and reports the
   effective merged :severity/:enforcement, not pre-merge values.

   Emitting all four statuses (not just failures) makes the applied policy set
   for a run reconstructable from the event log."
  [packs phase artifact context violations]
  (let [enabled        (filterv policy-pack/rule-enabled?
                                (policy-pack/resolve-rules (mapcat :pack/rules packs)))
        considered-ids (set (map :rule/id
                                 (policy-pack/filter-applicable-rules
                                  enabled (assoc context :phase phase :artifact artifact))))
        violated       (into {} (map (fn [{:keys [rule violation]}]
                                       [(:rule/id rule) violation]))
                             violations)]
    (mapv (fn [rule]
            (let [id (:rule/id rule)]
              {:rule-id     id
               :status      (cond
                              (not (policy-pack/rule-applies-to-phase? rule phase)) :skipped-by-phase
                              (contains? violated id)                               :failed
                              (contains? considered-ids id)                         :passed
                              :else                                                 :not-applicable)
               :severity    (:rule/severity rule)
               :enforcement (get-in rule [:rule/enforcement :action])
               :violation   (violation-summary (get violated id))}))
          enabled)))

(defn- emit-rule-evidence!
  "Publish one :gate/rule-applied event per classified rule. No-op when the
   run carries no event stream. Fail-safe: evidence emission must never break
   enforcement, so a publish error is swallowed (the gate verdict still
   stands)."
  [ctx phase classified]
  (when-let [stream (:event-stream ctx)]
    (try
      (let [wid (:workflow/id ctx)]
        (doseq [{:keys [rule-id status severity enforcement violation]} classified]
          (event-stream/publish!
           stream
           (event-stream/gate-rule-applied stream wid phase rule-id status
                                           {:severity    severity
                                            :enforcement enforcement
                                            :violation   violation}))))
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 2
;; Phase-scoped check

(defn check-policy-pack-for-phase
  "Evaluate the pack against `artifact` for `phase`.

   Selects the enabled rules whose `:applies-to {:phases}` includes `phase`
   (via `policy-pack/check-artifact`'s context filtering), runs their
   detection, and maps the result to a gate result:
     :errors   — blocking (`:hard-halt`) violations as gate errors
     :warnings — require-approval / warn / audit violations (record-only here)
     :passed?  — true iff no blocking violations

   When no pack is available, passes with a no-packs warning (no regression on
   repos without a standards pack)."
  [phase artifact ctx]
  (let [packs (packs-for-gate ctx)]
    (if (empty? packs)
      {:passed? true :warnings [(no-policy-packs-warning)]}
      (let [context (-> ctx with-semantic-wiring (assoc :phase phase))
            result  (policy-pack/check-artifact packs artifact context)]
        (emit-rule-evidence! ctx phase
                             (classify-rules packs phase artifact context
                                             (:violations result)))
        {:passed?  (:passed? result)
         :errors   (vec (:blocking result))
         :warnings (vec (concat (:require-approval result)
                                (:warnings result)
                                (:audits result)))}))))

(defn check-policy-verify
  "Pack gate for the verify phase."
  [artifact ctx]
  (check-policy-pack-for-phase :verify artifact ctx))

(defn check-policy-review
  "Pack gate for the review phase."
  [artifact ctx]
  (check-policy-pack-for-phase :review artifact ctx))

(defn repair-policy-pack
  "Policy violations cannot be fixed in-place; the workflow must redirect to
   :implement for an agent to address the root cause (mirrors the behavioral
   gate)."
  [artifact errors _ctx]
  {:success? false
   :artifact artifact
   :errors   errors
   :message  (msg/t :policy-pack/repair-required)})

;------------------------------------------------------------------------------ Layer 3
;; Registry

(registry/register-gate! :policy-verify)
(registry/register-gate! :policy-review)

(defmethod registry/get-gate :policy-verify
  [_]
  {:name        :policy-verify
   :description (msg/t :policy-pack/description-verify)
   :check       check-policy-verify
   :repair      repair-policy-pack})

(defmethod registry/get-gate :policy-review
  [_]
  {:name        :policy-review
   :description (msg/t :policy-pack/description-review)
   :check       check-policy-review
   :repair      repair-policy-pack})

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; No pack available — passes with a warning.
  (check-policy-pack-for-phase :verify {} {:policy-packs []})

  ;; Against the shipped pack for the review phase.
  (check-policy-pack-for-phase
   :review
   {:artifact/content "(def x 1)" :artifact/path "core.clj"}
   {})

  :leave-this-here)

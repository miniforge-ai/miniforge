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

(ns ai.miniforge.progress-detector.detectors.repair-loop
  "Repair-loop detector — across-turn stagnation in review→implement
   repair cycles.

   Consumes events that carry a `:review/artifact` map. For each
   review observed, the detector reduces the review to a stable
   fingerprint of actionable issues and compares it to the prior
   review's fingerprint. When two consecutive non-empty fingerprints
   match exactly, the implementer hasn't moved the needle between
   iterations and the runtime should stop burning more cycles.

   Emits :anomalies.review/stagnation (:class :mechanical,
   :severity :error). Per the spec this is a verbatim port of the
   review-fingerprint logic that previously lived in
   ai.miniforge.agent.reviewer — same algorithm, same anomaly
   category, same termination semantics. The reviewer namespace
   ends Stage 2 with zero local copies of fingerprint logic.

   Observations without a `:review/artifact` key are passed through
   unchanged — this detector co-exists with the tool-loop detector
   in a MultiDetector and silently ignores tool-event observations."
  (:require
   [ai.miniforge.anomaly.interface          :as anomaly]
   [ai.miniforge.progress-detector.messages :as msg]
   [ai.miniforge.progress-detector.protocol :as proto]
   [clojure.string                          :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:private detector-version
  "Version stamp on emitted anomalies. Bump when fingerprint
   composition or termination semantics change."
  "stage-2.0")

(def ^:private detector-kind :detector/repair-loop)

(def ^:private anomaly-category :anomalies.review/stagnation)

(def ^:private stagnation-match-count
  "Number of consecutive identical fingerprints required to declare
   stagnation. Stamped onto :anomaly/evidence :threshold so the
   downstream caller (supervisor + reviewer) can see the rule that
   fired without consulting the detector source."
  2)

(def ^:private stagnation-window-size
  "Size of the sliding window the repair-loop detector observes
   over. Two consecutive reviews are the operative comparison —
   the detector only retains the prior fingerprint, so window = 2."
  2)

(def ^:private actionable-severities
  "Severities that count as 'something the implementer should fix.'
   Nits are excluded — a stable nit list is not stagnation, it's the
   normal long tail of style polish."
  #{:blocking :warning})

;------------------------------------------------------------------------------ Layer 1
;; Pure fingerprint helpers (verbatim port from agent.reviewer)

(defn- normalize-text
  "Trim and collapse internal whitespace. Trivial reformatting of an
   issue's description (added space, line wrap) does not read as
   progress between repair iterations."
  [s]
  (-> (or s "")
      str/trim
      (str/replace #"\s+" " ")))

(defn- issue-fingerprint
  "Stable per-issue tuple [severity file line description].
   Stores the whitespace-normalized description in full — Clojure's
   `hash` (and the underlying String hashCode) is not collision-free
   and a hash collision would surface as a false-positive stagnation,
   so we keep the full description text for comparison."
  [{:keys [severity file line description]}]
  [severity
   (or file "")
   (or line 0)
   (normalize-text description)])

(defn- failed-gate-fingerprint
  "Convert a failed gate-feedback entry into a virtual issue tuple so
   stagnation also catches gate-only-mode loops. In gate-only mode
   the reviewer populates :review/gate-results without :review/issues,
   so a fingerprint that only consulted :review/issues would be empty
   and the stagnation guard would never fire."
  [{:keys [gate-id errors]}]
  [:blocking
   (str ":gate/" (name (or gate-id :unknown)))
   0
   (normalize-text (str/join " | " (map :message errors)))])

(defn review-fingerprint
  "Reduce a review artifact to a stable, comparable fingerprint of its
   actionable items. Returns a sorted vector of `[severity file line
   description]` tuples — order-independent because the inputs are
   sorted before comparison.

   Two sources contribute:
   - LLM-surfaced :review/issues at severity :blocking or :warning
     (:nit is intentionally excluded — long-tail polish, not
     progress signal).
   - Failed entries in :review/gate-results — covers gate-only mode
     where :review/issues is empty but the review is still blocked.

   A review with neither actionable LLM issues nor failed gates
   returns the empty vector."
  [review]
  (let [llm-issues   (->> (get review :review/issues [])
                          (filter (comp actionable-severities :severity))
                          (map issue-fingerprint))
        failed-gates (->> (get review :review/gate-results [])
                          (remove :passed?)
                          (map failed-gate-fingerprint))]
    (->> (concat llm-issues failed-gates)
         sort
         vec)))

(defn stagnated?
  "True when a review's fingerprint matches the prior review's
   fingerprint exactly. Strict equality — if the implementer produced
   any actionable change between iterations the fingerprint should
   differ at minimum on file or description text. Empty current
   fingerprint never counts as stagnation (no actionable items =
   nothing to be stuck on).

   `prior` may be nil (first iteration); returns false in that case
   so the first review never short-circuits."
  [prior current]
  (and (some? prior)
       (seq current)
       (= prior current)))

;------------------------------------------------------------------------------ Layer 2
;; Anomaly construction

(defn- evidence-summary
  "Human-readable summary line for an emitted anomaly."
  [match-count]
  (msg/t :repair-loop/stagnation {:count match-count}))

(defn- build-anomaly
  "Construct a canonical anomaly map for a stagnation event."
  [fingerprint event]
  (let [match-count (count fingerprint)
        summary     (evidence-summary match-count)
        evidence    {:summary     summary
                     :event-ids   (filterv some? [(:seq event)])
                     :fingerprint (str fingerprint)
                     :threshold   {:n      stagnation-match-count
                                   :window stagnation-window-size}
                     :redacted?   true}
        data        {:detector/kind     detector-kind
                     :detector/version  detector-version
                     :anomaly/class     :mechanical
                     :anomaly/severity  :error
                     :anomaly/category  anomaly-category
                     :anomaly/evidence  evidence}]
    (anomaly/anomaly :fault summary data)))

;------------------------------------------------------------------------------ Layer 3
;; Detector record

(defrecord RepairLoopDetector []
  proto/Detector
  (init [_ _config]
    {:anomalies         []
     :prior-fingerprint nil})

  (observe [_ state event]
    (if-let [review (:review/artifact event)]
      (let [fp           (review-fingerprint review)
            now-stagnated? (stagnated? (:prior-fingerprint state) fp)
            anomaly-map  (when now-stagnated? (build-anomaly fp event))]
        (cond-> state
          true        (assoc :prior-fingerprint fp)
          anomaly-map (update :anomalies conj anomaly-map)))
      state)))

(defn make-repair-loop-detector
  "Construct a RepairLoopDetector. No registry needed — the detector
   operates on review-artifact maps embedded in the event stream."
  []
  (->RepairLoopDetector))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Smoke-test in REPL
  (def det (make-repair-loop-detector))
  (def state0 (proto/init det {}))
  (def review {:review/issues [{:severity :blocking
                                :file "src/foo.clj"
                                :line 42
                                :description "needs guard"}]
               :review/gate-results []})
  (-> state0
      (#(proto/observe det % {:seq 1 :review/artifact review}))
      (#(proto/observe det % {:seq 2 :review/artifact review}))
      :anomalies)
  ;; => [{...:anomaly/data {:detector/kind :detector/repair-loop ...}}]

  :leave-this-here)

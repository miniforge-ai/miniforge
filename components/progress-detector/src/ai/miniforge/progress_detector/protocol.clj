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

(ns ai.miniforge.progress-detector.protocol
  "Detector protocol: pure-reducer interface for progress anomaly detection.

   Detectors are stateless pure functions that fold observations into
   accumulated state. The protocol enforces two operations:

     init    - produce the initial (empty) detector state from config
     observe - pure reducer: (state, observation) -> state'

   Anomalies are surfaced as a sequence under the :anomalies key in the
   returned state. Callers accumulate state across observations and read
   anomalies after each step.

   Determinism guarantee:
     Given identical config and identical observation sequence, observe
     MUST return identical state. Side effects are prohibited inside
     detectors; all I/O happens outside the reduce loop.")

;------------------------------------------------------------------------------ Layer 0
;; Core protocol

(defprotocol Detector
  "Pure reducer for progress anomaly detection.

   Implementations MUST be:
   - Pure: no side effects, no hidden I/O
   - Deterministic: identical inputs → identical outputs
   - Serializable: state must be EDN-representable (maps/vecs/sets/scalars)

   Usage pattern:
     (let [state  (init   detector cfg)
           state' (observe detector state obs)]
       (:anomalies state'))"

  (init [detector config]
    "Produce the initial detector state from config map.

     Arguments:
       config - map of detector-specific configuration. Shape varies by
                implementation; unknown keys MUST be ignored.

     Returns: Initial state map. Must include at minimum:
       {:anomalies []   ; empty anomaly log
        :observations [] ; empty observation window}

     Implementations SHOULD merge config defaults defensively.")

  (observe [detector state observation]
    "Pure reducer: fold one observation into accumulated state.

     Arguments:
       state       - current detector state (result of init or prior observe)
       observation - map describing one tool invocation:
                       :tool/id       - qualified keyword identifying the tool
                       :tool/input    - input args map (may be redacted)
                       :tool/output   - output value (may be nil if pending)
                       :tool/duration-ms - wall-clock ms for this call
                       :timestamp     - inst of observation
                       :seq           - monotonic sequence number

     Returns: New state map (same shape as init return) with :anomalies
     updated to include any newly detected anomaly maps.

     Contract:
       - MUST NOT mutate state argument
       - MUST return a map satisfying the same shape as init output
       - MUST NOT throw; anomalies surface in-band as data"))

;------------------------------------------------------------------------------ Layer 1
;; Null detector — reference implementation (pass-through, never flags)

(defrecord NullDetector []
  Detector
  (init [_detector _config]
    {:anomalies    []
     :observations []
     :window-size  0})

  (observe [_detector state observation]
    (-> state
        (update :observations conj observation)
        (update :window-size inc))))

(defn null-detector
  "Return a NullDetector that never flags any anomaly.
   Useful as a no-op placeholder and for composing detector pipelines."
  []
  (->NullDetector))

;------------------------------------------------------------------------------ Layer 1
;; Multi-detector: fan observations through a seq of detectors

(defrecord MultiDetector [detectors]
  Detector
  (init [_detector config]
    {:anomalies    []
     :observations []
     :sub-states   (mapv #(init % config) detectors)})

  (observe [_detector state observation]
    ;; Compute the DELTA per sub-state — newly emitted anomalies in
    ;; this step only. The previous implementation appended each
    ;; sub-state's full :anomalies history on every observe, growing
    ;; the top-level vector quadratically (Copilot review on PR #784).
    ;; subvec gives an O(1) view over the trailing slice.
    (let [old-sub-states  (:sub-states state)
          old-counts      (mapv #(count (:anomalies %)) old-sub-states)
          new-sub-states  (mapv (fn [sub-det sub-state]
                                  (observe sub-det sub-state observation))
                                detectors
                                old-sub-states)
          delta-anomalies (into []
                                (mapcat (fn [sub-state old-count]
                                          (subvec (:anomalies sub-state)
                                                  old-count))
                                        new-sub-states
                                        old-counts))]
      (-> state
          (update :observations conj observation)
          (assoc  :sub-states new-sub-states)
          (update :anomalies into delta-anomalies)))))

(defn multi-detector
  "Compose multiple detectors into one.
   Observations are fanned to all children; anomalies are merged.

   Arguments:
     detectors - seq of Detector implementations

   Returns: MultiDetector record"
  [detectors]
  (->MultiDetector (vec detectors)))

;------------------------------------------------------------------------------ Layer 2
;; Helper: reduce a seq of observations through a detector

(defn reduce-observations
  "Fold a sequence of observations through detector, returning final state.

   Arguments:
     detector     - Detector implementation
     config       - config map passed to init
     observations - seq of observation maps

   Returns: Final detector state after all observations are folded."
  [detector config observations]
  (reduce (partial observe detector)
          (init detector config)
          observations))

;------------------------------------------------------------------------------ Rich Comment
;; Rich comment — development examples

(comment
  ;; Null detector — always passes through
  (let [det (null-detector)
        obs {:tool/id :tool/bash
             :timestamp (java.time.Instant/now)
             :seq 1
             :tool/duration-ms 150}]
    (-> (init det {})
        (observe det obs)))
  ;; => {:anomalies [] :observations [{...}] :window-size 1}

  ;; reduce-observations helper
  (reduce-observations (null-detector) {}
                       [{:tool/id :tool/write :seq 1 :tool/duration-ms 20}
                        {:tool/id :tool/bash  :seq 2 :tool/duration-ms 80}])
  ;; => {:anomalies [] :observations [{...} {...}] :window-size 2}

  :leave-this-here)

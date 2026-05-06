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

(ns ai.miniforge.progress-detector.detectors.tool-loop
  "Tool-loop detector — mechanical fingerprint sliding-window.

   Folds a stream of Observations and emits an anomaly when the same
   tool fingerprint repeats above a threshold within a window. The
   anomaly's :class (:mechanical vs :heuristic) is gated by the
   tool's :determinism, looked up in a registry passed to the
   detector at construction:

     :stable-with-resource-version - mechanical when version-hash
                                     is in the fingerprint
     :stable-ish                   - mechanical (acceptable bounded
                                     false-positive rate)
     :environment-dependent        - mechanical only when every match
                                     has :tool/error? true AND the
                                     redacted :tool/output (Stage 2
                                     stub for parsed failure-
                                     fingerprint) matches; otherwise
                                     heuristic
     :unstable                     - heuristic only

   Default config (override via the layered :config/params map):
     :window-size  10  - sliding window size (oldest evicted)
     :threshold-n   5  - emit when fingerprint count >= this in window

   Total per-observe work is bounded by window-size, so per-event
   overhead stays under the spec's 5ms budget for any reasonable
   default."
  (:require
   [ai.miniforge.anomaly.interface         :as anomaly]
   [ai.miniforge.progress-detector.config  :as cfg]
   [ai.miniforge.progress-detector.messages :as msg]
   [ai.miniforge.progress-detector.protocol :as proto]
   [ai.miniforge.progress-detector.tool-profile :as tp]))

;------------------------------------------------------------------------------ Layer 0
;; Defaults

(def ^:private default-window-size
  "Sliding-window size used when config/params omits :window-size.
   10 mirrors the Stage 1 spec example."
  10)

(def ^:private default-threshold-n
  "Repeat-count threshold used when config/params omits :threshold-n.
   5 mirrors the Stage 1 spec example."
  5)

(def ^:private detector-version
  "Version string stamped into emitted anomalies. Bump when the
   fingerprint composition or class-decision rules change."
  "stage-2.0")

(def ^:private detector-kind :detector/tool-loop)

(def ^:private anomaly-category :anomalies.agent/tool-loop)

(def ^:private mechanical-determinisms
  "Determinism levels whose fingerprint repeat counts as a mechanical
   signal without further evidence. :environment-dependent gets a
   conditional rule (see classify-match-class)."
  #{:stable-with-resource-version :stable-ish})

;------------------------------------------------------------------------------ Layer 1
;; Fingerprint composition (per determinism)

(defn- fingerprint-of
  "Reduce an observation to a stable fingerprint vector under the
   given determinism. Two observations that share a fingerprint
   represent 'the same call' for loop detection.

   Composition by determinism:
     :stable-with-resource-version  [tool-id input :resource/version-hash]
     :stable-ish                    [tool-id input]
     :environment-dependent         [tool-id input :tool/error? :tool/output]
     :unstable                      [tool-id input]    (heuristic only)
     <unknown>                      [tool-id input]    (safe default)

   :tool/input and :tool/output are already redacted by the event
   envelope so this never holds raw content."
  [observation determinism]
  (let [base [(:tool/id observation)
              (:tool/input observation)]]
    (case determinism
      :stable-with-resource-version
      (conj base (:resource/version-hash observation))

      :environment-dependent
      (conj base
            (:tool/error? observation)
            (:tool/output observation))

      ;; :stable-ish, :unstable, unknown
      base)))

;------------------------------------------------------------------------------ Layer 1
;; Class decision

(defn- env-dep-mechanical?
  "True when an :environment-dependent fingerprint group looks like a
   mechanical failure loop. Spec: every match must have :tool/error?
   true, AND the parsed failure-fingerprint must be identical across
   matches. Stage 2 stub treats the redacted :tool/output as the
   failure-fingerprint — group fingerprints already encode equal
   :tool/output, so we only need to check the error-flag invariant."
  [matches]
  (every? :tool/error? matches))

(defn- classify-match-class
  "Return :mechanical or :heuristic for an above-threshold fingerprint
   group, given the tool's :determinism and the matching observations."
  [determinism matches]
  (cond
    (contains? mechanical-determinisms determinism) :mechanical
    (and (= determinism :environment-dependent)
         (env-dep-mechanical? matches))             :mechanical
    :else                                           :heuristic))

(defn- class->severity
  "Severity stamped on the emitted anomaly. Mechanical loops are
   :error (controlling, will terminate). Heuristic loops are :warn
   (logged, no action under the Stage 2 default policy)."
  [anomaly-class]
  (case anomaly-class
    :mechanical :error
    :heuristic  :warn))

;------------------------------------------------------------------------------ Layer 2
;; Anomaly construction

(defn- evidence-summary
  "Human-readable summary line for an anomaly. Tool-id and the
   match count are the two non-redacted facts a reviewer needs."
  [tool-id match-count]
  (msg/t :tool-loop/loop-detected
         {:tool-id tool-id :count match-count}))

(defn- build-anomaly
  "Construct a canonical anomaly map for an above-threshold group."
  [{:keys [tool-id matches anomaly-class threshold-n window-size
           fingerprint]}]
  (let [match-count (count matches)
        summary     (evidence-summary tool-id match-count)
        evidence    {:summary     summary
                     :event-ids   (mapv :seq matches)
                     :fingerprint (str fingerprint)
                     :threshold   {:n threshold-n :window window-size}
                     :redacted?   true}
        data        {:detector/kind     detector-kind
                     :detector/version  detector-version
                     :anomaly/class     anomaly-class
                     :anomaly/severity  (class->severity anomaly-class)
                     :anomaly/category  anomaly-category
                     :anomaly/evidence  evidence}]
    (anomaly/anomaly :fault summary data)))

;------------------------------------------------------------------------------ Layer 2
;; Window helpers

(defn- slide-window
  "Append observation to window, evicting the oldest if full."
  [window observation window-size]
  (let [appended (conj window observation)]
    (if (> (count appended) window-size)
      (subvec appended 1)
      appended)))

(defn- matches-in-window
  "Return the subset of `window` whose fingerprint matches `fp`,
   computed against `determinism`."
  [window fp determinism]
  (filterv #(= fp (fingerprint-of % determinism)) window))

;------------------------------------------------------------------------------ Layer 3
;; Detector record

(defn- maybe-emit-anomaly
  "Return an anomaly map when the most-recent observation's fingerprint
   has reached the threshold inside the window, else nil."
  [{:keys [registry threshold-n window-size]} window observation]
  (let [tool-id      (:tool/id observation)
        determinism  (tp/determinism-of tool-id registry)
        fp           (fingerprint-of observation determinism)
        matches      (matches-in-window window fp determinism)]
    (when (>= (count matches) threshold-n)
      (build-anomaly
       {:tool-id       tool-id
        :matches       matches
        :anomaly-class (classify-match-class determinism matches)
        :threshold-n   threshold-n
        :window-size   window-size
        :fingerprint   fp}))))

(defrecord ToolLoopDetector [registry]
  proto/Detector
  (init [_ config]
    (let [params (cfg/effective-params config)]
      {:anomalies   []
       :window      []
       :window-size (get params :window-size default-window-size)
       :threshold-n (get params :threshold-n default-threshold-n)
       :registry    registry}))

  (observe [_ state observation]
    (let [window-size (:window-size state)
          new-window  (slide-window (:window state) observation window-size)
          anomaly-map (maybe-emit-anomaly state new-window observation)]
      (cond-> state
        true        (assoc :window new-window)
        anomaly-map (update :anomalies conj anomaly-map)))))

(defn make-tool-loop-detector
  "Construct a ToolLoopDetector backed by `registry`. Pass the
   process-wide default-tool-registry, or a test-isolated registry
   from make-tool-registry."
  [registry]
  (->ToolLoopDetector registry))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Quick smoke test in REPL
  (require '[ai.miniforge.progress-detector.tool-profile :as tp])
  (def reg (tp/make-registry))
  (tp/register! reg
                {:tool/id     :tool/Read
                 :determinism :stable-with-resource-version
                 :anomaly/categories #{:anomalies.agent/tool-loop}})
  (def det (make-tool-loop-detector reg))
  (def state0 (proto/init det {:config/params {:threshold-n 3 :window-size 5}}))

  ;; Three identical Reads with the same version-hash → mechanical
  (let [obs {:tool/id   :tool/Read
             :seq       1
             :timestamp (java.time.Instant/now)
             :tool/input            "src/foo.clj"
             :resource/version-hash "sha256:aaa"}]
    (-> state0
        (#(proto/observe det % (assoc obs :seq 1)))
        (#(proto/observe det % (assoc obs :seq 2)))
        (#(proto/observe det % (assoc obs :seq 3)))
        :anomalies))
  ;; => [{...:anomaly/data {:detector/kind :detector/tool-loop ...}}]

  :leave-this-here)

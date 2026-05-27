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

(ns ai.miniforge.anomaly.interface
  "Public API for the anomaly component.

   An anomaly is a plain map describing a failure. Functions return
   anomalies instead of throwing — exceptions are reserved for
   programmer errors at the boundary (e.g. an unknown anomaly type).

   Standard usage:

     (require '[ai.miniforge.anomaly.interface :as anomaly])

     (defn lookup [id]
       (or (db/find id)
           (anomaly/anomaly :not-found
                            (str \"No record for id \" id)
                            {:id id})))

     (let [result (lookup 42)]
       (if (anomaly/anomaly? result)
         (handle-failure result)
         (process result)))"
  (:require
   [ai.miniforge.anomaly.contract :as contract]))

;------------------------------------------------------------------------------ Layer 0
;; Schema/vocabulary re-exports + helpers with no in-namespace deps.

(def Anomaly
  "Malli schema for the canonical anomaly map. See
   `ai.miniforge.anomaly.contract/Anomaly`."
  contract/Anomaly)

(def anomaly-types
  "Set of standard anomaly type keywords. See
   `ai.miniforge.anomaly.contract/anomaly-types`."
  contract/anomaly-types)

(defn- now
  "Return the current instant. Wrapped so tests can redef."
  []
  (java.time.Instant/now))

;------------------------------------------------------------------------------ Layer 1
;; Construction — composes Layer 0 (`now`). Cross-namespace use of the
;; `contract` namespace doesn't participate in the intra-namespace
;; layering model.

(defn anomaly
  "Construct a canonical anomaly map.

   - `type`    — keyword from `anomaly-types`; an unknown type throws
                 IllegalArgumentException because that is a programmer
                 error, not a runtime condition.
   - `message` — human-readable description string.
   - `data`    — caller-supplied context map; nil is normalized to {}.

   The :anomaly/at timestamp is populated at construction with the
   current instant. The returned map satisfies the `Anomaly` schema."
  [type message data]
  (when-not (contains? contract/anomaly-types type)
    (throw (IllegalArgumentException.
            (str "Unknown anomaly type: " (pr-str type)
                 ". Must be one of " (pr-str contract/anomaly-types)))))
  {:anomaly/type    type
   :anomaly/message message
   :anomaly/data    (or data {})
   :anomaly/at      (now)})

(defn sub-anomaly
  "Construct an anomaly carrying a domain `:anomaly/subtype` — a finer
   classification (e.g. `:gate/validation-failed`, `:agent/tool-loop`)
   that routing and classification dispatch on. `type` stays one of the
   generic vocabulary; `subtype` names the domain-specific failure.
   Use when a consumer needs more than the generic type to route."
  [type subtype message data]
  (assoc (anomaly type message data) :anomaly/subtype subtype))

(defn subtype
  "The domain `:anomaly/subtype` of `a`, or nil when it carries only a
   generic `:anomaly/type`. Routing/classification should prefer this
   over `:anomaly/type` when present."
  [a]
  (:anomaly/subtype a))

(defn validation-anomaly
  "Construct an `:invalid-input` anomaly describing a schema-validation
   failure. The schema name, offending value, and explain data are
   carried under `:anomaly/data` so callers can inspect why validation
   failed without re-running it."
  [message schema-name value explain-data]
  (anomaly :invalid-input
           message
           {:anomaly/schema  schema-name
            :anomaly/value   value
            :anomaly/explain explain-data}))

(defn exception-anomaly
  "Wrap an exception as an anomaly of `type`, preserving the original
   provenance — exception message and class (plus `ex-data` when
   present) — under `:anomaly/data`. Any caller `data` is merged in.
   Use at boundaries that must translate a thrown exception into the
   data-first anomaly contract."
  ([type message ex]
   (exception-anomaly type message {} ex))
  ([type message data ex]
   (let [ed (ex-data ex)]
     (anomaly type
              message
              (cond-> (assoc data
                             :anomaly/ex-message (ex-message ex)
                             :anomaly/ex-class   (.getName (class ex)))
                ed (assoc :anomaly/ex-data ed))))))

;------------------------------------------------------------------------------ Layer 2
;; Predicate

(defn anomaly?
  "True when `x` is a map matching the canonical anomaly shape.

   Returns false for nil, non-maps, and maps missing required keys
   or holding values that do not match the schema."
  [x]
  (and (map? x)
       (contract/valid? x)))

;------------------------------------------------------------------------------ Macros
;; Compile-time control flow; expansion composes the Layer 2 predicate.

(defmacro let-ok
  "Sequential `let`-style bindings that short-circuit on the first
   anomaly. Each right-hand expression is evaluated and bound
   left-to-right; if one evaluates to an anomaly (per `anomaly?`), that
   anomaly is returned immediately and the remaining bindings and
   `body` are skipped. When no binding yields an anomaly, evaluates
   `body` like `let`.

   Use for railway-style pipelines over anomaly-returning steps in
   place of nested `(if (anomaly? x) x (let [...] ...))` pyramids:

     (let-ok [user    (lookup id)
              account (account-for user)]
       (summarize account))

   Returns the first anomaly encountered, otherwise the body's value."
  [bindings & body]
  (when-not (vector? bindings)
    (throw (IllegalArgumentException. "let-ok requires a binding vector")))
  (when-not (even? (count bindings))
    (throw (IllegalArgumentException. "let-ok requires an even number of binding forms")))
  (reduce
   (fn [acc [sym expr]]
     `(let [~sym ~expr]
        (if (anomaly? ~sym)
          ~sym
          ~acc)))
   `(do ~@body)
   (reverse (partition 2 bindings))))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (def boom (anomaly :not-found "missing user" {:id 7}))

  (anomaly? boom)
  ;; => true

  (anomaly? {:anomaly/type :bogus
             :anomaly/message "x"
             :anomaly/data {}
             :anomaly/at (java.time.Instant/now)})
  ;; => false (type not in vocabulary)

  ;; Programmer error — wrong type at construction throws
  (try (anomaly :no-such-type "x" {})
       (catch IllegalArgumentException e (.getMessage e)))

  :leave-this-here)

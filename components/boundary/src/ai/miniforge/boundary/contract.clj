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

(ns ai.miniforge.boundary.contract
  "Malli contracts for the boundary primitive.

   The boundary component catches exceptions thrown by libraries (DBs,
   HTTP clients, JVM I/O, parsers, ...) and converts them into anomaly
   steps in a response-chain. It is the third H-series cross-cutting
   primitive — anomaly is the shape, response-chain carries it through
   a flow, boundary captures throws at the edges and turns them into
   data.

   The schemas here describe the exception-category vocabulary and the
   shape of the captured exception payload that lands inside an
   anomaly's `:anomaly/data`."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Exception-category vocabulary

(def exception-categories
  "Standard categories for classifying caught exceptions.

   The category is supplied by the call site (the developer knows what
   kind of library call they are wrapping) and drives the canonical
   anomaly type the chain step ends up carrying:

   - :network     — outbound network failure (connection refused,
                    DNS, socket reset, TLS handshake)
   - :db          — database driver/JDBC failure
   - :io          — local filesystem or stream I/O failure
   - :parse       — malformed input from a deserializer (JSON, EDN, ...)
   - :timeout     — operation exceeded its time budget
   - :unavailable — downstream dependency is down
   - :unknown     — caller cannot classify; fall back to :fault"
  #{:network
    :db
    :io
    :parse
    :timeout
    :unavailable
    :unknown})

;------------------------------------------------------------------------------ Layer 1
;; Captured-exception payload

(def CapturedException
  "Malli schema for the exception payload stored inside an anomaly's
   `:anomaly/data` when boundary converts a throw into a chain step.

   Every field is plain data so the payload can be serialized into the
   evidence-bundle and replayed later:
   - :exception/type    — fully-qualified class name (string)
   - :exception/message — `(.getMessage e)` or nil
   - :exception/cause   — immediate-cause message string from
                          `(.getCause e)`, or nil when there is no
                          cause. Note: this is the *immediate* cause
                          only, not a walked root cause; nested causes
                          remain accessible via the original throwable
                          if a caller needs them, but boundary itself
                          does not unwind the chain.
   - :exception/data    — `(ex-data e)` for `ExceptionInfo`, else nil
   - :boundary/category — the category the call site supplied"
  [:map {:closed true}
   [:exception/type     :string]
   [:exception/message  [:maybe :string]]
   [:exception/cause    [:maybe :string]]
   [:exception/data     [:maybe [:map-of :any :any]]]
   [:boundary/category  (into [:enum] exception-categories)]])

;------------------------------------------------------------------------------ Layer 2
;; check-fn shape (advisory)
;;
;; The boundary primitive is intentionally small: callers pass an
;; ordinary 0+ arity function `f` plus its `args`. There is no
;; mandatory pre-flight `check-fn` — but downstream callers commonly
;; want to validate input before invoking `f`, and a `check-fn` is the
;; conventional name for that. The schema below documents the shape
;; consumers should use when they layer one on top of `execute`.

(def CheckFn
  "Malli schema for the optional pre-flight check function consumers
   may layer on top of `execute`. A `check-fn` returns nil when its
   inputs are acceptable, or a canonical `Anomaly` (per
   `ai.miniforge.anomaly.interface/Anomaly`) when they are not.
   Boundary itself does not consume a check-fn — this schema is
   exposed so higher-level wrappers agree on the shape."
  [:=> [:cat [:* :any]] [:maybe anomaly/Anomaly]])

;------------------------------------------------------------------------------ Layer 3
;; Validation helpers

(defn valid-category?
  "Return true when `value` is one of the standard
   `exception-categories`."
  [value]
  (contains? exception-categories value))

(defn valid-captured-exception?
  "Return true when `value` matches the `CapturedException` schema."
  [value]
  (m/validate CapturedException value))

(defn explain-captured-exception
  "Return a humanized explanation for an invalid captured-exception
   payload, or nil when `value` is valid."
  [value]
  (some-> (m/explain CapturedException value)
          me/humanize))

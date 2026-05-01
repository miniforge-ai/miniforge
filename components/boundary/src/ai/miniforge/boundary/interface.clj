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

(ns ai.miniforge.boundary.interface
  "Public API for the boundary component.

   Boundary is the third H-series cross-cutting primitive. Together
   with `ai.miniforge.anomaly` (the failure shape) and
   `ai.miniforge.response-chain` (the runtime trace), it lets every
   miniforge-family Clojure repo speak one error-flow vocabulary:
   functions return anomalies, the chain accumulates them, and
   `boundary/execute` is the canonical place to catch a thrown
   exception from a library call and convert it into a chain step.

   The component is itself a boundary helper: it never throws on
   runtime input. The only acceptable throw is the programmer-error
   guard for an unknown category.

   Standard usage. Category comes first, chain second — so callers
   thread with `as->` (or explicit `let` rebinding) rather than `->`:

     (require '[ai.miniforge.boundary.interface :as boundary]
              '[ai.miniforge.response-chain.interface :as chain])

     (defn fetch-user [id]
       (as-> (chain/create-chain :user/fetch) c
         (boundary/execute :db      c :db/find-user      db-find id)
         (boundary/execute :network c :api/sync-profile  sync!   id)))"
  (:require
   [ai.miniforge.boundary.contract :as contract]
   [ai.miniforge.boundary.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Vocabulary and schema re-exports

(def exception-categories
  "Set of standard exception-category keywords. See
   `ai.miniforge.boundary.contract/exception-categories`."
  contract/exception-categories)

(def CapturedException
  "Malli schema for the captured-exception payload that lives inside
   an anomaly's `:anomaly/data`. See
   `ai.miniforge.boundary.contract/CapturedException`."
  contract/CapturedException)

(def CheckFn
  "Malli schema for an advisory pre-flight `check-fn` consumers may
   layer on top of `execute`. Returns nil when its inputs are
   acceptable, or a canonical `Anomaly` when they are not. Boundary
   itself does not consume a check-fn; the schema is exposed so
   higher-level wrappers agree on the shape. See
   `ai.miniforge.boundary.contract/CheckFn`."
  contract/CheckFn)

(def category->anomaly-type
  "Read-only mapping from boundary category to canonical anomaly type.
   See `ai.miniforge.boundary.core/category->anomaly-type`.

       :network     -> :unavailable
       :db          -> :fault
       :io          -> :fault
       :parse       -> :invalid-input
       :timeout     -> :timeout
       :unavailable -> :unavailable
       :unknown     -> :fault

   The map is invokable as a function in Clojure for known keys
   (`(category->anomaly-type :db) ;; => :fault`); use `classify-category`
   when you want explicit lookup-with-`:fault`-default semantics for
   unknown keys."
  core/category->anomaly-type)

(defn classify-category
  "Return the canonical anomaly type for `category`, falling back to
   `:fault` when the category is not in `exception-categories`.

   This is the function form of the lookup. The underlying
   `category->anomaly-type` mapping is also re-exported as a map for
   inspection (e.g. listing every (category, type) pair, or asserting
   coverage in tests)."
  [category]
  (get category->anomaly-type category :fault))

;------------------------------------------------------------------------------ Layer 1
;; Canonical wrapper

(defn execute-with-exception-handling
  "Run `(apply f args)` and convert the outcome into a chain step.

   - On success: appends a successful step under `operation-key` to
     `chain` carrying the function's return value.
   - On thrown exception: classifies the throw via `category`, builds
     an anomaly whose type is determined by `category->anomaly-type`,
     captures the exception payload (`:exception/type`,
     `:exception/message`, `:exception/cause`, `:exception/data`,
     `:boundary/category`), appends an anomaly step, and returns the
     now-failed chain.

   Never throws on runtime input. Throws `IllegalArgumentException`
   only when `category` is not in `exception-categories` — that is a
   programmer error at the call site, not a runtime condition."
  [category chain operation-key f & args]
  (apply core/execute-with-exception-handling
         category chain operation-key f args))

(defn execute
  "Short alias for `execute-with-exception-handling`."
  [category chain operation-key f & args]
  (apply core/execute-with-exception-handling
         category chain operation-key f args))

;------------------------------------------------------------------------------ Rich Comment

(comment
  (require '[ai.miniforge.response-chain.interface :as chain])

  (def c0 (chain/create-chain :demo))

  (def c1 (execute :db c0 :db/lookup
                   (fn [id] {:id id :name "ada"})
                   42))
  (chain/succeeded? c1)
  ;; => true

  (def c2 (execute :network c1 :api/call
                   (fn [_] (throw (java.io.IOException. "connection refused")))
                   :anything))
  (chain/succeeded? c2)
  ;; => false
  (-> c2 chain/last-anomaly :anomaly/type)
  ;; => :unavailable
  (-> c2 chain/last-anomaly :anomaly/data :exception/type)
  ;; => "java.io.IOException"

  ;; Programmer error — unknown category throws
  (try (execute :no-such-category c0 :op identity 1)
       (catch IllegalArgumentException e (.getMessage e)))

  :leave-this-here)

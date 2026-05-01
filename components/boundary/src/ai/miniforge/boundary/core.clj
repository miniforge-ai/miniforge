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

(ns ai.miniforge.boundary.core
  "Implementation of the boundary primitive.

   `execute-with-exception-handling` runs an arbitrary 0+ arity
   function and converts any thrown exception into an anomaly step
   appended to the supplied response-chain. Success appends a
   successful step.

   The component is itself a boundary helper: it never throws on
   runtime input. The single permitted throw is a programmer-error
   guard for an unknown category (mirrors anomaly's vocabulary check
   at construction)."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.boundary.contract :as contract]
   [ai.miniforge.response-chain.interface :as chain]))

;------------------------------------------------------------------------------ Layer 0
;; Category → anomaly-type mapping

(def category->anomaly-type
  "Data-driven mapping from boundary category to canonical anomaly
   type. Public read-only table — exposed through the interface so
   callers and reviewers can audit the wiring without reading code.

   Every category in `contract/exception-categories` must appear here;
   the assertion at namespace load guards the invariant."
  {:network     :unavailable
   :db          :fault
   :io          :fault
   :parse       :invalid-input
   :timeout     :timeout
   :unavailable :unavailable
   :unknown     :fault})

;; Compile-time invariant: every standard category resolves to an
;; anomaly type. Catches drift if either set is edited in isolation.
(assert (= contract/exception-categories
           (set (keys category->anomaly-type)))
        "boundary/category->anomaly-type must cover every exception-category")

;------------------------------------------------------------------------------ Layer 1
;; Programmer-error guard

(defn- assert-known-category!
  "Throw `IllegalArgumentException` when `category` is not in the
   standard vocabulary. This is the one place boundary throws — the
   exception means the call site is wrong, not that runtime data went
   bad."
  [category]
  (when-not (contract/valid-category? category)
    (throw (IllegalArgumentException.
            (str "Unknown boundary category: " (pr-str category)
                 ". Must be one of "
                 (pr-str contract/exception-categories))))))

;------------------------------------------------------------------------------ Layer 2
;; Exception → captured payload

(defn- cause-message
  "Return the message of the immediate cause of `e`, or nil when there
   is no cause. Boundary code may pass the cause as nil (no
   `Throwable.getCause` link), so we explicitly nil-coalesce."
  [^Throwable e]
  (when-let [cause (.getCause e)]
    (.getMessage ^Throwable cause)))

(defn- exception-class-name
  "Return the fully-qualified class name of `e`. Always a string —
   every JVM Throwable has a class."
  [^Throwable e]
  (.getName (class e)))

(defn- safe-ex-data
  "Return `(ex-data e)` when `e` is an `ExceptionInfo`, else nil.
   `ex-data` returns nil for non-`IExceptionInfo`, but callers may
   subclass; this stays defensive."
  [e]
  (try
    (ex-data e)
    (catch Throwable _ nil)))

(defn- capture-exception
  "Build the `CapturedException` payload for the supplied exception
   and category. Pure — never throws."
  [^Throwable e category]
  {:exception/type     (exception-class-name e)
   :exception/message  (.getMessage e)
   :exception/cause    (cause-message e)
   :exception/data     (safe-ex-data e)
   :boundary/category  category})

;------------------------------------------------------------------------------ Layer 3
;; Exception → anomaly

(defn- category->type
  "Resolve `category` to its anomaly type. Falls back to `:fault` for
   unknown categories so the function stays non-throwing in the
   recovery path; the public entry point's `assert-known-category!`
   handles programmer-error rejection up front."
  [category]
  (get category->anomaly-type category :fault))

(defn- exception->anomaly
  "Convert exception `e` (under `category`) into a canonical anomaly.
   The anomaly's `:anomaly/data` carries the captured payload."
  [^Throwable e category]
  (let [an-type (category->type category)
        message (or (.getMessage e)
                    (exception-class-name e))]
    (anomaly/anomaly an-type message (capture-exception e category))))

;------------------------------------------------------------------------------ Layer 4
;; Safe invocation

(defn- safe-apply
  "Apply `f` to `args`, returning either {:ok value} on success or
   {:throw e} on any thrown `Throwable`. Never propagates."
  [f args]
  (try
    {:ok (apply f args)}
    (catch Throwable e
      {:throw e})))

;------------------------------------------------------------------------------ Layer 5
;; Public entry point

(defn execute-with-exception-handling
  "Canonical exception → anomaly wrapper.

   Calls `(apply f args)`. On success appends a successful step under
   `operation-key` to `chain` and returns the updated chain. On a
   thrown exception, classifies the throw via `category`, converts it
   into an anomaly carrying the `CapturedException` payload, appends
   an anomaly step, and returns the (now-failed) chain.

   Never throws on runtime input — the chain absorbs the exception as
   data. Throws `IllegalArgumentException` only when `category` is not
   in `contract/exception-categories`; that is a programmer error, not
   a runtime condition."
  [category chain operation-key f & args]
  (assert-known-category! category)
  (let [result (safe-apply f args)]
    (if-let [e (:throw result)]
      (chain/append-step chain
                         operation-key
                         (exception->anomaly e category)
                         nil)
      (chain/append-step chain operation-key (:ok result)))))

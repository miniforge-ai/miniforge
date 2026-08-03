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
(ns ai.miniforge.compliance-scanner.exceptions-as-data-walk
  "Recursive form walk over a file's read forms, split out of
   `exceptions-as-data` (rule 210: an eighth real layer there is the
   signal to split it). Threads classification context (enclosing defn,
   interrupted-exception/cleanup-rethrow bindings, response-chain
   boundary) down through nested forms and emits a violation record for
   every throw-shaped site it finds.

   Layer 0: Per-parent context enrichment
   Layer 1: Recursive walk emitting violation records"
  (:require [ai.miniforge.compliance-scanner.exceptions-as-data-classify :as classify]
            [ai.miniforge.compliance-scanner.exceptions-as-data-reader   :as reader]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} form-context
  "Enrich traversal context once for a parent form before walking children."
  [context form]
  (let [defn-data   (classify/defn-context form)
        interrupted (classify/interrupted-catch-binding form)
        catch-binding (when (and (seq? form)
                                 (= 'catch (first form)))
                        (nth form 2 nil))]
    (cond-> context
      defn-data (merge defn-data)
      (and (seq? form)
           (symbol? (first form))
           (= "execute-with-handling" (name (first form))))
      (assoc :response-chain-boundary? true)
      interrupted (assoc :interrupted-binding interrupted)
      (and (symbol? catch-binding)
           (classify/cleanup-before-rethrow? form catch-binding))
      (assoc :cleanup-rethrow-binding catch-binding))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} visit-form
  "Walk a single form and conj violation records onto `acc!` (a transient
   vector). Recurses into seqable children, including vectors and maps.

   Counting policy: a throw-shaped form is recorded once. We then stop
   recursing into its children — `(throw (ex-info ...))` is one site, not
   two. Standalone `(ex-info ...)` outside of a `throw` still counts on
   its own. `(comment ...)` blocks are skipped entirely.

   `boundary?` is computed once at the file level — when true, the file
   yields no violations regardless of contents."
  ([acc! file-path boundary? form]
   (visit-form acc! file-path boundary? {} form))
  ([acc! file-path boundary? context form]
  (cond
    boundary?
    acc!

    (seq? form)
    (let [head (first form)
          kind (classify/throw-shaped-form? head)]
      (cond
        (and (symbol? head) (contains? classify/skip-walk-heads head))
        acc!

        kind
        (conj! acc! (reader/->violation-record file-path form
                                               (classify/classify-throw-site form context)
                                               kind))

        :else
        (let [child-context (form-context context form)]
          (reduce (fn [a child]
                    (visit-form a file-path boundary? child-context child))
                  acc!
                  form))))

    (or (vector? form) (set? form))
    (reduce (fn [a child] (visit-form a file-path boundary? context child))
            acc!
            form)

    (map? form)
    (reduce (fn [a [k v]]
              (-> a
                  (visit-form file-path boundary? context k)
                  (visit-form file-path boundary? context v)))
            acc!
            form)

    :else
    acc!)))

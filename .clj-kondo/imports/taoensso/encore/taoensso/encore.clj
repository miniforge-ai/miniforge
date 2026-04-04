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

(ns taoensso.encore
  "I don't personally use clj-kondo, so these hooks are
  kindly authored and maintained by contributors.
  PRs very welcome! - Peter Taoussanis"
  (:refer-clojure :exclude [defonce])
  (:require
   [clj-kondo.hooks-api :as hooks]))

(defn defalias
  [{:keys [node]}]
  (let [[sym-raw src-raw] (rest (:children node))
        src (or src-raw sym-raw)
        sym (if src-raw sym-raw (symbol (name (hooks/sexpr src))))]
    {:node
     (with-meta
       (hooks/list-node
         [(hooks/token-node 'def)
          (hooks/token-node (hooks/sexpr sym))
          (hooks/token-node (hooks/sexpr src))])
       (meta src))}))

(defn defn-cached
  [{:keys [node]}]
  (let [[sym _opts binding-vec & body] (rest (:children node))]
    {:node
     (hooks/list-node
       (list
         (hooks/token-node 'def)
         sym
         (hooks/list-node
           (list*
             (hooks/token-node 'fn)
             binding-vec
             body))))}))

(defn defonce
  [{:keys [node]}]
  ;; args = [sym doc-string? attr-map? init-expr]
  (let [[sym & args] (rest (:children node))
        [doc-string args]    (if (and (hooks/string-node? (first args)) (next args)) [(hooks/sexpr (first args)) (next  args)] [nil        args])
        [attr-map init-expr] (if (and (hooks/map-node?    (first args)) (next args)) [(hooks/sexpr (first args)) (fnext args)] [nil (first args)])

        attr-map (if doc-string (assoc attr-map :doc doc-string) attr-map)
        sym+meta (if attr-map (with-meta sym attr-map) sym)
        rewritten
        (hooks/list-node
          [(hooks/token-node 'clojure.core/defonce)
           sym+meta
           init-expr])]

    {:node rewritten}))

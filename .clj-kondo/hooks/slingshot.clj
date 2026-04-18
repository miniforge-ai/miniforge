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

(ns hooks.slingshot
  "clj-kondo hook for slingshot/try+.

   Slingshot's `try+` introduces an implicit `&throw-context` binding inside
   `catch` clauses. Out of the box, clj-kondo doesn't know about it and flags
   the symbol as unresolved, along with the conventional `_` ignore-binding
   that slingshot tolerates in `(catch Object _ …)` forms.

   This hook rewrites `try+` into an equivalent `try` whose catch bodies are
   wrapped in a `let` that pre-defines `&throw-context` (and `_` where the
   catch clause wrote `_` as its binding). The kondo analyzer then sees the
   bindings as resolved."
  (:require
   [clj-kondo.hooks-api :as api]))

(defn- wrap-catch-body
  "Given a catch clause `(catch EXC BINDING body...)`, produce
   `(catch EXC gensym-e (let [&throw-context nil, BINDING gensym-e] body...))`.

   The original BINDING is preserved so downstream name usage still resolves;
   the `&throw-context` binding is declared so kondo recognises it anywhere
   within the catch body."
  [catch-form]
  (let [[catch-sym exc-class binding & body] (:children catch-form)
        gensym-e (api/token-node (gensym "e_"))
        let-bindings (api/vector-node
                      [(api/token-node '&throw-context) (api/token-node 'nil)
                       binding gensym-e])
        let-body (api/list-node
                  (into [(api/token-node 'let) let-bindings] body))]
    (api/list-node [catch-sym exc-class gensym-e let-body])))

(defn- rewrite-child
  [c]
  (if (and (api/list-node? c)
           (some-> c :children first :value (= 'catch)))
    (wrap-catch-body c)
    c))

(defn try+
  "Rewrite (try+ body... (catch EXC BINDING body...) ...) → (try body... (catch …rewritten)…)."
  [{:keys [node]}]
  (let [children   (rest (:children node))
        rewritten  (mapv rewrite-child children)]
    {:node (api/list-node
            (into [(api/token-node 'try)] rewritten))}))

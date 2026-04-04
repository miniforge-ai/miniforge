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

(ns hiccup.hooks
  (:require [clj-kondo.hooks-api :as api]
            [clojure.set :as set]))

;; See https://github.com/clj-kondo/clj-kondo/blob/master/doc/hooks.md

(defn- parse-defn [elems]
  (let [[fhead fbody] (split-with #(not (or (api/vector-node? %)
                                            (api/list-node? %)))
                                  elems)
        arities (if (api/vector-node? (first fbody))
                  (list (api/list-node fbody))
                  fbody)]
    [fhead arities]))

(defn- count-args [arity]
  (let [args (first (api/sexpr arity))]
    (if (= '& (fnext (reverse args)))
      true ; unbounded args
      (count args))))

(defn- dummy-arity [arg-count]
  (api/list-node
   (list
    (api/vector-node
     (vec (repeat arg-count (api/token-node '_)))))))

(defn defelem [{:keys [node]}]
  (let [[_ & rest] (:children node)
        [fhead arities] (parse-defn rest)
        arg-counts (set (filter number? (map count-args arities)))
        dummy-arg-counts (set/difference (set (map inc arg-counts)) arg-counts)
        dummy-arities (for [n dummy-arg-counts] (dummy-arity n))]
    {:node
     (api/list-node
      (list*
       (api/token-node 'clojure.core/defn)
       (concat fhead arities dummy-arities)))}))

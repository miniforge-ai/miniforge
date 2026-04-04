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

(ns hooks.artifact-session
  (:require [clj-kondo.hooks-api :as api]))

(defn with-artifact-session
  "Expands (with-artifact-session [session] body...) to (let [session nil] body...)
   so clj-kondo can resolve the binding."
  [{:keys [node]}]
  (let [children (rest (:children node))
        binding-vec (first children)
        session-sym (first (:children binding-vec))
        body (rest children)]
    {:node (api/list-node
            (list* (api/token-node 'let)
                   (api/vector-node [session-sym (api/token-node nil)])
                   body))}))

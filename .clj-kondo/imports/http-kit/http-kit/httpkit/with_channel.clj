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

(ns httpkit.with-channel
  (:require [clj-kondo.hooks-api :as api]))

(defn with-channel [{node :node}]
  (let [[request channel & body] (rest (:children node))]
    (when-not (and request     channel) (throw (ex-info "No request or channel provided" {})))
    (when-not (api/token-node? channel) (throw (ex-info "Missing channel argument" {})))
    (let [new-node
          (api/list-node
            (list*
              (api/token-node 'let)
              (api/vector-node [channel (api/vector-node [])])
              request
              body))]

      {:node new-node})))

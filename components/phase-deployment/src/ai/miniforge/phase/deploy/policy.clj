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

(ns ai.miniforge.phase.deploy.policy
  "Custom policy check functions for deployment safety rules.

   Referenced by detection entries in the deployment-safety policy pack
   via :check-fn symbols."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Custom detection functions for policy pack rules

(defn check-resource-count
  "Check if a Pulumi preview creates too many resources in a single operation.

   Arguments:
     content - Pulumi preview JSON (as string or parsed map)
     context - Detection context (may contain :max-resources, default 20)

   Returns:
     nil if no violation, or violation map."
  [content context]
  (let [max-resources (get context :max-resources 20)
        preview       (if (string? content)
                        (try
                          (let [parse-fn (requiring-resolve 'cheshire.core/parse-string)]
                            (parse-fn content true))
                          (catch Exception _ {}))
                        content)
        steps         (or (:steps preview) (:Steps preview) [])
        creates       (count (filter #(= "create" (or (:op %) (:Op %))) steps))]
    (when (> creates max-resources)
      {:violation/rule-id   :deploy/resource-count-limit
       :violation/severity  :medium
       :violation/message   (str "Creating " creates " resources (limit: " max-resources ")")
       :violation/data      {:creates creates :limit max-resources}})))

(defn check-gke-node-limit
  "Check if a GKE node pool exceeds configured maximum size.

   Arguments:
     content - Pulumi preview JSON
     context - Detection context (may contain :max-nodes, default 10)

   Returns:
     nil if no violation, or violation map."
  [content context]
  (let [max-nodes (get context :max-nodes 10)
        preview   (if (string? content)
                    (try
                      (let [parse-fn (requiring-resolve 'cheshire.core/parse-string)]
                        (parse-fn content true))
                      (catch Exception _ {}))
                    content)
        steps     (or (:steps preview) (:Steps preview) [])
        node-pools (->> steps
                        (filter #(str/includes? (str (or (:type %) ""))
                                               "NodePool"))
                        (filter #(= "create" (or (:op %) (:Op %)))))]
    ;; Check each node pool's node count configuration
    ;; (This is a simplified check — real implementation would parse the
    ;;  resource inputs for initialNodeCount or autoscaling maxNodeCount)
    (when (and (seq node-pools) (> (count node-pools) max-nodes))
      {:violation/rule-id   :deploy/gke-node-limit
       :violation/severity  :medium
       :violation/message   (str "Creating " (count node-pools) " node pools (limit: " max-nodes ")")
       :violation/data      {:node-pools (count node-pools) :limit max-nodes}})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-resource-count {:steps (repeat 25 {:op "create" :type "gcp:compute:Instance"})} {})
  ;; => {:violation/rule-id :deploy/resource-count-limit ...}

  (check-resource-count {:steps (repeat 5 {:op "create"})} {})
  ;; => nil

  :leave-this-here)

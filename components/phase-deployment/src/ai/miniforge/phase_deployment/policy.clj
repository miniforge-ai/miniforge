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

(ns ai.miniforge.phase-deployment.policy
  "Custom policy check functions for deployment safety rules.

   Referenced by detection entries in the deployment-safety policy pack
  via :check-fn symbols."
  (:require [ai.miniforge.phase-deployment.messages :as msg]
            [ai.miniforge.schema.interface :as schema]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Deployment policy pack (supplied to the :policy-pack gate)

(def ^:private deployment-safety-pack-resource
  "Classpath location of the shipped deployment-safety policy pack."
  "packs/deployment-safety/pack.edn")

(def ^:private deployment-safety-pack
  "Memoized deployment-safety pack — read once per JVM (the classpath is stable
   for a process). nil only when the resource is absent; a present-but-malformed
   pack throws on deref, which the provision enter surfaces as a phase failure
   rather than a silent skip."
  (delay
    (when-let [res (io/resource deployment-safety-pack-resource)]
      (edn/read-string (slurp res)))))

(defn deployment-policy-packs
  "The policy packs the provision gate evaluates — the shipped deployment-safety
   pack. Empty when the pack resource is absent, in which case the :policy-pack
   gate fails closed (no deploy without deployment policy)."
  []
  (if-let [pack @deployment-safety-pack] [pack] []))

;------------------------------------------------------------------------------ Layer 0
;; Preview parsing + violation helpers

(def PolicyViolation
  [:map
   [:violation/rule-id keyword?]
   [:violation/severity keyword?]
   [:violation/message :string]
   [:violation/data map?]])

(defn- validate-violation!
  [violation]
  (schema/validate PolicyViolation violation))

(defn- parse-preview
  [content]
  (if (string? content)
    (try
      (json/parse-string content true)
      (catch Exception _ {}))
    content))

(defn- preview-steps
  [content]
  (let [preview (parse-preview content)]
    (if-some [steps (or (get preview :steps)
                        (get preview :Steps))]
      steps
      [])))

(defn- created-steps
  [steps]
  (filter #(= "create" (or (get % :op) (get % :Op))) steps))

(defn- violation
  [rule-id severity message-key params data]
  (validate-violation!
   {:violation/rule-id   rule-id
    :violation/severity  severity
    :violation/message   (msg/t message-key params)
    :violation/data      data}))

;------------------------------------------------------------------------------ Layer 1
;; Custom detection functions for policy pack rules

(defn check-resource-count
  "Check if a Pulumi preview creates too many resources in a single operation."
  [content context]
  (let [max-resources (get context :max-resources 20)
        creates       (count (created-steps (preview-steps content)))]
    (when (> creates max-resources)
      (violation :deploy/resource-count-limit
                 :medium
                 :policy/resource-count-limit
                 {:creates creates
                  :limit max-resources}
                 {:creates creates
                  :limit max-resources}))))

(defn check-gke-node-limit
  "Check if a GKE node pool exceeds configured maximum size."
  [content context]
  (let [max-nodes  (get context :max-nodes 10)
        node-pools (->> (preview-steps content)
                        created-steps
                        (filter #(str/includes? (str (get % :type "")) "NodePool")))]
    (when (> (count node-pools) max-nodes)
      (violation :deploy/gke-node-limit
                 :medium
                 :policy/gke-node-limit
                 {:node-pools (count node-pools)
                  :limit max-nodes}
                 {:node-pools (count node-pools)
                  :limit max-nodes}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (check-resource-count {:steps (repeat 25 {:op "create" :type "gcp:compute:Instance"})} {})
  (check-resource-count {:steps (repeat 5 {:op "create"})} {})

  :leave-this-here)

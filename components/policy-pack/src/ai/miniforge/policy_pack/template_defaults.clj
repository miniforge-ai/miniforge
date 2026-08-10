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
(ns ai.miniforge.policy-pack.template-defaults
  "Default prompt templates loaded from the pack's bundled EDN resource.

   Layer 0: default-templates-resource (classpath resource path)
   Layer 1: loaded-defaults (delay that reads + parses the EDN resource)
   Layer 2: default-template (keyed lookup into the loaded defaults)

   Split out of `ai.miniforge.policy-pack.prompt-template` (rule 210:
   the parent namespace measured 6 real layers, max 3; this is the
   resource-loading concern, kept separate from template resolution
   and rendering)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private default-templates-resource
  "Classpath resource path for default prompt templates."
  "policy_pack/templates/defaults.edn")

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private loaded-defaults
  "Default templates loaded from classpath EDN resource. Delay for lazy loading."
  (delay
    (try
      (when-let [resource (io/resource default-templates-resource)]
        (edn/read-string (slurp resource)))
      (catch Exception _ {}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} default-template
  "Get a default template by key. Returns empty string if not found."
  [k]
  (get @loaded-defaults k ""))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (default-template :repair-prompt)
  (default-template :behavior-section)
  (default-template :knowledge-section)
  :leave-this-here)

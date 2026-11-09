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
(ns ai.miniforge.pipeline-config.rule-resolution
  "Quality-rule hydration: turn a quality pack's rule descriptors into live
   rules through a rule registry. A descriptor the registry cannot resolve
   stays as data, without a :rule/check-fn, so pipeline-runner preflight can
   name the missing rule.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} lookup-rule
  "The registered rule for a descriptor, or nil when the registry has none."
  [rule-registry {:rule/keys [type id] :as descriptor}]
  (case type
    :custom (get rule-registry id)
    ;; Built-in types: look up resolver fn by type keyword
    (when-let [resolver (get rule-registry type)]
      (resolver descriptor))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-rule
  "The registered rule for a descriptor, or the descriptor unchanged when the
   registry cannot resolve it."
  [rule-registry descriptor]
  (or (lookup-rule rule-registry descriptor) descriptor))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-quality-pack
  "Hydrate a quality pack's rule descriptors into live rule functions."
  [rule-registry pack]
  (update pack :pack/rules
          (fn [rules] (mapv #(resolve-rule rule-registry %) rules))))

(comment
  (resolve-quality-pack {} {:pack/id :p :pack/rules [{:rule/type :custom :rule/id :missing}]}))

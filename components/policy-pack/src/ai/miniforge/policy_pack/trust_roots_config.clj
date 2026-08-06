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
(ns ai.miniforge.policy-pack.trust-roots-config
  "Loads the trusted-publisher store (N4 §8.2.1) from configuration.

   Two sources, merged by identifier with the operator's entry winning:
   the shipped classpath resource, which ships empty so a deployment that
   configures nothing trusts nothing, and ~/.miniforge/config.edn — the
   out-of-band, auditable store §8.2.1 asks for.

   Layer 0: Config paths and schema
   Layer 1: Loading"
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.messages.interface :as messages]
   [ai.miniforge.policy-pack.schema-validation :as schema-validation]
   [ai.miniforge.policy-pack.trust-roots :as trust-roots]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private t
  (messages/create-translator "config/policy-pack/messages/system.edn"
                              :policy-pack/system))

(def ^{:stratum 0} trust-roots-resource
  "Classpath path of the shipped trust-root defaults."
  "config/policy-pack/trust-roots.edn")

(def ^{:stratum 0} user-config-path
  "Key path into ~/.miniforge/config.edn holding operator trust roots."
  [:policy-pack :trust-roots])

(def ^{:stratum 0} TrustRootConfig
  "The trust-root config file and the user-config block under
   `user-config-path`. An empty publisher vector trusts nobody."
  [:map
   [:trust-roots/publishers [:vector trust-roots/TrustRoot]]])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} load-trust-roots
  "Load the configured store: shipped defaults overlaid with the operator's
   entries. The 1-arity takes an already-loaded user config, for tests and
   for callers whose config is not at the default path.

   Throws when either source is malformed — trusting the wrong set of
   publishers is not a condition to fail open on, and config-as-data (007)
   puts that check at load rather than at each verification."
  ([]
   (load-trust-roots (config/load-user-config)))
  ([user-config]
   (let [shipped  (config/load-config-resource trust-roots-resource
                                               [:trust-roots/publishers])
         operator (get-in user-config user-config-path)]
     (doseq [[source cfg] [[trust-roots-resource shipped]
                           [user-config-path operator]]
             :when (some? cfg)]
       (let [{:keys [valid? errors]} (schema-validation/validate TrustRootConfig cfg)]
         (when-not valid?
           (throw (ex-info (t :trust-roots/invalid-config {:source source})
                           {:policy-pack/config-source source
                            :policy-pack/config-errors errors})))))
     (trust-roots/->store (:trust-roots/publishers shipped)
                          (:trust-roots/publishers operator)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; The shipped store trusts nobody
  (load-trust-roots)
  ;; => {}

  :leave-this-here)

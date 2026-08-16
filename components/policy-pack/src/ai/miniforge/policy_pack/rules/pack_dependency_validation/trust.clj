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
(ns ai.miniforge.policy-pack.rules.pack-dependency-validation.trust
  "Trust-level constraint checks for pack dependency validation (N4
   §2.4.2): tainted-dependency and untrusted-instruction-authority
   escalation detection. Split out of
   `ai.miniforge.policy-pack.rules.pack-dependency-validation` (rule
   210, SL003 split train, slice 2/3) — a self-contained trust-check
   chain that `detect-trust-violations` sits atop.

   Layer 0: tainted-dependency?, untrusted-instruction-escalation?
     predicates
   Layer 1: check-dependency-trust (over both Layer 0 predicates)
   Layer 2: detect-trust-violations (over check-dependency-trust)")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} tainted-dependency?
  "True when a non-tainted pack depends on a tainted pack."
  [pack dep-pack]
  (and dep-pack
       (= :tainted (get dep-pack :pack/trust-level))
       (not= :tainted (get pack :pack/trust-level))))

(defn- ^{:stratum 0} untrusted-instruction-escalation?
  "True when an untrusted pack with instruction authority depends on a trusted pack."
  [pack dep-pack]
  (and dep-pack
       (= :untrusted (get pack :pack/trust-level :untrusted))
       (= :authority/instruction (get pack :pack/authority :authority/data))
       (= :trusted (get dep-pack :pack/trust-level))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} check-dependency-trust
  "Check a single pack→dependency pair for trust violations.
   Returns a violation map or nil."
  [pack-id pack dep-id dep-pack]
  (cond
    (tainted-dependency? pack dep-pack)
    {:type       :trust-violation
     :pack-id    pack-id
     :dependency dep-id
     :message    (str "Tainted dependency " dep-id
                      " cannot be used by non-tainted pack " pack-id)}

    (untrusted-instruction-escalation? pack dep-pack)
    {:type       :trust-violation
     :pack-id    pack-id
     :dependency dep-id
     :message    (str "Untrusted pack " pack-id
                      " with instruction authority cannot depend on "
                      "trusted pack " dep-id)}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} detect-trust-violations
  "Detect trust level constraint violations per N4 §2.4.2.

   Checks:
   1. Untrusted pack with instruction authority depending on trusted pack
   2. Tainted pack as dependency of any non-tainted pack

   Returns vector of violation maps."
  [_graph by-id]
  (->> (vals by-id)
       (mapcat (fn [pack]
                 (let [pack-id (get pack :pack/id)]
                   (->> (get pack :pack/extends [])
                        (keep (fn [dep]
                                (let [dep-id (get dep :pack-id)]
                                  (check-dependency-trust
                                   pack-id pack dep-id (get by-id dep-id)))))))))
       vec))

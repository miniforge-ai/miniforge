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
(ns ai.miniforge.policy-pack.compiler.rule
  "Compile one enabled policy rule into an executable N4 check entry.

   Split out of `ai.miniforge.policy-pack.compiler` (rule 210: the
   combined namespace measured 8 real layers, max 3). This namespace
   builds the executable check-fn on top of
   `ai.miniforge.policy-pack.compiler.check`'s detector resolution and
   result/violation construction; the parent namespace builds
   pack-level compilation (`compile-pack-checks`, `compile-pack`) on
   top of this namespace's `compile-rule-check`.

   Layer 0: compile-check-fn
   Layer 1: compile-rule-check"
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.policy-pack.compiler.check :as check]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} compile-check-fn
  [rule detector]
  (fn [artifacts context]
    (try
      (if (and (= :semantic detector)
               (not (check/semantic-context-ready? context)))
        (check/check-result rule detector [(check/missing-semantic-wiring-violation rule)])
        (check/check-result rule detector
                            (check/detect-rule-violations rule detector artifacts context)))
      (catch Exception e
        (check/check-result rule detector [(check/exception-violation rule detector e)])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} compile-rule-check
  "Compile one enabled rule into an executable N4 check entry.

   Returns:
     {:rule rule
      :detector <mechanism>
      :check-fn (fn [artifacts context] -> {:passed? bool
                                            :violations [...]
                                            :metadata {...}})}

   Returns an :invalid-input anomaly when the rule cannot bind to any
   mechanism. Disabled rules are not compiled."
  [rule]
  (let [detector (check/resolve-detector rule)]
    (cond
      (not (check/rule-enabled? rule))
      (anomaly/anomaly :invalid-input
                       "Disabled rules are not compiled into checks"
                       {:rule/id (:rule/id rule)})

      (= :none detector)
      (anomaly/anomaly :invalid-input
                       "Enabled policy rule binds to no detection mechanism"
                       {:rule/id (:rule/id rule)})

      :else
      {:rule     rule
       :detector detector
       :check-fn (compile-check-fn rule detector)})))

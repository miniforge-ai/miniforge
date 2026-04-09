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

(ns ai.miniforge.policy-pack.standard-packs-test
  "Tests for the 11 standard reference policy packs (N4 §5).

   Validates:
   - All packs load from classpath resources
   - All packs conform to PackManifest schema
   - All packs have taxonomy-ref pointing to miniforge/dewey
   - Rule counts match spec"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(def ^:private pack-specs
  "Expected pack metadata for each reference pack."
  [{:file "foundations-1.0.0.pack.edn"     :id "miniforge/foundations"            :min-rules 4}
   {:file "terraform-aws-1.0.0.pack.edn"  :id "miniforge/terraform-aws"          :min-rules 5}
   {:file "kubernetes-1.0.0.pack.edn"      :id "miniforge/kubernetes"             :min-rules 5}
   {:file "pack-trust-1.0.0.pack.edn"     :id "miniforge/pack-trust"             :min-rules 3}
   {:file "task-scope-1.0.0.pack.edn"     :id "miniforge/task-scope"             :min-rules 5}
   {:file "capability-grant-1.0.0.pack.edn" :id "miniforge/capability-grant"     :min-rules 4}
   {:file "pack-high-risk-action-1.0.0.pack.edn" :id "miniforge/high-risk-action" :min-rules 3}
   {:file "external-pr-evaluation-1.0.0.pack.edn" :id "miniforge/external-pr-evaluation" :min-rules 3}
   {:file "opsv-governance-1.0.0.pack.edn" :id "miniforge/opsv-governance"       :min-rules 6}
   {:file "control-action-governance-1.0.0.pack.edn" :id "miniforge/control-action-governance" :min-rules 4}
   {:file "data-foundry-quality-1.0.0.pack.edn" :id "miniforge/data-foundry-quality" :min-rules 11}])

(defn- load-pack-resource [filename]
  (let [path (str "policy_pack/packs/" filename)]
    (when-let [resource (io/resource path)]
      (edn/read-string (slurp resource)))))

(deftest all-standard-packs-load-test
  (doseq [{:keys [file id min-rules]} pack-specs]
    (testing (str "Pack " file " loads and validates")
      (let [pack (load-pack-resource file)]
        (is (some? pack) (str "Resource not found: " file))
        (when pack
          (is (= id (:pack/id pack)) (str file " has correct ID"))
          (is (>= (count (:pack/rules pack)) min-rules)
              (str file " has at least " min-rules " rules"))
          (is (= :miniforge/dewey (get-in pack [:pack/taxonomy-ref :taxonomy/id]))
              (str file " references miniforge/dewey taxonomy")))))))

(deftest all-packs-have-valid-rules-test
  (doseq [{:keys [file]} pack-specs]
    (testing (str "Rules in " file " have required fields")
      (when-let [pack (load-pack-resource file)]
        (doseq [rule (:pack/rules pack)]
          (is (keyword? (:rule/id rule)) (str "Rule in " file " has keyword :rule/id"))
          (is (string? (:rule/title rule)) (str "Rule " (:rule/id rule) " has title"))
          (is (#{:critical :major :minor :info} (:rule/severity rule))
              (str "Rule " (:rule/id rule) " has valid severity"))
          (is (map? (:rule/detection rule)) (str "Rule " (:rule/id rule) " has detection"))
          (is (map? (:rule/enforcement rule)) (str "Rule " (:rule/id rule) " has enforcement")))))))

(deftest total-reference-rules-test
  (testing "total reference rules across all packs is at least 53"
    (let [total (->> pack-specs
                     (keep (fn [{:keys [file]}]
                             (when-let [pack (load-pack-resource file)]
                               (count (:pack/rules pack)))))
                     (reduce + 0))]
      (is (>= total 53) (str "Expected at least 53 rules, got " total)))))

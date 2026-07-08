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

(ns ai.miniforge.policy-pack.builtin-detectors-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.policy-pack.compiler :as compiler]
   [ai.miniforge.policy-pack.detection :as detection]
   ;; loaded for its registration side effect
   [ai.miniforge.policy-pack.builtin-detectors]))

(defn- tf-rule [id]
  (->> (edn/read-string (slurp (io/resource "policy_pack/packs/terraform-aws-1.0.0.pack.edn")))
       :pack/rules
       (filter #(= id (:rule/id %)))
       first))

(defn- fires? [rule content]
  (boolean (detection/detect-custom rule {:artifact/content content} {})))

(deftest approved-instance-types-detector-test
  (let [rule (tf-rule :tf-aws/approved-instance-types)]
    (testing "the rule is a registered :custom detector (binds :custom, not the judge)"
      (is (some? rule))
      (is (= :custom (compiler/resolve-detector rule))))
    (testing "instance_type literals are checked against the approved families"
      (is (not (fires? rule "instance_type = \"t3.micro\"")) "approved family passes")
      (is (fires? rule "instance_type = \"p4d.24xlarge\"") "unapproved family fires")
      (is (not (fires? rule "instance_type = var.size")) "variable ref is not evaluated"))
    (testing "the approved families are DATA in :detector-config, not a regex —
              changing the list changes the outcome"
      (let [p4d-only (assoc-in rule [:rule/detection :detector-config :approved-families] ["p4d"])]
        (is (not (fires? p4d-only "instance_type = \"p4d.24xlarge\"")) "now approved")
        (is (fires? p4d-only "instance_type = \"t3.micro\"") "now unapproved")))
    (testing "the violation carries the rule's enforcement message"
      (let [v (detection/detect-custom rule {:artifact/content "instance_type = \"p4d.24xlarge\""} {})]
        (is (= :tf-aws/approved-instance-types (:rule-id v)))
        (is (= ["p4d.24xlarge"] (:matches v)))
        (is (re-find #"approved list" (:message v)))))))

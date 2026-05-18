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

(ns ai.miniforge.pr-lifecycle.label-actions-config-test
  "Structural validity tests for the M1 PR-label-actions registry EDN.

   The watcher (M2) consumes this file. Pinning the shape here means a
   typo in the registry surfaces at test time, not at first-merge time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]))

(def ^:private config-path
  "config/pr-lifecycle/label-actions.edn")

(defn- load-registry []
  (-> (io/resource config-path)
      slurp
      edn/read-string
      :pr-label-actions/registry))

(deftest registry-resource-loads
  (testing "the registry resource exists on the classpath and parses as EDN"
    (let [r (io/resource config-path)]
      (is (some? r)
          (str "Registry resource missing at " config-path)))
    (let [registry (load-registry)]
      (is (map? registry) "Registry must be a map keyed by label string"))))

(deftest registry-keys-are-strings
  (testing "label keys are STRINGS (GitHub-native), never keywords or symbols"
    (let [registry (load-registry)]
      (doseq [[k _] registry]
        (is (string? k)
            (str "Label registry key " (pr-str k)
                 " must be a string — GitHub labels are not keywords"))))))

(deftest dogfood-fix-entry-shape
  (testing "the seed dogfood-fix entry has the canonical M1 shape"
    (let [entry (get (load-registry) "dogfood-fix")]
      (is (some? entry) "dogfood-fix entry must be present in the registry")
      (is (= :rebase-and-resume (:action entry))
          ":action must be :rebase-and-resume per the design plan")
      (is (string? (:description entry))
          ":description must be a human-readable string")
      (is (= :attention-required (:on-conflict entry))
          ":on-conflict must route to :attention-required (no silent loss)")
      (let [applies (:applies-when entry)]
        (is (map? applies))
        (is (= true (:workflow.base-sha/not-ancestor-of-merge applies))
            ":applies-when must declare the not-ancestor-of-merge predicate")))))

(deftest every-entry-has-required-keys
  (testing "every registry entry declares :action, :description, :on-conflict, :applies-when"
    (let [registry (load-registry)
          required #{:action :description :on-conflict :applies-when}]
      (doseq [[label entry] registry]
        (let [missing (set/difference required (set (keys entry)))]
          (is (empty? missing)
              (str "Entry " (pr-str label)
                   " is missing required keys: " (pr-str missing))))))))

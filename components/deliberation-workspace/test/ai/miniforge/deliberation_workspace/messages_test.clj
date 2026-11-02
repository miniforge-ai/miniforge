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
(ns ai.miniforge.deliberation-workspace.messages-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} catalog-resource
  "config/deliberation-workspace/messages/system.edn")

(defn- ^{:stratum 0} source-dir []
  (-> (io/resource "ai/miniforge/deliberation_workspace/validation.clj")
      io/file
      .getParentFile))

(defn- ^{:stratum 0} catalog-keys []
  (-> (io/resource catalog-resource) slurp edn/read-string
      :deliberation-workspace/system keys set))

(defn- ^{:stratum 0} referenced-keys
  "Every key a source file passes to `messages/system-t`."
  []
  (->> (file-seq (source-dir))
       (filter #(.endsWith (.getName %) ".clj"))
       (mapcat #(re-seq #"system-t\s+:([\w-]+/[\w-]+)" (slurp %)))
       (map (comp keyword second))
       set))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-catalog-covers-exactly-the-referenced-keys
  (let [referenced (referenced-keys)]
    (testing "the scan found the component's anomaly messages"
      (is (< 10 (count referenced))))
    (testing "every referenced key has catalog text"
      (is (empty? (remove (catalog-keys) referenced))))
    (testing "the catalog holds no unreferenced entry"
      (is (empty? (remove referenced (catalog-keys)))))))

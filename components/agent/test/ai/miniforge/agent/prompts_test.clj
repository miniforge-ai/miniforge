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

(ns ai.miniforge.agent.prompts-test
  (:require
   [ai.miniforge.agent.prompts :as prompts]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

(defn- fault-anomaly
  [f]
  (try+
    (f)
    (catch [:anomaly/category :anomalies/fault] m
      m)))

(deftest missing-prompt-resource-carries-invalid-config-marker
  (testing "load-prompt reports a missing classpath prompt as invalid config"
    (with-redefs [io/resource (constantly nil)]
      (let [anomaly (fault-anomaly #(prompts/load-prompt :missing-agent))]
        (is (= :anomalies/fault (:anomaly/category anomaly)))
        (is (= :invalid-config (:config/error anomaly)))
        (is (= :missing-agent (:agent-type anomaly)))
        (is (= "prompts/missing-agent.edn" (:resource-path anomaly)))))))

(deftest missing-prompt-data-resource-carries-invalid-config-marker
  (testing "load-prompt-data reports a missing classpath prompt as invalid config"
    (with-redefs [io/resource (constantly nil)]
      (let [anomaly (fault-anomaly #(prompts/load-prompt-data :missing-agent))]
        (is (= :anomalies/fault (:anomaly/category anomaly)))
        (is (= :invalid-config (:config/error anomaly)))
        (is (= :missing-agent (:agent-type anomaly)))
        (is (= "prompts/missing-agent.edn" (:resource-path anomaly)))))))

(deftest non-map-prompt-data-carries-invalid-config-marker
  (testing "load-prompt reports malformed prompt EDN without bypassing anomaly data"
    (let [prompt-file (java.io.File/createTempFile "miniforge-prompt" ".edn")]
      (.deleteOnExit prompt-file)
      (spit prompt-file "[]")
      (with-redefs [io/resource (constantly (.toURL (.toURI prompt-file)))]
        (let [anomaly (fault-anomaly #(prompts/load-prompt :malformed-agent))]
          (is (= :anomalies/fault (:anomaly/category anomaly)))
          (is (= :invalid-config (:config/error anomaly)))
          (is (= :malformed-agent (:agent-type anomaly)))
          (is (= "prompts/malformed-agent.edn" (:resource-path anomaly)))
          (is (= "class clojure.lang.PersistentVector"
                 (:prompt-data/type anomaly)))
          (is (nil? (:keys anomaly))))))))

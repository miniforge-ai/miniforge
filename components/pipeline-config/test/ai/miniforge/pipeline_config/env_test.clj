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
(ns ai.miniforge.pipeline-config.env-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline-config.env :as env]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-config-file
  "A fresh temp file holding `content`, deleted when the JVM exits."
  [content]
  (let [f (java.io.File/createTempFile "env-config-" ".edn")]
    (.deleteOnExit f)
    (spit f content)
    f))

(deftest ^{:stratum 0} load-env-config-missing-file-test
  (testing "an absent file is reported as not found"
    (let [path (str (io/file (System/getProperty "java.io.tmpdir")
                             (str "absent-" (random-uuid) ".edn")))
          result (env/load-env-config path)]
      (is (schema/failed? result))
      (is (str/includes? (str (:error result)) "not found"))
      (is (str/includes? (str (:error result)) path)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} load-env-config-reads-an-existing-file-test
  (testing "an existing, readable file loads as success"
    (let [f (temp-config-file "{:env/name \"development\"}")
          result (env/load-env-config (str f))]
      (is (schema/succeeded? result))
      (is (= {:env/name "development"} (:env-config result))))))

(deftest ^{:stratum 1} load-env-config-unreadable-file-test
  (testing "a file that exists but cannot be read is not reported as missing"
    (let [f (temp-config-file "{:env/name \"development\"}")]
      (.setReadable f false false)
      (try
        ;; Root reads any file regardless of mode; nothing to assert there.
        (when-not (.canRead f)
          (let [result (env/load-env-config (str f))]
            (is (schema/failed? result))
            (is (not (str/includes? (str (:error result)) "not found"))
                "a permission error must keep its own message")))
        (finally
          (.setReadable f true false))))))

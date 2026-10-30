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
(ns ai.miniforge.config.execution-test
  "[:execution :require-governed?] fails closed: only false or an absent
   value leaves local execution allowed."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.interface :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-value
  "A user config with `v` at [:execution :require-governed?]."
  [v]
  {:execution {:require-governed? v}})

(deftest ^{:stratum 0} unreadable-user-config-requires-governed-test
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "exec-config-" (random-uuid))) .mkdirs)
        at  (fn [text] (let [f (io/file dir (str (random-uuid) ".edn"))] (spit f text) (str f)))]
    (try
      (testing "no file: the defaults decide (not required)"
        (is (false? (sut/user-config-requires-governed? (str (io/file dir "absent.edn"))))))
      (testing "a file that sets the key requires it; one that does not, does not"
        (is (true? (sut/user-config-requires-governed? (at "{:execution {:require-governed? true}}"))))
        (is (false? (sut/user-config-requires-governed? (at "{:llm {:backend :claude}}")))))
      (testing "a file that does not parse, or is not exactly one map, requires it"
        (doseq [text ["{:execution {:require-governed? false" "[:not :a :map]" "" "nil"
                      "{} {:execution {:require-governed? true}}"]]
          (is (true? (sut/user-config-requires-governed? (at text))) (pr-str text))))
      (finally
        (run! #(.delete ^java.io.File %) (reverse (file-seq dir)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-required-fails-closed-test
  (testing "false, nil or no :execution section allows local runs"
    (is (false? (sut/governed-required? {})))
    (is (false? (sut/governed-required? (with-value nil))))
    (is (false? (sut/governed-required? (with-value false)))))
  (testing "true, and any malformed value, requires governed runs"
    (doseq [v [true "true" "yes" "false" 1 :yes]]
      (is (true? (sut/governed-required? (with-value v))) (pr-str v)))))

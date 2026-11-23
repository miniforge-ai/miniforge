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
(ns ai.miniforge.subprocess.config-test
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.subprocess.config :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private override-limit-bytes
  "A valid limit other than the default, to show the file's value wins."
  4096)

(defn- ^{:stratum 0} load-with
  "Load the capture config as if config/subprocess/capture.edn held `edn`."
  [edn]
  (with-redefs [config/load-config-resource (constantly edn)]
    (#'sut/load-capture-config)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} load-capture-config-test
  (testing "given a file without the key → the default limit"
    (is (= sut/default-max-captured-bytes
           (:subprocess/max-captured-bytes (load-with {})))))
  (testing "given a valid limit → that limit"
    (is (= override-limit-bytes
           (:subprocess/max-captured-bytes
            (load-with {:subprocess/max-captured-bytes override-limit-bytes})))))
  (testing "given a limit that is not a positive integer a buffer can hold → throws at load"
    (doseq [bad [0 "64m" (inc Integer/MAX_VALUE)]]
      (is (thrown? clojure.lang.ExceptionInfo
                   (load-with {:subprocess/max-captured-bytes bad}))
          (pr-str bad)))))

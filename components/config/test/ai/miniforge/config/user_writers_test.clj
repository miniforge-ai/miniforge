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
(ns ai.miniforge.config.user-writers-test
  "The config component has one way to write a config file: the locked,
   atomic writers in config.interface.file."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.config.interface]
   [ai.miniforge.config.interface.file]
   [ai.miniforge.config.user]))

;------------------------------------------------------------------------------ Layer 0

;; Writers that replaced the file with a plain spit, without the config lock
;; or an atomic rename, and with no callers.
(def ^{:stratum 0} ^:private unlocked-writers
  '[save-user-config init-user-config update-user-config reset-user-config
    edit-user-config write-edn-file])

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} no-unlocked-config-writer-test
  (testing "neither the interface nor config.user offers a writer that skips the lock"
    (doseq [ns-sym '[ai.miniforge.config.interface ai.miniforge.config.user]
            writer unlocked-writers]
      (is (nil? (ns-resolve ns-sym writer))
          (str ns-sym "/" writer " writes the config file without the lock"))))
  (testing "the locked writers are there"
    (is (some? (ns-resolve 'ai.miniforge.config.interface.file 'update-config!)))
    (is (some? (ns-resolve 'ai.miniforge.config.interface.file 'write-config!)))))

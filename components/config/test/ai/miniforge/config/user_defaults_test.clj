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

(ns ai.miniforge.config.user-defaults-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.config.user :as user]))

;------------------------------------------------------------------------------ Layer 0
;; Default config policy

(deftest default-config-prefers-gpt-execution-test
  (let [cfg config/default-config]
    (testing "default llm backend prefers codex"
      (is (= :codex (get-in cfg [:llm :backend])))
      (is (= "gpt-5.2-codex" (get-in cfg [:llm :model]))))

    (testing "default agent models use GPT for thinking, Claude for execution"
      (is (= "gpt-5.4" (get-in cfg [:agents :default-models :thinking])))
      (is (= "claude-sonnet-4-6" (get-in cfg [:agents :default-models :execution]))))

    (testing "default self-healing enables claude and codex failover"
      (is (= [:claude :codex] (get-in cfg [:self-healing :allowed-failover-backends]))))))

(deftest load-default-config-falls-back-to-edn-resource-test
  (let [orig-resource io/resource]
    (with-redefs [io/resource (fn [path]
                                (case path
                                  "config/default-user-config.edn" nil
                                  "config/default-user-config-fallback.edn"
                                  (orig-resource "config/default-user-config-fallback.edn")
                                  nil))]
      (let [cfg (user/load-default-config)]
        (is (= :codex (get-in cfg [:llm :backend])))
        (is (= "claude-sonnet-4-6" (get-in cfg [:agents :default-models :execution])))))))

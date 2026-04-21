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

(ns ai.miniforge.phase-deployment.provision-test
  (:require [ai.miniforge.phase-deployment.provision :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest resolve-provision-config-test
  (testing "workflow input overrides phase defaults and builds the shell env"
    (let [resolved (#'sut/resolve-provision-config
                    {:phase-config    {:stack-dir "/cfg"
                                       :stack "staging"
                                       :gcp-project "cfg-project"}
                     :execution/input {:stack-dir "/input"
                                       :gcp-project "input-project"}})]
      (is (= "/input" (:stack-dir resolved)))
      (is (= "staging" (:stack resolved)))
      (is (= "input-project" (:gcp-project resolved)))
      (is (= {"GOOGLE_PROJECT" "input-project"} (:env resolved))))))

(deftest analyze-preview-test
  (testing "preview analysis summarizes resource operations"
    (let [analysis (sut/analyze-preview {:steps [{:op "create" :type "bucket" :urn "urn:1"}
                                                 {:op "update" :type "svc" :urn "urn:2"}
                                                 {:op "create" :type "topic" :urn "urn:3"}]})]
      (is (= 2 (:creates analysis)))
      (is (= 1 (:updates analysis)))
      (is (= 0 (:deletes analysis)))
      (is (= ["create" "update" "create"]
             (mapv :action (:resources analysis)))))))

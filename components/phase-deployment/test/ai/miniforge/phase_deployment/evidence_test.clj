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

(ns ai.miniforge.phase-deployment.evidence-test
  (:require [ai.miniforge.phase-deployment.evidence :as sut]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Evidence tests

(deftest evidence-types-loaded-from-config-test
  (testing "evidence metadata is loaded from EDN config"
    (is (= :yaml (get-in sut/evidence-types [:evidence/rendered-manifests :format])))
    (is (= :workflow-start (get-in sut/evidence-types [:evidence/environment-metadata :captured])))))

(deftest create-environment-metadata-test
  (testing "environment metadata is validated and wrapped as evidence"
    (let [result (sut/create-environment-metadata {:gcp-project "proj"
                                                   :namespace "default"})]
      (is (= :evidence/environment-metadata (:evidence/type result)))
      (is (= "proj" (get-in result [:evidence/content :gcp-project]))))))

(deftest persist-bundle-writes-full-bundle-test
  (testing "bundle.edn persists the full bundle and manifest.edn persists the summary"
    (let [base-dir  (str (io/file (System/getProperty "java.io.tmpdir")
                                  (str "deploy-evidence-" (random-uuid))))
          workflow-id (random-uuid)
          items     [(sut/create-environment-metadata {:gcp-project "proj"})
                     (sut/create-evidence :evidence/rendered-manifests "kind: Deployment")
                     (sut/create-evidence :evidence/image-digests [{:image "img"}])]
          bundle    (sut/create-deployment-bundle workflow-id items)
          persisted (sut/persist-bundle! base-dir bundle)
          bundle-edn (edn/read-string (slurp (io/file (:bundle/path persisted) "bundle.edn")))
          manifest   (edn/read-string (slurp (io/file (:bundle/path persisted) "manifest.edn")))]
      (is (= 3 (count (:bundle/evidence bundle-edn))))
      (is (= "kind: Deployment" (get-in bundle-edn [:bundle/evidence 1 :evidence/content])))
      (is (= 3 (count (:evidence manifest))))
      (is (every? :evidence/path (:bundle/evidence bundle-edn))))))

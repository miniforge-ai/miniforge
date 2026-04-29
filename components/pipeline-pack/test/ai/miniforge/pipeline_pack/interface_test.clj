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

(ns ai.miniforge.pipeline-pack.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline-pack.interface :as pp]
            [clojure.java.io :as io]))

(def ^:private simple-pack-dir
  (-> (io/resource "test-packs/simple-pack/pack.edn")
      io/file .getParentFile .getPath))

(deftest interface-load-test
  (testing "Load pack via interface"
    (let [result (pp/load-pack simple-pack-dir)]
      (is (:success? result))
      (is (= "simple-pack" (get-in result [:pack :pack/manifest :pack/id]))))))

(deftest interface-registry-test
  (testing "Full registry workflow via interface"
    (let [result (pp/load-pack simple-pack-dir)
          pack (:pack result)
          reg (pp/create-registry)]
      (pp/register-pack! reg pack)
      (is (= 1 (pp/pack-count reg)))
      (is (= ["simple-pack"] (pp/list-pack-ids reg)))
      (is (some? (pp/get-pack reg "simple-pack")))

      (pp/unregister-pack! reg "simple-pack")
      (is (= 0 (pp/pack-count reg))))))

(deftest interface-trust-test
  (testing "Trust validation via interface"
    (let [result (pp/load-pack simple-pack-dir)
          pack (:pack result)]
      (is (:valid? (pp/validate-pack-trust pack))))))

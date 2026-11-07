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
(ns ai.miniforge.agent-environment.conformance-test
  (:require
   [ai.miniforge.agent-environment.fake :as fake]
   [ai.miniforge.agent-environment.interface :as env]
   [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failing-provider [] (env/fake-provider {:fake/fail-step :step/workload}))

(defn- ^{:stratum 0} outcomes-by-check
  [results]
  (into {} (map (juxt :check/id :check/outcome)) results))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-fake-provider-passes-the-contract-suite
  (let [results (env/run-conformance {:make-provider env/fake-provider
                                      :make-partial-provider failing-provider})]
    (is (seq results))
    (doseq [{:check/keys [id outcome detail]} results]
      (is (= :check/passed outcome) (str id " " detail)))))

(deftest ^{:stratum 1} test-a-check-without-its-fixture-is-unrun-not-passed
  (is (= :check/unrun (:check/partial-provisioning-receipts
                       (outcomes-by-check (env/run-conformance {:make-provider env/fake-provider}))))))

(deftest ^{:stratum 1} test-a-provider-that-cannot-provision-fails-the-suite
  (let [outcomes (outcomes-by-check (env/run-conformance {:make-provider failing-provider}))]
    (is (= :check/failed (:check/duplicate-acquire-returns-one-environment outcomes)))
    (is (= :check/failed (:check/stale-lease-refused outcomes)))
    (is (= :check/failed (:check/lifecycle-roundtrip outcomes)))))

(deftest ^{:stratum 1} test-the-suite-releases-what-it-acquires
  (let [provider (env/fake-provider)
        outcomes (outcomes-by-check (env/run-conformance {:make-provider (constantly provider)}))]
    (is (= :check/passed (:check/cleanup-confirmed outcomes)))
    (is (empty? (fake/live-namespaces provider)))))

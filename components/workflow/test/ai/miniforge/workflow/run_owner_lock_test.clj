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
(ns ai.miniforge.workflow.run-owner-lock-test
  "Recovery scenario C3: one live process per run. A second start of a
   running run (a resume, a launcher retry) is refused while the owner
   lock is held, in this process or another, and allowed once the owner
   is gone."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow.run-owner-lock :as owner]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} owner-lock-in-this-process-test
  (fs/with-temp-dir [root {:prefix "run-owner-"}]
    (let [id   (random-uuid)
          held (owner/acquire! root id)]
      (try
        (is (not (anomaly/anomaly? held)))
        (testing "a second claim is refused, naming the owner"
          (let [again (owner/acquire! root id)]
            (is (= :conflict (:anomaly/type again)))
            (is (= (.pid (java.lang.ProcessHandle/current))
                   (get-in again [:anomaly/data :run/owner :owner/pid])))))
        (testing "the probe reports the live owner"
          (is (= (str id) (:owner/run-id (owner/owner root id)))))
        (finally (owner/release! held)))
      (testing "released: no owner, and the run can be claimed again"
        (is (nil? (owner/owner root id)))
        (let [next-claim (owner/acquire! root id)]
          (is (not (anomaly/anomaly? next-claim)))
          (owner/release! next-claim))))))

(deftest ^{:stratum 0} with-run-owner-does-not-run-a-claimed-run-test
  (fs/with-temp-dir [root {:prefix "run-owner-"}]
    (let [id   (random-uuid)
          held (owner/acquire! root id)
          ran  (atom false)]
      (try
        (is (= :conflict (try+ (owner/with-run-owner root id #(reset! ran true))
                               :ran
                               (catch [:anomaly/type :conflict] _ :conflict))))
        (is (false? @ran))
        (finally (owner/release! held)))
      (is (= :done (owner/with-run-owner root id (constantly :done))))
      (is (= :fresh (owner/with-run-owner root nil (constantly :fresh)))
          "a run id minted fresh needs no lock"))))

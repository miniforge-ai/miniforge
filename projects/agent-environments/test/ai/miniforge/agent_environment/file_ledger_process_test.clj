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
(ns ai.miniforge.agent-environment.file-ledger-process-test
  "Integration: the file ledger across processes. Each writer is its own
   JVM, so only the key's file lock, never this JVM's monitors, keeps their
   compare-and-set writes apart."
  (:require
   [ai.miniforge.agent-environment.file-ledger-processes :as processes]
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-environment.interface.ledger :as store]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-processes-racing-on-one-revision-write-once
  (let [dir (processes/temp-dir)
        {:keys [reads results]} (processes/race! dir 2)]
    (is (= [{:read nil} {:read nil}] reads))
    (is (= #{{:ledger/revision 1} :anomalies.agent-environment/ledger-conflict} (set results)))
    (testing "given the loser retries against the winner's revision → its write lands"
      (is (= {:ledger/revision 2} (store/write-entry! (env/file-ledger dir) processes/k 1 {:entry/writer "retry"}))))
    (testing "given a fresh process after the writers exit → it reads the acknowledged write"
      (is (= {:ledger/entry {:entry/writer "retry"} :ledger/revision 2} (processes/read-in-fresh-process dir))))))

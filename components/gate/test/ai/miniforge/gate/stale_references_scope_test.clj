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
(ns ai.miniforge.gate.stale-references-scope-test
  "Consumer scoping and per-family removal for the contract-drift gate.
   The end-to-end fixture is trap-bench repair series 3, rep rs1, as
   recorded: producer renamed, a changed test keeping the old keyword
   in another sense, the bb.edn consumer requiring the interface and
   still reading the old key, and an unrelated file using the same bare
   keyword with no import of the family."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.stale-references :as stale]
            [ai.miniforge.gate.stale-references-test :as base]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} ns-name-and-family
  (is (= "ai.miniforge.codex-gap.ledger"
         (stale/ns-name-of "(ns ai.miniforge.codex-gap.ledger\n  \"doc\"\n  (:require [x]))")))
  (is (= "ai.miniforge.codex-gap.ledger"
         (stale/ns-name-of "(ns ^{:stratum 0} ai.miniforge.codex-gap.ledger)")))
  (is (nil? (stale/ns-name-of "{:tasks {codex-gap-report {:doc \"x\"}}}")))
  (is (= "ai.miniforge.codex-gap" (stale/namespace-family "ai.miniforge.codex-gap.ledger")))
  (is (= "producer" (stale/namespace-family "producer"))))

(deftest ^{:stratum 0} test-path-detection
  (is (true? (stale/test-path? "components/codex-gap/test/ai/miniforge/codex_gap/interface_test.clj")))
  (is (true? (stale/test-path? "src/foo_test.clj")))
  (is (false? (stale/test-path? "components/codex-gap/src/ai/miniforge/codex_gap/ledger.clj")))
  (is (false? (stale/test-path? "bb.edn"))))

(deftest ^{:stratum 0} namespaced-keyword-detection
  (is (true? (stale/namespaced-keyword? ":ledger/skipped")))
  (is (false? (stale/namespaced-keyword? ":skipped")))
  (is (false? (stale/namespaced-keyword? "read-ledger"))))

(deftest ^{:stratum 0} producer-families-groups-non-test-namespaced-files
  (let [before-of {"components/c/src/ai/m/c/ledger.clj" "(ns ai.m.c.ledger)"
                   "components/c/src/ai/m/c/report.clj" "(ns ai.m.c.report)"
                   "components/c/test/ai/m/c/ledger_test.clj" "(ns ai.m.c.ledger-test)"
                   "bb.edn" "{:tasks {}}"}
        families (stale/producer-families (keys before-of) before-of)]
    (is (= ["ai.m.c"] (keys families)))
    (is (= 2 (count (get families "ai.m.c"))))))

(deftest ^{:stratum 0} rs1-shape-flags-only-the-importing-consumer
  (testing "a changed test keeping :skipped in another sense does not
            hide the producer's removal, and a stranger using :skipped
            without importing the family is not reported"
    (let [producer "components/codex-gap/src/ai/miniforge/codex_gap/ledger.clj"
          test-file "components/codex-gap/test/ai/miniforge/codex_gap/interface_test.clj"
          consumer "bb.edn"
          stranger "components/gate/src/ai/miniforge/gate/other.clj"
          dir (base/temp-git-repo
               {producer "(ns ai.miniforge.codex-gap.ledger)\n(defn read-ledger [] {:entries [] :skipped 0})"
                test-file "(ns ai.miniforge.codex-gap.interface-test)\n(is (= {:status :skipped} (pin)))\n(is (= 0 (:skipped (read))))"
                consumer "{:tasks {report {:requires ([ai.miniforge.codex-gap.interface :as codex-gap])\n :task (:skipped (codex-gap/read-ledger d))}}}"
                stranger "(ns ai.miniforge.gate.other)\n(def r {:status :skipped})"})
          new-producer "(ns ai.miniforge.codex-gap.ledger)\n(defn read-ledger [] {:entries [] :torn-lines 0})"
          new-test "(ns ai.miniforge.codex-gap.interface-test)\n(is (= {:status :skipped} (pin)))\n(is (= 0 (:torn-lines (read))))"
          _ (spit (str dir "/" producer) new-producer)
          _ (spit (str dir "/" test-file) new-test)
          result (stale/check-stale-references
                  {:code/files [{:path producer :content new-producer :action :modify}
                                {:path test-file :content new-test :action :modify}]}
                  {:execution/worktree-path dir})]
      (is (false? (:passed? result)))
      (is (= [{:token ":skipped" :family "ai.miniforge.codex-gap" :files [consumer]}]
             (mapv #(select-keys % [:token :family :files]) (:errors result)))))))

(deftest ^{:stratum 0} namespaced-keyword-is-searched-repo-wide
  (let [producer "src/ai/m/c/ledger.clj"
        consumer "src/ai/m/other/reader.clj"
        dir (base/temp-git-repo
             {producer "(ns ai.m.c.ledger)\n(defn read [] {:ledger/skipped 0})"
              consumer "(ns ai.m.other.reader)\n(get {} :ledger/skipped)"})
        new-producer "(ns ai.m.c.ledger)\n(defn read [] {:ledger/torn-lines 0})"
        _ (spit (str dir "/" producer) new-producer)
        result (stale/check-stale-references
                {:code/files [{:path producer :content new-producer :action :modify}]}
                {:execution/worktree-path dir})]
    (is (false? (:passed? result)))
    (is (= [":ledger/skipped"] (mapv :token (:errors result))))
    (is (= [[consumer]] (mapv :files (:errors result))))))

(deftest ^{:stratum 0} family-without-importers-passes
  (let [producer "src/ai/m/c/ledger.clj"
        stranger "src/ai/m/other/x.clj"
        dir (base/temp-git-repo
             {producer "(ns ai.m.c.ledger)\n(defn read [] {:skipped 0})"
              stranger "(ns ai.m.other.x)\n(def r {:status :skipped})"})
        new-producer "(ns ai.m.c.ledger)\n(defn read [] {:torn-lines 0})"
        _ (spit (str dir "/" producer) new-producer)
        result (stale/check-stale-references
                {:code/files [{:path producer :content new-producer :action :modify}]}
                {:execution/worktree-path dir})]
    (is (true? (:passed? result)))))

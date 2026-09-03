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
(ns ai.miniforge.gate.stale-references-test
  "Tests for the contract-drift gate: token extraction, removal
   semantics, and the end-to-end check against a real temp git repo —
   the trap-bench shape (producer renamed, untested consumer stale) is
   the fixture, per feedback: fixtures match the real producer's output."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.gate.stale-references :as stale])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} temp-git-repo
  "Init a git repo with `files` ({path content}) committed at HEAD.
   Returns the repo dir as a string."
  [files]
  (let [dir (str (Files/createTempDirectory "stale-ref-test" (make-array FileAttribute 0)))]
    (shell/sh "git" "-C" dir "init" "-q")
    (shell/sh "git" "-C" dir "config" "user.email" "test@test")
    (shell/sh "git" "-C" dir "config" "user.name" "test")
    (doseq [[path content] files]
      (let [f (java.io.File. dir path)]
        (.mkdirs (.getParentFile f))
        (spit f content)))
    (shell/sh "git" "-C" dir "add" "-A")
    (shell/sh "git" "-C" dir "commit" "-q" "-m" "base")
    dir))

(deftest ^{:stratum 0} content-tokens-extracts-keywords-and-def-names
  (let [tokens (stale/content-tokens
                "(defn read-ledger [x]\n  {:entries [] :skipped 0 :a 1})\n(def ^{:stratum 0} ledger-filename \"f\")")]
    (is (contains? tokens ":skipped"))
    (is (contains? tokens ":entries"))
    (is (contains? tokens "read-ledger"))
    (is (contains? tokens "ledger-filename"))
    (is (contains? tokens ":a")
        "extraction keeps short tokens; removed-tokens filters length")))

(deftest ^{:stratum 0} removed-tokens-semantics
  (testing "a token absent from every after-content is removed"
    (is (= [":skipped"]
           (stale/removed-tokens ["{:entries [] :skipped 0}"]
                                 ["{:entries [] :torn-lines 0}"]))))
  (testing "a token that MOVED to another changed file is not removed"
    (is (= []
           (stale/removed-tokens ["{:skipped 0}"]
                                 ["{}" "(get m :skipped)"]))))
  (testing "boundary-anchored presence: :skip is removed even though
            :skipped survives in the after-content"
    (is (= [":skip"]
           (stale/removed-tokens ["{:skip 1 :skipped 0}"]
                                 ["{:skipped 0}"]))))
  (testing "short tokens are filtered"
    (is (= []
           (stale/removed-tokens ["{:a 1}"] ["{}"])))))

(deftest ^{:stratum 0} token-present?-boundaries
  (is (true? (stale/token-present? "(get r :skipped 0)" ":skipped")))
  (is (false? (stale/token-present? "(get r :skipped 0)" ":skip")))
  (is (false? (stale/token-present? "read-ledger-fast" "read-ledger")))
  (is (true? (stale/token-present? "(read-ledger x)" "read-ledger"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} check-guards
  (testing "no changed files -> pass, no warnings"
    (is (= {:passed? true}
           (stale/check-stale-references {} {:execution/worktree-path "/tmp"}))))
  (testing "no worktree -> fail-open with a warning"
    (let [r (stale/check-stale-references
             {:code/files [{:path "a.clj" :content "x" :action :modify}]} {})]
      (is (true? (:passed? r)))
      (is (= :stale-references-skipped (-> r :warnings first :type)))))
  (testing "worktree is not a git repo -> fail-open with the
            git-unavailable warning"
    (let [dir (str (Files/createTempDirectory "stale-ref-nogit" (make-array FileAttribute 0)))
          r (stale/check-stale-references
             {:code/files [{:path "a.clj" :content "x" :action :modify}]}
             {:execution/worktree-path dir})]
      (is (true? (:passed? r)))
      (is (str/includes? (-> r :warnings first :message) "git unavailable"))))
  (testing "no readable after-content -> fail-open with the no-content
            warning"
    (let [dir (temp-git-repo {"a.clj" "(ns a)"})
          r (stale/check-stale-references
             {:code/file-paths ["missing.clj"]}
             {:execution/worktree-path dir})]
      (is (true? (:passed? r)))
      (is (str/includes? (-> r :warnings first :message) "no readable after-content")))))

(deftest ^{:stratum 1} check-fails-on-stale-consumer
  (testing "the trap-bench shape: producer renames a key, an uncommitted
            consumer elsewhere still reads the old one"
    (let [producer "src/producer.clj"
          consumer "tasks/report.clj"
          dir (temp-git-repo
               {producer "(ns producer)\n(defn read-ledger [] {:entries [] :skipped 0})"
                consumer "(ns report (:require [producer :as p]))\n(defn skipped-count [r] (get (p/read-ledger) :skipped 0))"})
          new-producer "(ns producer)\n(defn read-ledger [] {:entries [] :torn-lines 0})"
          _ (spit (str dir "/" producer) new-producer)
          result (stale/check-stale-references
                  {:code/files [{:path producer :content new-producer :action :modify}]}
                  {:execution/worktree-path dir})]
      (is (false? (:passed? result)))
      (is (= [":skipped"] (mapv :token (:errors result))))
      (is (= [[consumer]] (mapv :files (:errors result)))))))

(deftest ^{:stratum 1} check-passes-when-consumers-updated
  (let [producer "src/producer.clj"
        consumer "tasks/report.clj"
        dir (temp-git-repo
             {producer "(ns producer)\n(defn read-ledger [] {:skipped 0})"
              consumer "(ns report (:require [producer]))\n(get {} :skipped)"})
        new-producer "(ns producer)\n(defn read-ledger [] {:torn-lines 0})"
        new-consumer "(ns report (:require [producer]))\n(get {} :torn-lines)"
        _ (spit (str dir "/" producer) new-producer)
        _ (spit (str dir "/" consumer) new-consumer)
        result (stale/check-stale-references
                {:code/files [{:path producer :content new-producer :action :modify}
                              {:path consumer :content new-consumer :action :modify}]}
                {:execution/worktree-path dir})]
    (is (true? (:passed? result)))))

(deftest ^{:stratum 1} check-judges-cumulative-worktree-diff-not-just-the-artifact
  (testing "an EMPTY-artifact retry is still denied when the worktree
            carries a prior attempt's stale rename — the vacuous-pass
            escape every repair-demonstration run took"
    (let [producer "src/producer.clj"
          consumer "tasks/report.clj"
          dir (temp-git-repo
               {producer "(ns producer)\n(defn read-ledger [] {:skipped 0})"
                consumer "(ns report (:require [producer]))\n(get {} :skipped)"})
          _ (spit (str dir "/" producer) "(ns producer)\n(defn read-ledger [] {:torn-lines 0})")
          result (stale/check-stale-references
                  {:code/files []}
                  {:execution/worktree-path dir})]
      (is (false? (:passed? result)))
      (is (= [":skipped"] (mapv :token (:errors result)))))))

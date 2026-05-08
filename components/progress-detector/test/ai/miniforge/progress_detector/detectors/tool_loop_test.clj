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

(ns ai.miniforge.progress-detector.detectors.tool-loop-test
  "Tests for the tool-loop detector — fingerprint sliding-window."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.progress-detector.detectors.tool-loop :as sut]
   [ai.miniforge.progress-detector.protocol :as proto]
   [ai.miniforge.progress-detector.tool-profile :as tp]))

;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private read-profile
  {:tool/id     :tool/Read
   :determinism :stable-with-resource-version
   :anomaly/categories #{:anomalies.agent/tool-loop}})

(def ^:private grep-profile
  {:tool/id     :tool/Grep
   :determinism :stable-ish
   :anomaly/categories #{:anomalies.agent/tool-loop}})

(def ^:private bash-profile
  {:tool/id     :tool/Bash
   :determinism :environment-dependent
   :anomaly/categories #{:anomalies.agent/tool-loop}})

(def ^:private websearch-profile
  {:tool/id     :tool/WebSearch
   :determinism :unstable
   :anomaly/categories #{:anomalies.agent/tool-loop}})

(defn- registry-with [profiles]
  (let [reg (tp/make-registry)]
    (doseq [p profiles] (tp/register! reg p))
    reg))

(defn- run-observations [detector config observations]
  (proto/reduce-observations detector config observations))

(def ^:private now (java.time.Instant/now))

(defn- read-event
  "A Read observation against `path` with a given `version-hash`."
  [seq-num path version-hash]
  {:tool/id   :tool/Read
   :seq       seq-num
   :timestamp now
   :tool/input            path
   :resource/version-hash version-hash})

(defn- bash-event
  "A Bash observation. `error?` flags whether the call failed.
   `output` is the redacted-output stand-in for the stub failure
   fingerprint."
  [seq-num cmd error? output]
  {:tool/id      :tool/Bash
   :seq          seq-num
   :timestamp    now
   :tool/input   cmd
   :tool/error?  error?
   :tool/output  output})

(defn- count-anomalies [state]
  (count (:anomalies state)))

(defn- first-anomaly-class [state]
  (get-in (first (:anomalies state)) [:anomaly/data :anomaly/class]))

(def ^:private threshold-3
  {:config/params {:window-size 10 :threshold-n 3}})

;------------------------------------------------------------------------------ Layer 1
;; Mechanical positives

(deftest read-loop-with-stable-version-hash-fires-mechanical-test
  (testing "6 Reads of the same path + unchanged version-hash → mechanical"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % "src/foo.clj" "sha256:aaa")
                      (range 1 7))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1)
          "anomaly fired at the threshold")
      (is (= :mechanical (first-anomaly-class final))
          "stable-with-resource-version + matching hash → mechanical"))))

(deftest grep-loop-stable-ish-fires-mechanical-test
  (testing "5 identical Greps → mechanical (stable-ish)"
    (let [reg   (registry-with [grep-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv (fn [n]
                        {:tool/id    :tool/Grep
                         :seq        n
                         :timestamp  now
                         :tool/input "pattern"})
                      (range 1 6))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1))
      (is (= :mechanical (first-anomaly-class final))))))

(deftest bash-loop-all-error-true-fires-mechanical-test
  (testing "5 Bash failures with same cmd + same output → mechanical"
    (let [reg   (registry-with [bash-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(bash-event % "git push" true "permission denied")
                      (range 1 6))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1))
      (is (= :mechanical (first-anomaly-class final))
          "every match :tool/error? true + matching :tool/output → mechanical"))))

;------------------------------------------------------------------------------ Layer 1
;; Mechanical negatives

(deftest version-hash-change-suppresses-anomaly-test
  (testing "Read of same path BUT version-hash changed → no anomaly"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % "src/foo.clj" (str "sha256:" %))
                      (range 1 7))
          final (run-observations det {} obs)]
      (is (zero? (count-anomalies final))
          "different version-hashes are different fingerprints"))))

(deftest distinct-paths-suppress-anomaly-test
  (testing "Read of 6 different files → no anomaly"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % (str "src/file" % ".clj") "sha256:x")
                      (range 1 7))
          final (run-observations det {} obs)]
      (is (zero? (count-anomalies final))
          "distinct inputs are distinct fingerprints"))))

(deftest bash-mixed-error-flags-degrades-to-heuristic-test
  (testing ":environment-dependent with mixed :tool/error? → heuristic"
    (let [reg   (registry-with [bash-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   [(bash-event 1 "ls" false "ok")
                 (bash-event 2 "ls" false "ok")
                 (bash-event 3 "ls" false "ok")
                 (bash-event 4 "ls" false "ok")
                 (bash-event 5 "ls" false "ok")]
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1)
          "fingerprint repeats still trigger an anomaly")
      (is (= :heuristic (first-anomaly-class final))
          "no error flag means no failure-fingerprint match → heuristic"))))

;------------------------------------------------------------------------------ Layer 1
;; Heuristic-only path (:unstable)

(deftest stable-with-version-but-nil-hash-degrades-to-heuristic-test
  (testing ":stable-with-resource-version with nil version-hash → heuristic, not mechanical"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          ;; Same path, but the envelope did not capture version-hash —
          ;; nil on every match. Without resource-state evidence the
          ;; detector cannot claim 'same call same answer'; degrade to
          ;; heuristic per Copilot review on PR #788.
          obs   (mapv #(read-event % "src/foo.clj" nil) (range 1 7))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1))
      (is (= :heuristic (first-anomaly-class final))
          "missing version-hash evidence cannot promote to mechanical"))))

(deftest websearch-repeat-fires-heuristic-only-test
  (testing "5 identical WebSearch calls → heuristic (never mechanical)"
    (let [reg   (registry-with [websearch-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv (fn [n]
                        {:tool/id    :tool/WebSearch
                         :seq        n
                         :timestamp  now
                         :tool/input "claude code"})
                      (range 1 6))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1))
      (is (= :heuristic (first-anomaly-class final))
          ":unstable :determinism disqualifies mechanical termination"))))

;------------------------------------------------------------------------------ Layer 2
;; Threshold + window semantics

(deftest below-threshold-no-fire-test
  (testing "4 identical Reads at threshold-n 5 → no anomaly"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % "src/foo.clj" "sha256:a") (range 1 5))
          final (run-observations det {} obs)]
      (is (zero? (count-anomalies final))))))

(deftest custom-threshold-respected-test
  (testing "config :threshold-n 3 fires earlier than default 5"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % "src/foo.clj" "sha256:a") (range 1 4))
          final (run-observations det threshold-3 obs)]
      (is (>= (count-anomalies final) 1)
          "3 matches against config-tuned threshold-n 3"))))

(deftest window-evicts-old-entries-test
  (testing "old fingerprints fall out of the window"
    (let [reg    (registry-with [grep-profile])
          det    (sut/make-tool-loop-detector reg)
          config {:config/params {:window-size 3 :threshold-n 3}}
          ;; 2 noisy + 2 of-interest + 2 noisy: of-interest never reaches
          ;; the window-size cap of 3 with 3 matches.
          obs   [{:tool/id :tool/Grep :seq 1 :timestamp now :tool/input "noise-a"}
                 {:tool/id :tool/Grep :seq 2 :timestamp now :tool/input "noise-b"}
                 {:tool/id :tool/Grep :seq 3 :timestamp now :tool/input "interesting"}
                 {:tool/id :tool/Grep :seq 4 :timestamp now :tool/input "interesting"}
                 {:tool/id :tool/Grep :seq 5 :timestamp now :tool/input "noise-c"}
                 {:tool/id :tool/Grep :seq 6 :timestamp now :tool/input "noise-d"}]
          final (run-observations det config obs)]
      (is (zero? (count-anomalies final))
          "interesting fingerprint count peaks at 2 inside any window of 3"))))

(deftest window-eviction-keeps-recent-test
  (testing "consecutive identical observations within window do fire"
    (let [reg    (registry-with [grep-profile])
          det    (sut/make-tool-loop-detector reg)
          config {:config/params {:window-size 3 :threshold-n 3}}
          obs    (mapv (fn [n]
                         {:tool/id :tool/Grep :seq n
                          :timestamp now :tool/input "x"})
                       (range 1 4))
          final  (run-observations det config obs)]
      (is (>= (count-anomalies final) 1)))))

;------------------------------------------------------------------------------ Layer 2
;; Anomaly shape

(deftest anomaly-evidence-bounded-test
  (testing "evidence carries summary + event-ids + fingerprint + threshold"
    (let [reg     (registry-with [read-profile])
          det     (sut/make-tool-loop-detector reg)
          obs     (mapv #(read-event % "src/foo.clj" "sha256:a") (range 1 7))
          final   (run-observations det {} obs)
          first-a (first (:anomalies final))
          ev      (get-in first-a [:anomaly/data :anomaly/evidence])]
      (is (string? (:summary ev)))
      (is (vector? (:event-ids ev)))
      (is (every? int? (:event-ids ev)))
      (is (string? (:fingerprint ev)))
      (is (= {:n 5 :window 10} (:threshold ev))
          "threshold metadata reflects defaults")
      (is (true? (:redacted? ev))))))

(deftest anomaly-stamps-detector-kind-and-version-test
  (testing "anomaly carries :detector/kind and :detector/version"
    (let [reg   (registry-with [read-profile])
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv #(read-event % "src/foo.clj" "sha256:a") (range 1 7))
          final (run-observations det {} obs)
          a     (first (:anomalies final))]
      (is (= :detector/tool-loop (get-in a [:anomaly/data :detector/kind])))
      (is (string? (get-in a [:anomaly/data :detector/version])))
      (is (= :anomalies.agent/tool-loop
             (get-in a [:anomaly/data :anomaly/category]))))))

;------------------------------------------------------------------------------ Layer 2
;; Unknown-tool fallthrough

(deftest unknown-tool-treated-as-unstable-test
  (testing "tool absent from registry defaults to :unstable → heuristic"
    (let [reg   (tp/make-registry)
          det   (sut/make-tool-loop-detector reg)
          obs   (mapv (fn [n]
                        {:tool/id   :tool/Mystery
                         :seq       n
                         :timestamp now
                         :tool/input "x"})
                      (range 1 6))
          final (run-observations det {} obs)]
      (is (>= (count-anomalies final) 1))
      (is (= :heuristic (first-anomaly-class final))
          "registry returns :unstable for unknown tools"))))

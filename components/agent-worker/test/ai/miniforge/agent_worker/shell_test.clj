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
(ns ai.miniforge.agent-worker.shell-test
  (:require
   [ai.miniforge.agent-worker.native.shell :as shell]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} test-arguments-are-single-quoted
  (is (= "'it'\\''s'" (shell/quote-arg "it's")))
  (is (= "'a b' '$HOME'" (shell/command-line ["a b" "$HOME"]))))

(deftest ^{:stratum 0} test-a-cancel-that-signals-nothing-is-not-forced-to-succeed
  (is (not (str/includes? (shell/cancel-script "4242") "true"))))

(deftest ^{:stratum 0} test-a-process-ref-splits-at-its-last-hash
  (is (= ["/work#1/.miniforge/worker/1-1" "4242"] (shell/split-ref (shell/process-ref "/work#1/.miniforge/worker/1-1" "4242")))))

(deftest ^{:stratum 0} test-status-output-maps-to-states
  (testing "given an exit status file → completed, failed, or cancelled by SIGTERM"
    (is (= {:status/state :state/completed :status/exit 0} (shell/status-of "0\n")))
    (is (= {:status/state :state/failed :status/exit 2} (shell/status-of "2")))
    (is (= :state/cancelled (:status/state (shell/status-of (str shell/sigterm-exit))))))
  (testing "given a live process or nothing at all → running, or unknown"
    (is (= :state/running (:status/state (shell/status-of "running"))))
    (is (= :state/unknown (:status/state (shell/status-of "gone"))))
    (is (= :state/unknown (:status/state (shell/status-of nil))))))

(deftest ^{:stratum 0} test-credential-check-names-only-safe-variables
  (is (= "test -n \"${A_KEY:-}${B_TOKEN:-}\"" (shell/credential-script #{"B_TOKEN" "A_KEY" "x;rm -rf /"}))))

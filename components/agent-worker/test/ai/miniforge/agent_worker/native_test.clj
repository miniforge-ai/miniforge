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
(ns ai.miniforge.agent-worker.native-test
  (:require
   [ai.miniforge.agent-environment.interface :as env]
   [ai.miniforge.agent-worker.fixtures :as fx]
   [ai.miniforge.agent-worker.interface :as worker]
   [ai.miniforge.agent-worker.native-fixtures :as nf]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} prompt "Make the failing test pass.")

(def ^{:stratum 0} claude-result
  "A final stream-json frame as the Claude CLI writes it."
  "{\"type\":\"result\",\"session_id\":\"native-7\",\"result\":\"done\",\"total_cost_usd\":0.25,\"usage\":{\"input_tokens\":10,\"output_tokens\":20}}")

(def ^{:stratum 0} codex-completed
  "A turn.completed event as `codex exec --json` writes it: tokens, no cost."
  "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":30,\"output_tokens\":40}}")

(defn- ^{:stratum 0} reason [result] (anomaly/subtype result))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-claude-turn-runs-the-llm-backend-command-line
  (let [w (nf/native-world :worker/claude nil)
        session (fx/start! w (fx/config :worker/claude :config/model "claude-model") 1 prompt)
        launch (first (nf/launched-scripts w))]
    (is (str/ends-with? (:session/process-ref session) (str "#" nf/harness-pid)))
    (testing "given the Claude backend → its own flags, prompt on stdin, settings beside the turn"
      (is (str/includes? launch "'claude' '-p' '--output-format' 'stream-json'"))
      (is (str/includes? launch "'--input-format' 'text'"))
      (is (str/includes? launch (str "'--settings' '" nf/turn-dir "/settings.json'")))
      (is (str/includes? launch "'--model' 'claude-model'"))
      (is (not (str/includes? launch prompt))))
    (is (= prompt (env/read-file (:ec w) (:acting w) (:lease w) (str nf/turn-dir "/prompt.txt"))))
    (is (str/includes? (env/read-file (:ec w) (:acting w) (:lease w) (str nf/turn-dir "/settings.json")) "Bash"))))

(deftest ^{:stratum 1} test-claude-usage-and-resume-come-from-its-stream
  (let [w (nf/native-world :worker/claude nil)
        session (fx/start! w (fx/config :worker/claude) 1 prompt)]
    (nf/write-stream! w claude-result)
    (is (= {:usage/status :usage/reported :usage/input-tokens 10 :usage/output-tokens 20 :usage/cost-usd 0.25}
           (worker/usage (:wc w) (:acting w) session)))
    (let [steered (worker/send-input! (:wc w) (:acting w) session "Now the docs.")]
      (is (= "native-7" (:session/native-id steered)))
      (is (str/includes? (last (nf/launched-scripts w)) "'--resume' 'native-7'")))))

(deftest ^{:stratum 1} test-codex-reports-tokens-without-cost-and-cannot-steer
  (let [w (nf/native-world :worker/codex nil)
        session (fx/start! w (fx/config :worker/codex) 1 prompt)]
    (is (str/includes? (first (nf/launched-scripts w)) "'codex' 'exec' '--json'"))
    (nf/write-stream! w codex-completed)
    (is (= {:usage/status :usage/partial :usage/input-tokens 30 :usage/output-tokens 40 :usage/cost-usd nil}
           (worker/usage (:wc w) (:acting w) session)))
    (is (= :anomalies.agent-worker/steering-unsupported
           (reason (worker/send-input! (:wc w) (:acting w) session "More."))))))

(deftest ^{:stratum 1} test-missing-harness-or-credential-fails-preflight
  (doseq [missing [:cli :credential]]
    (let [w (nf/native-world :worker/claude missing)
          refused (fx/start! w (fx/config :worker/claude) 1 prompt)]
      (is (= :anomalies.agent-worker/worker-preflight-failed (reason refused)))
      (is (empty? (nf/launched-scripts w))))))

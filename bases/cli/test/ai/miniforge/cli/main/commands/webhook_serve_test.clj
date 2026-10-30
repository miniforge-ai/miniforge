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
(ns ai.miniforge.cli.main.commands.webhook-serve-test
  "`mf webhook serve` validates its trigger file and secret, and wires the
   intake and the trigger to one event stream."
  (:require
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.commands.webhook-serve :as sut]
   [ai.miniforge.cli.main.commands.webhook-triggers :as triggers]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.webhook-intake.interface :as intake]
   [ai.miniforge.workflow.interface :as workflow]
   [babashka.fs :as fs]
   [clojure.test :refer [deftest is testing]]
   [org.httpkit.client :as http-client]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} rule
  "A trigger rule in the form create-event-trigger reads."
  {:on :pr/merged :repo "miniforge-ai/miniforge" :run {:workflow-id :release-notes}})

(def ^{:stratum 0} merged-delivery
  "A minimal merged pull_request delivery body from this repository."
  (str "{\"action\":\"closed\",\"number\":7,\"repository\":{\"full_name\":\"miniforge-ai/miniforge\"},"
       "\"pull_request\":{\"number\":7,\"merged\":true,\"merge_commit_sha\":\"abc\","
       "\"head\":{\"ref\":\"feat/x\",\"sha\":\"def\",\"repo\":{\"full_name\":\"miniforge-ai/miniforge\"}},"
       "\"base\":{\"ref\":\"main\",\"repo\":{\"full_name\":\"miniforge-ai/miniforge\"}}}}"))

(defn ^{:stratum 0} temp-file
  "A temp file holding `content`."
  [content]
  (let [f (fs/create-temp-file {:suffix ".edn"})]
    (spit (str f) content)
    (str f)))

(defn ^{:stratum 0} run-cmd
  "Run the command with exits turned into return values and output
   captured. Returns {:exit code-or-nil :errors [...] :infos [...]}."
  [opts]
  (let [errors (atom []) infos (atom []) real-stream es/create-event-stream]
    (with-redefs [shared/exit! (fn [code] (throw (ex-info "exit" {:code code})))
                  display/print-error #(swap! errors conj %)
                  display/print-info #(swap! infos conj %)
                  messages/t (fn ([k] (name k)) ([k _] (name k)))
                  execution/create-artifact-store (constantly nil)
                  es/create-event-stream (fn [opts] (real-stream (assoc opts :sinks [])))]
      {:exit (try (sut/webhook-serve-cmd opts) nil
                  (catch clojure.lang.ExceptionInfo e (:code (ex-data e))))
       :errors @errors
       :infos @infos})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} trigger-config-test
  (testing "a rule needs an intake event type, a :repo, a :workflow-id, and a pattern that compiles"
    (is (= {:triggers [rule]} (triggers/trigger-config (pr-str {:triggers [rule]}))))
    (is (some? (triggers/trigger-config (pr-str {:triggers [(assoc rule :branch-pattern "feat/.*")]}))))
    (doseq [bad [(dissoc rule :on) (assoc rule :on :pr/ci-passed) (dissoc rule :repo)
                 (assoc rule :branch-pattern "feat/(") (update rule :run dissoc :workflow-id)]]
      (is (nil? (triggers/trigger-config (pr-str {:triggers [bad]}))) (pr-str bad)))
    (is (nil? (triggers/trigger-config (pr-str {:triggers []}))))
    (is (nil? (triggers/trigger-config "{:triggers")))))

(deftest ^{:stratum 1} refusals-exit-1-test
  (testing "no trigger file"
    (is (= {:exit 1 :errors ["triggers-required"]} (select-keys (run-cmd {}) [:exit :errors]))))
  (testing "a file that is not a trigger file, or no file there"
    (is (= ["triggers-invalid"] (:errors (run-cmd {:triggers (temp-file "[]")}))))
    (is (= ["triggers-invalid"] (:errors (run-cmd {:triggers "/no/such/rules.edn"})))))
  (testing "no webhook secret file"
    (is (= {:exit 1 :errors ["start-refused"]}
           (select-keys (run-cmd {:triggers (temp-file (pr-str {:triggers [rule]})) :port 0})
                        [:exit :errors])))))

(deftest ^{:stratum 1} intake-and-trigger-share-a-stream-test
  (testing "a signed delivery reaches the stream the trigger listens on"
    (let [started (atom nil) hook (atom nil) seen (atom nil) published (atom [])
          real-start intake/start!]
      (with-redefs [sut/block-forever! (fn [] nil)
                    sut/add-shutdown-hook! #(reset! hook %)
                    intake/start! (fn [opts] (reset! started (real-start opts)))
                    workflow/create-event-trigger (fn [stream config opts]
                                                    (es/subscribe! stream :test #(swap! published conj %))
                                                    (reset! seen {:config config :opts opts :stream stream})
                                                    {:stop-fn (fn [] nil)})]
        (is (nil? (:exit (run-cmd {:triggers (temp-file (pr-str {:triggers [rule]}))
                                   :secret-file (temp-file "s3cret\n")
                                   :port 0})))))
      (try
        (is (= [rule] (get-in @seen [:config :triggers])))
        (is (identical? (:stream @seen) (get-in @seen [:opts :event-stream])))
        (is (= "127.0.0.1" (:host @started)) "binds loopback when --host is absent")
        (let [response @(http-client/post (str "http://127.0.0.1:" (:port @started) intake/github-path)
                                          {:headers {"X-GitHub-Event" "pull_request"
                                                     "X-GitHub-Delivery" "d-1"
                                                     "X-Hub-Signature-256"
                                                     (intake/sign "s3cret" (.getBytes merged-delivery "UTF-8"))}
                                           :body merged-delivery})]
          (is (= 202 (:status response)))
          (is (= [:pr/merged] (mapv :event/type @published))))
        (finally (@hook))))))

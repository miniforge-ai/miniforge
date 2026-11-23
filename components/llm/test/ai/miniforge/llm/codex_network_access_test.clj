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
(ns ai.miniforge.llm.codex-network-access-test
  "A codex client created with `:network-access? true` adds
   `-c sandbox_workspace_write.network_access=true` to every call and keeps
   the workspace-write sandbox and approval_policy=never; without it the
   argv is unchanged. Only the client turns it on, and only a codex
   client takes it."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.protocols.impl.llm-client.cli-args :as cli-args]
   [ai.miniforge.llm.protocols.impl.llm-client.completion :as completion]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} network-override
  "The Codex config override that turns on network access in the
   workspace-write sandbox."
  ["-c" "sandbox_workspace_write.network_access=true"])

(defn- ^{:stratum 0} contains-run?
  "True when `args` holds `run` as consecutive elements."
  [args run]
  (boolean (some #{run} (partition (count run) 1 args))))

(defn- ^{:stratum 0} sent-argv
  "The argv a codex client created with `opts` sends for one completion
   of `request`."
  [opts request]
  (let [seen (atom nil)
        client (llm/create-client (merge {:backend :codex
                                          :exec-fn (fn [cmd & _]
                                                     (reset! seen cmd)
                                                     {:out "" :err "" :exit 0})}
                                         opts))]
    (llm/complete client request)
    @seen))

(defn- ^{:stratum 0} refusal
  "The anomaly category `create-client` throws for `opts`, or nil."
  [opts]
  (try (llm/create-client opts) nil
       (catch clojure.lang.ExceptionInfo e (:anomaly/category (ex-data e)))))

(deftest ^{:stratum 0} with-client-config-network-access-test
  (testing "the client's setting decides; a request's own value is dropped"
    (is (true? (:network-access? (completion/with-client-config {:prompt "p"} {:network-access? true}))))
    (is (not (contains? (completion/with-client-config {:prompt "p" :network-access? true} {})
                        :network-access?)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} codex-args-network-override-test
  (testing "network access adds the override and keeps the sandbox and approval flags"
    (let [args (cli-args/codex-args {:prompt "p" :network-access? true})]
      (is (contains-run? args network-override))
      (is (some #{"--sandbox=workspace-write"} args))
      (is (contains-run? args ["-c" "approval_policy=never"]))
      (is (not-any? #(re-find #"danger-full-access|dangerously" %) args))))
  (testing "without it, or with a value that is not true, the argv has no override"
    (doseq [request [{:prompt "p"} {:prompt "p" :network-access? false}
                     {:prompt "p" :network-access? "true"}]]
      (is (not (contains-run? (cli-args/codex-args request) network-override))
          (pr-str request)))))

(deftest ^{:stratum 1} client-network-access-reaches-the-argv-test
  (testing "a codex client created with network access sends the override"
    (is (contains-run? (sent-argv {:network-access? true} {:prompt "p"}) network-override)))
  (testing "a default codex client does not, even when a request asks for it"
    (is (not (contains-run? (sent-argv {} {:prompt "p"}) network-override)))
    (is (not (contains-run? (sent-argv {} {:prompt "p" :network-access? true}) network-override)))))

(deftest ^{:stratum 1} create-client-validates-network-access-test
  (testing "true on codex, and false or nil anywhere, are accepted"
    (is (llm/client-network-access? (llm/create-client {:backend :codex :network-access? true})))
    (is (not (llm/client-network-access? (llm/create-client {:backend :codex :network-access? false}))))
    (is (not (llm/client-network-access? (llm/create-client {:backend :claude :network-access? false}))))
    (is (not (llm/client-network-access? (llm/create-client {:backend :codex})))))
  (testing "a value that is not a boolean, or true on another backend, is refused"
    (is (= :anomalies/incorrect (refusal {:backend :codex :network-access? "true"})))
    (is (= :anomalies/incorrect (refusal {:backend :codex :network-access? 1})))
    (is (= :anomalies/incorrect (refusal {:backend :claude :network-access? true})))
    (is (= :anomalies/incorrect (refusal {:backend :opencode :network-access? true}))))
  (testing "something that is not an LLMClient has no network access"
    (is (false? (llm/client-network-access? nil)))
    (is (false? (llm/client-network-access? {:config {:network-access? true}})))))

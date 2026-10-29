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
(ns ai.miniforge.cli.config-set-validate-test
  "`config set` and `config validate` accept only the keys the config
   schema lists, with values of their type. Every command here works on a
   temp config file, never ~/.miniforge."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-config-file
  "Call `f` with the path of a temp config.edn holding `content` (a string)."
  [content f]
  (let [dir  (fs/create-temp-dir {:prefix "mf-config-schema-"})
        path (str (fs/path dir "config.edn"))]
    (try
      (spit path content)
      (f path)
      (finally (fs/delete-tree dir)))))

(defn- ^{:stratum 0} run-recording
  "Call `f` with exit! recorded and output captured: {:exits :out :err}."
  [f]
  (let [exits (atom [])
        out   (java.io.StringWriter.)
        err   (java.io.StringWriter.)]
    (with-redefs [shared/exit! (fn [code] (swap! exits conj code) nil)]
      (binding [*out* out *err* err] (f)))
    {:exits @exits :out (str out) :err (str err)}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} config-set-rejects-what-a-run-cannot-use-test
  (doseq [[k v] [["llm.backend" "bogus"]
                 ["dashboard.port" "notanumber"]
                 ["made.up.key" "5"]]]
    (testing (str "config set " k " " v ": exit 1, error naming the key, file unchanged")
      (with-config-file "{:llm {:backend :claude}}"
        (fn [path]
          (let [{:keys [exits out err]} (run-recording #(config/cmd-set {:config path :key k :value v}))]
            (is (= [1] exits))
            ;; config's print-error goes to stdout until #2225 lands, stderr after.
            (is (str/includes? (str out err) k))
            (is (not (str/includes? out "✅")))
            (is (= {:llm {:backend :claude}} (edn/read-string (slurp path))))))))))

(deftest ^{:stratum 1} config-set-stores-valid-settings-test
  (testing "known keys with valid values are stored, coerced to their type"
    (with-config-file "{}"
      (fn [path]
        (let [{:keys [exits]} (run-recording
                               #(do (config/cmd-set {:config path :key "llm.backend" :value "claude"})
                                    (config/cmd-set {:config path :key "dashboard.port" :value "9090"})
                                    (config/cmd-set {:config path :key "self-healing.agent/per-backend-gap-thresholds.opencode"
                                                     :value "210000"})))
              saved (edn/read-string (slurp path))]
          (is (= [] exits))
          (is (= :claude (get-in saved [:llm :backend])))
          (is (= 9090 (get-in saved [:dashboard :port])))
          (is (= 210000 (get-in saved [:self-healing :agent/per-backend-gap-thresholds :opencode]))))))))

(deftest ^{:stratum 1} config-validate-reports-each-problem-test
  (testing "validate names each unknown key and wrong value and exits 1"
    (with-config-file "{:llm {:backend :bogus} :dashboard {:port \"x\"} :made {:up 1}}"
      (fn [path]
        (let [{:keys [exits out err]} (run-recording #(config/cmd-validate {:config path}))]
          (is (= [1] exits))
          (is (not (str/includes? out "✅ Config file is valid")))
          (doseq [k ["llm.backend" "dashboard.port" "made.up"]]
            (is (str/includes? (str out err) k) k))))))
  (testing "a config that fits the schema is valid"
    (with-config-file "{:llm {:backend :echo}}"
      (fn [path]
        (let [{:keys [exits out]} (run-recording #(config/cmd-validate {:config path}))]
          (is (= [] exits))
          (is (str/includes? out "✅"))))))
  (testing "a config file that does not exist exits 1"
    (is (= [1] (:exits (run-recording #(config/cmd-validate {:config "/nonexistent/mf/config.edn"})))))))

(deftest ^{:stratum 1} non-keyword-keys-are-named-whole-test
  (testing "a string key is shown as EDN, not with its first character cut"
    (is (str/includes? (config/problem-message {:path ["llm"] :problem :unknown-key}) "\"llm\""))
    (is (str/includes? (config/problem-message {:path [:llm "model"] :problem :unknown-key}) "llm.\"model\""))
    (is (str/includes? (config/problem-message {:path [""] :problem :unknown-key}) "\"\"")
        "an empty string key does not throw"))
  (testing "validate reports every string key in a hand-edited file and exits 1"
    (with-config-file "{\"llm\" 5 \"\" 1}"
      (fn [path]
        (let [{:keys [exits out err]} (run-recording #(config/cmd-validate {:config path}))]
          (is (= [1] exits))
          (is (str/includes? (str out err) "\"llm\""))
          (is (str/includes? (str out err) "Unknown config key: \"\"")))))))

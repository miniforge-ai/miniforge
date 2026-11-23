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
(ns ai.miniforge.logging.redaction-test
  "Secrets in a log entry never reach a sink: file, stdout, or a
   caller's own output fn. Ordinary text passes through unchanged."
  (:require
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.logging.sinks :as sinks]
   [ai.miniforge.redaction.interface :as redaction]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fixture
  "Secrets shaped like the real ones (none is a credential), built from
   parts so the source holds no token-shaped literal, and a message and
   :data map quoting each shape the logger must withhold."
  (let [gh-token      (str "ghs" "_" "abcdefghijklmnopqrstuvwxyz0123456789")
        anthropic-key (str "sk" "-ant-api03-" "abcdefghijklmnopqrstuvwxyz0123456789")
        bearer-value  "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA"
        url-password  "hunter2-url-secret"]
    {:secrets      [gh-token anthropic-key bearer-value url-password]
     :url-password url-password
     :message      (str "GH_TOKEN=" gh-token
                        " ANTHROPIC_API_KEY=" anthropic-key
                        " remote https://user:" url-password "@github.com/o/r.git"
                        " header Authorization: Bearer " bearer-value
                        " auth x-access-token:" gh-token)
     :data         {:push-url     (str "https://x-access-token:" gh-token "@github.com/o/r.git")
                    :github-token "opaque-value-only-its-key-names"
                    :env          {"ANTHROPIC_API_KEY" anthropic-key}}}))

(def ^{:stratum 0} ordinary-data
  "Text and numbers that resemble nothing secret and must survive."
  {:sha "3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b"
   :uuid "123e4567-e89b-12d3-a456-426614174000"
   :note "the token budget was exceeded; refresh the token"
   :tokens 8000})

(defn- ^{:stratum 0} temp-log-file
  "A fresh path for a log file that does not exist yet."
  []
  (let [f (java.io.File/createTempFile "miniforge-log-redaction" ".log")]
    (.deleteOnExit f)
    (io/delete-file f)
    f))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leaked
  "The fixture secrets that appear in TEXT."
  [text]
  (filterv #(str/includes? text %) (:secrets fixture)))

(deftest ^{:stratum 1} ordinary-text-passes-unchanged-test
  (testing "a SHA, a UUID, the word token and a token count are not touched"
    (let [[logger entries] (log/collecting-logger)
          message "pushed 3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b with a fresh token"]
      (log/info logger :release :release/pushed {:message message :data ordinary-data})
      (let [entry (first @entries)]
        (is (= ordinary-data (:data entry)))
        (is (= message (:log/message entry)))
        (is (not (str/includes? (pr-str entry) (redaction/marker))))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} file-sink-writes-no-secret-test
  (testing "a workflow log file holds the marker, not the token"
    (let [file   (temp-log-file)
          logger (log/create-logger {:sinks   [(sinks/file-sink {:format :human})]
                                     :context {:workflow/id (random-uuid)}})]
      (with-redefs [sinks/log-file-path (fn [_workflow-id] (.getPath file))]
        (log/info logger :release :release/pushed (select-keys fixture [:message :data])))
      (let [text (slurp file)]
        (is (= [] (leaked text)))
        (is (str/includes? text (redaction/marker)))
        (is (str/includes? text ":github-token"))))))

(deftest ^{:stratum 2} stdout-writes-no-secret-test
  (testing "the default EDN output prints no token"
    (let [text (with-out-str
                 (log/info (log/create-logger {:output :edn}) :release :release/pushed
                           (select-keys fixture [:message :data])))]
      (is (= [] (leaked text)))
      (is (str/includes? text (redaction/marker)))))
  (testing "the stdout sink prints no token"
    (let [text (with-out-str
                 (log/info (log/create-logger {:sinks [(sinks/stdout-sink)]})
                           :release :release/pushed
                           (select-keys fixture [:message :data])))]
      (is (= [] (leaked text)))
      (is (str/includes? text (redaction/marker))))))

(deftest ^{:stratum 2} context-and-caller-output-get-no-secret-test
  (testing "a caller's output fn and the returned entry are redacted, context included"
    (let [[logger entries] (log/collecting-logger)
          logger   (log/with-context logger
                     {:ctx/remote (str "https://bot:" (:url-password fixture) "@example.com")})
          returned (log/warn logger :release :release/pushed
                             (select-keys fixture [:message :data]))]
      (is (= [] (leaked (pr-str @entries))))
      (is (= [] (leaked (pr-str returned))))
      (is (= (redaction/marker) (get-in returned [:data :github-token]))))))

(deftest ^{:stratum 2} exception-in-data-is-redacted-test
  (testing "an ex-info in :data loses the secrets in its message and ex-data, not its stack trace"
    (let [[gh-token _ _ url-password] (:secrets fixture)
          cause  (ex-info (str "auth failed for x-access-token:" gh-token)
                          {:remote (str "https://bot:" url-password "@github.com/o/r.git")})
          thrown (ex-info (str "push failed with GH_TOKEN=" gh-token)
                          {:github-token "opaque-value-only-its-key-names"}
                          cause)
          [logger entries] (log/collecting-logger)
          returned (log/error logger :release :release/push-failed {:data {:error thrown}})
          text   (with-out-str
                   (log/error (log/create-logger {:output :edn}) :release :release/push-failed
                              {:data {:error thrown}}))
          error  (get-in returned [:data :error])]
      (is (= [] (leaked (pr-str @entries))))
      (is (= [] (leaked text)))
      (is (map? error) "the exception is logged as data")
      (is (seq (:trace error)) "the stack trace is kept")
      (is (= 2 (count (:via error))) "every cause is kept")
      (is (= (redaction/marker) (get-in error [:via 0 :data :github-token])))
      (is (str/starts-with? (get-in error [:via 0 :message]) "push failed with GH_TOKEN=")))))

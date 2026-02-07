;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.tui-views.command-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.command :as cmd]
   [ai.miniforge.tui-views.model :as model]))

(deftest parse-command-test
  (testing "Simple command"
    (is (= {:command :workflows :args []}
           (cmd/parse-command "workflows"))))

  (testing "Command with arguments"
    (is (= {:command :workflows :args ["status:blocked"]}
           (cmd/parse-command "workflows status:blocked"))))

  (testing "Quit variants"
    (is (= {:command :quit :args []} (cmd/parse-command "quit")))
    (is (= {:command :q :args []} (cmd/parse-command "q"))))

  (testing "Empty input returns nil"
    (is (nil? (cmd/parse-command "")))
    (is (nil? (cmd/parse-command nil)))))

(deftest execute-command-test
  (testing ":workflows switches to workflow list"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :workflows :args []})]
      (is (= :workflow-list (:view m)))))

  (testing ":evidence switches view"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :evidence :args []})]
      (is (= :evidence (:view m)))))

  (testing ":artifacts switches view"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :artifacts :args []})]
      (is (= :artifact-browser (:view m)))))

  (testing ":dag switches view"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :dag :args []})]
      (is (= :dag-kanban (:view m)))))

  (testing ":quit sets quit flag"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :quit :args []})]
      (is (true? (:quit? m)))))

  (testing ":q also sets quit flag"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :q :args []})]
      (is (true? (:quit? m)))))

  (testing ":help sets flash message"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :help :args []})]
      (is (some? (:flash-message m)))))

  (testing "Unknown command shows error flash"
    (let [m (cmd/execute-command (model/init-model)
                                 {:command :bogus :args []})]
      (is (str/includes? (:flash-message m) "Unknown")))))

(deftest handle-command-submit-test
  (testing "Full command flow: buffer -> parse -> execute"
    (let [m (-> (model/init-model)
                (assoc :mode :command :command-buf ":evidence")
                cmd/handle-command-submit)]
      (is (= :evidence (:view m)))
      (is (= :normal (:mode m)))
      (is (= "" (:command-buf m))))))

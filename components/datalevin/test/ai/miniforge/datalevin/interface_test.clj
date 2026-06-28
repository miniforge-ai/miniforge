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

(ns ai.miniforge.datalevin.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.datalevin.interface :as dl])
  (:import [java.io File]))

(deftest round-trip
  (let [conn (dl/get-conn (dl/transient-dir)
                          {:thing/id {:db/unique :db.unique/identity}})]
    (try
      (dl/transact! conn [{:thing/id "a" :thing/name "Alpha"}])
      (testing "q over the db value"
        (is (= #{["Alpha"]}
               (dl/q '[:find ?n :where [?e :thing/name ?n]] (dl/db conn)))))
      (testing "entity-map returns a plain map without :db/id"
        (is (= {:thing/id "a" :thing/name "Alpha"}
               (dl/entity-map (dl/db conn) [:thing/id "a"]))))
      (testing "entity-map is nil for an absent lookup"
        (is (nil? (dl/entity-map (dl/db conn) [:thing/id "missing"]))))
      (finally (dl/close conn)))))

(deftest transient-dir-is-fresh-and-exists
  (let [a (dl/transient-dir)
        b (dl/transient-dir)]
    (is (not= a b) "each call yields a distinct directory")
    (is (.isDirectory (File. a)) "the directory is created")))

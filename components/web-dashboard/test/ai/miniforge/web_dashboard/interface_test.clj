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

(ns ai.miniforge.web-dashboard.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.web-dashboard.interface :as sut]))

(deftest server-lifecycle-test
  (testing "Server starts and stops"
    (let [server (sut/start! {:port 0})]  ; Port 0 = random available port
      (try
        (is (some? server))
        (is (number? (sut/get-port server)))
        (is (pos? (sut/get-port server)))
        (finally
          (sut/stop! server))))))

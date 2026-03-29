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

(ns ai.miniforge.web-dashboard.server.auth-test
  "Route-level tests for dashboard login."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.server :as server]
   [ai.miniforge.web-dashboard.server.auth :as auth]
   [ai.miniforge.web-dashboard.state.core :as state-core]))

;------------------------------------------------------------------------------ Layer 0
;; Test helpers

(defn fresh-state
  []
  (state-core/create-state
   {:auth (auth/build-auth-state
           {:users [{:username "alice"
                     :password "wonderland"
                     :role :operator}]})}))

(defn body-stream
  [content]
  (java.io.ByteArrayInputStream. (.getBytes content "UTF-8")))

(defn form-request
  [uri body & [headers]]
  {:uri uri
   :request-method :post
   :headers (merge {"content-type" "application/x-www-form-urlencoded"} headers)
   :body (body-stream body)})

;------------------------------------------------------------------------------ Layer 1
;; Route tests

(deftest protected-route-redirects-to-login-test
  (testing "GET / redirects to login when auth is enabled"
    (let [handler (server/create-handler (fresh-state))
          response (handler {:uri "/" :request-method :get :headers {}})]
      (is (= 302 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Location"]) "/login?return-to=")))))

(deftest login-flow-test
  (testing "GET /login serves a login form"
    (let [handler (server/create-handler (fresh-state))
          response (handler {:uri "/login" :request-method :get :headers {}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Sign in to the dashboard"))))

  (testing "POST /login with valid credentials sets a session cookie"
    (let [state (fresh-state)
          handler (server/create-handler state)
          login-response (handler (form-request "/login"
                                                "username=alice&password=wonderland&return-to=%2Ffleet"))
          session-cookie (get-in login-response [:headers "Set-Cookie"])
          fleet-response (handler {:uri "/fleet"
                                   :request-method :get
                                   :headers {"cookie" session-cookie}})]
      (is (= 302 (:status login-response)))
      (is (= "/fleet" (get-in login-response [:headers "Location"])))
      (is (str/includes? session-cookie "miniforge-dashboard-session="))
      (is (= 200 (:status fleet-response)))))

  (testing "POST /login with invalid credentials returns 401"
    (let [handler (server/create-handler (fresh-state))
          response (handler (form-request "/login"
                                          "username=alice&password=incorrect&return-to=%2F"))]
      (is (= 401 (:status response)))
      (is (str/includes? (:body response) "Invalid username or password.")))))

(deftest logout-clears-session-test
  (testing "POST /logout clears the current session cookie"
    (let [state (fresh-state)
          handler (server/create-handler state)
          login-response (handler (form-request "/login"
                                                "username=alice&password=wonderland&return-to=%2F"))
          session-cookie (get-in login-response [:headers "Set-Cookie"])
          logout-response (handler {:uri "/logout"
                                    :request-method :post
                                    :headers {"cookie" session-cookie}
                                    :body (body-stream "")})
          after-logout (handler {:uri "/workflows"
                                 :request-method :get
                                 :headers {"cookie" session-cookie}})]
      (is (= 302 (:status logout-response)))
      (is (= "/login" (get-in logout-response [:headers "Location"])))
      (is (str/includes? (get-in logout-response [:headers "Set-Cookie"]) "Max-Age=0"))
      (is (= 302 (:status after-logout))))))

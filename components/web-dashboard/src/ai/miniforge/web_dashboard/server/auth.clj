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

(ns ai.miniforge.web-dashboard.server.auth
  "Dashboard-local authentication and session management."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.server.filters :as filters]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.views.auth :as auth-views]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(def default-cookie-name "miniforge-dashboard-session")
(def default-session-ttl-ms (* 12 60 60 1000))

(defn- utf8-bytes
  [value]
  (.getBytes (str (or value "")) "UTF-8"))

(defn- constant-time=
  [left right]
  (java.security.MessageDigest/isEqual (utf8-bytes left) (utf8-bytes right)))

(defn- env-auth-config
  []
  (let [username (System/getenv "MINIFORGE_WEB_USERNAME")
        password (System/getenv "MINIFORGE_WEB_PASSWORD")]
    (when (and (seq username) (seq password))
      {:users [{:username username
                :password password
                :role :operator}]})))

(defn- normalize-user-entry
  [[username config]]
  (let [entry (if (map? config) config {:password config})
        uname (or (:username entry) username)]
    (when (and (seq uname) (seq (:password entry)))
      [uname {:username uname
              :password (:password entry)
              :display-name (or (:display-name entry) uname)
              :role (get entry :role :operator)}])))

(defn- normalize-users
  [auth-config]
  (let [users (:users auth-config)]
    (cond
      (and (seq (:username auth-config)) (seq (:password auth-config)))
      {(str (:username auth-config))
       {:username (str (:username auth-config))
        :password (:password auth-config)
        :display-name (or (:display-name auth-config) (str (:username auth-config)))
        :role (get auth-config :role :operator)}}

      (map? users)
      (into {}
            (keep normalize-user-entry)
            users)

      (sequential? users)
      (into {}
            (keep (fn [entry]
                    (when (and (map? entry) (seq (:username entry)) (seq (:password entry)))
                      [(:username entry)
                       {:username (:username entry)
                        :password (:password entry)
                        :display-name (or (:display-name entry) (:username entry))
                        :role (get entry :role :operator)}])))
            users)

      :else
      {})))

(defn parse-cookie-header
  "Parse a Cookie header into a string-keyed map."
  [cookie-header]
  (if (str/blank? cookie-header)
    {}
    (reduce (fn [acc cookie-part]
              (let [[raw-name raw-value] (str/split cookie-part #"=" 2)
                    name (some-> raw-name str/trim)
                    value (some-> raw-value str/trim)]
                (if (seq name)
                  (assoc acc name (or value ""))
                  acc)))
            {}
            (str/split cookie-header #";"))))

(defn- safe-return-to
  [value]
  (let [path (or value "/")]
    (if (and (string? path)
             (str/starts-with? path "/")
             (not (str/starts-with? path "//")))
      path
      "/")))

(defn- login-page-response
  [{:keys [error username return-to status]}]
  (assoc (responses/html-response
          (auth-views/login-view {:error error
                                  :username username
                                  :return-to (safe-return-to return-to)}))
         :status (or status 200)))

(defn- redirect-response
  [location & [headers]]
  {:status 302
   :headers (merge {"Location" location} headers)
   :body ""})

(defn- session-cookie-value
  [{:keys [cookie-name session-ttl-ms]} token]
  (str cookie-name "=" token
       "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" (quot session-ttl-ms 1000)))

(defn- clear-cookie-value
  [{:keys [cookie-name]}]
  (str cookie-name "=deleted; Path=/; HttpOnly; SameSite=Lax; Max-Age=0"))

;------------------------------------------------------------------------------ Layer 1
;; State and session lifecycle

(defn build-auth-state
  "Normalize dashboard auth config.

   Supported inputs:
   - {:username \"admin\" :password \"secret\"}
   - {:users {\"admin\" {:password \"secret\"}}}
   - {:users [{:username \"admin\" :password \"secret\" :role :operator}]}

   Falls back to MINIFORGE_WEB_USERNAME / MINIFORGE_WEB_PASSWORD when unset."
  [auth-config]
  (let [raw-config (or auth-config (env-auth-config) {})
        users (normalize-users raw-config)]
    {:enabled? (boolean (seq users))
     :cookie-name (or (:cookie-name raw-config) default-cookie-name)
     :session-ttl-ms (or (:session-ttl-ms raw-config) default-session-ttl-ms)
     :users users
     :sessions (atom {})}))

(defn enabled?
  "True when dashboard login is configured."
  [auth-state]
  (boolean (:enabled? auth-state)))

(defn public-request?
  "Requests that must stay available without a browser session."
  [{:keys [uri request-method]}]
  (or (= uri "/health")
      (and (= uri "/login") (#{:get :post} request-method))
      (and (= uri "/logout") (= :post request-method))
      (= uri "/ws/events")
      (and (= uri "/api/events/ingest") (= :post request-method))
      (and (= uri "/api/control-plane/agents/register") (= :post request-method))
      (and (.startsWith uri "/api/control-plane/agents/")
           (.endsWith uri "/heartbeat")
           (= :post request-method))
      (.startsWith uri "/css/")
      (.startsWith uri "/js/")
      (.startsWith uri "/img/")))

(defn current-session
  "Return the current valid session map from the request cookie."
  [auth-state req]
  (when (enabled? auth-state)
    (let [token (get (parse-cookie-header (get-in req [:headers "cookie"]))
                     (:cookie-name auth-state))
          session (get @(:sessions auth-state) token)
          now (System/currentTimeMillis)]
      (when session
        (if (< (- now (:last-seen-at session)) (:session-ttl-ms auth-state))
          (let [updated (assoc session :last-seen-at now)]
            (swap! (:sessions auth-state) assoc token updated)
            updated)
          (do
            (swap! (:sessions auth-state) dissoc token)
            nil))))))

(defn authenticate!
  "Authenticate credentials and create a session when valid."
  [auth-state username password]
  (when (enabled? auth-state)
    (when-let [user (get (:users auth-state) username)]
      (when (constant-time= password (:password user))
        (let [now (System/currentTimeMillis)
              token (str (random-uuid))
              session {:token token
                       :username (:username user)
                       :display-name (:display-name user)
                       :role (:role user)
                       :created-at now
                       :last-seen-at now}]
          (swap! (:sessions auth-state) assoc token session)
          session)))))

(defn clear-session!
  "Delete any session referenced by the request cookie."
  [auth-state req]
  (when (enabled? auth-state)
    (when-let [token (get (parse-cookie-header (get-in req [:headers "cookie"]))
                          (:cookie-name auth-state))]
      (swap! (:sessions auth-state) dissoc token))))

;------------------------------------------------------------------------------ Layer 2
;; Request handling

(defn handle-login-page
  "Serve the login page or redirect an already authenticated user."
  [auth-state req]
  (if-not (enabled? auth-state)
    (redirect-response "/")
    (if (current-session auth-state req)
      (redirect-response "/")
      (login-page-response {:return-to (get (filters/query-string->params (:query-string req))
                                            "return-to"
                                            "/")}))))

(defn handle-login-submit
  "Process login form submission."
  [auth-state req]
  (let [params (filters/query-string->params (slurp (:body req)))
        username (get params "username")
        password (get params "password")
        return-to (safe-return-to (get params "return-to"))]
    (if-not (enabled? auth-state)
      (redirect-response "/")
      (if-let [session (authenticate! auth-state username password)]
        (redirect-response return-to
                           {"Set-Cookie" (session-cookie-value auth-state (:token session))})
        (login-page-response {:status 401
                              :error "Invalid username or password."
                              :username username
                              :return-to return-to})))))

(defn handle-logout
  "Clear the current session and redirect to the login page."
  [auth-state req]
  (clear-session! auth-state req)
  (redirect-response "/login"
                     {"Set-Cookie" (clear-cookie-value auth-state)}))

(defn unauthorized-response
  "Return an auth challenge response for the current request."
  [_auth-state req]
  (let [return-to (safe-return-to
                   (str (:uri req)
                        (when-let [qs (:query-string req)]
                          (when (seq qs) (str "?" qs)))))]
    (if (and (= :get (:request-method req))
             (not (.startsWith (:uri req) "/api/"))
             (not= (:uri req) "/ws"))
      (redirect-response (str "/login?return-to="
                              (java.net.URLEncoder/encode return-to "UTF-8")))
      {:status 401
       :headers {"Content-Type" "application/json"
                 "HX-Redirect" (str "/login?return-to="
                                    (java.net.URLEncoder/encode return-to "UTF-8"))}
       :body (json/generate-string {:error "authentication-required"})})))

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

(ns ai.miniforge.web-dashboard.views.auth
  "Login view for the web dashboard."
  (:require
   [hiccup.page :as page]))

;------------------------------------------------------------------------------ Layer 0
;; View

(defn login-view
  "Render the login page."
  [{:keys [error username return-to]}]
  (page/html5
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title "Miniforge | Login"]
    [:link {:rel "stylesheet" :href "/css/app.css"}]]
   [:body
    [:div.dashboard
     [:main.main
      [:div.page-header
       [:h1.page-title "User Login"]]
      [:section.section
       [:div.cp-page
        [:div.cp-header
         [:h2 "Sign in to the dashboard"]
         [:p.cp-subtitle "Use a configured dashboard account to continue."]]
        [:div.cp-two-column
         [:div.cp-column-main
          [:div.cp-section
           (when error
             [:div.empty-state
              [:h3 "Sign-in failed"]
              [:p error]])
           [:form.cp-decision-form {:method "post" :action "/login"}
            [:input {:type "hidden"
                     :name "return-to"
                     :value (or return-to "/")}]
            [:input.cp-input {:type "text"
                              :name "username"
                              :placeholder "Username"
                              :value (or username "")
                              :autocomplete "username"
                              :required true}]
            [:input.cp-input {:type "password"
                              :name "password"
                              :placeholder "Password"
                              :autocomplete "current-password"
                              :required true}]
            [:button.btn.btn-sm.btn-primary {:type "submit"} "Sign in"]]]]
         [:div.cp-column-sidebar
          [:div.cp-section
           [:h3 "Session policy"]
           [:p "Dashboard sessions are browser-local and cookie-backed."]
           [:p "Static assets and health checks remain public; dashboard views require sign-in when auth is enabled."]]]]]]]]]))

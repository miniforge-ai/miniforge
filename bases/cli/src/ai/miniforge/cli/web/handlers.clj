(ns ai.miniforge.cli.web.handlers
  "HTTP route handlers."
  (:require
   [clojure.string :as str]
   [cheshire.core :as json]
   [hiccup2.core :as h]
   [ai.miniforge.cli.web.response :as response]
   [ai.miniforge.cli.web.github :as github]
   [ai.miniforge.cli.web.fleet :as fleet]
   [ai.miniforge.cli.web.components :as c]
   [ai.miniforge.cli.web.sse :as sse]))

(defn- parse-pr-path [uri]
  (let [path-parts (str/split (subs uri 8) #"/")]
    {:repo (java.net.URLDecoder/decode (first path-parts) "UTF-8")
     :number (Integer/parseInt (second path-parts))}))

(defn- parse-body-question [req]
  (when-let [body-str (some-> req :body slurp)]
    (cond
      (str/starts-with? (str/trim body-str) "{")
      (get (json/parse-string body-str) "question")

      (str/includes? body-str "=")
      (->> (str/split body-str #"&")
           (keep (fn [pair]
                   (let [[k v] (str/split pair #"=" 2)]
                     (when (and k v (= k "question"))
                       (java.net.URLDecoder/decode v "UTF-8")))))
           first))))

(defn index [repos]
  (->> (c/dashboard (github/fetch-all-prs repos) nil (fleet/get-status repos))
       c/page
       response/html))

(defn refresh [repos]
  (let [repos-with-prs (github/fetch-all-prs repos)]
    (->> (str (c/repo-tree repos-with-prs nil)
              (h/html [:div#detail-panel.detail-panel (c/empty-detail)]))
         response/html)))

(defn pr-detail [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        pr (->> (github/fetch-prs repo)
                (filter #(= (:number %) number))
                first)]
    (if pr
      (response/html (c/pr-detail pr))
      (response/not-found "PR not found"))))

(defn approve [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        result (github/approve-pr! repo number)]
    (response/html (c/toast (:message result) (:success result)))))

(defn reject [uri req]
  (let [{:keys [repo number]} (parse-pr-path uri)
        reason (get-in req [:headers "hx-prompt"] "Changes requested")
        result (github/request-changes! repo number reason)]
    (response/html (c/toast (:message result) (:success result)))))

(defn batch-approve [repos]
  (let [safe-prs (->> (github/fetch-all-prs repos)
                      (mapcat :prs)
                      (filter #(= :low (get-in % [:analysis :risk]))))
        results (doall (map #(github/approve-pr! (:repo %) (:number %)) safe-prs))
        success-count (count (filter :success results))]
    (response/html (c/toast (str "Approved " success-count " of " (count safe-prs) " PRs")
                            (= success-count (count safe-prs))))))

(defn chat [uri req]
  (let [{:keys [repo number]} (parse-pr-path uri)
        question (or (parse-body-question req) "What are the key changes in this PR?")
        diff (github/fetch-pr-diff repo number)
        resp (if diff
               (:response (github/chat-about-pr repo number question diff))
               "Could not fetch PR diff. Make sure you have access to this repository.")]
    (response/html (c/chat-message question resp))))

(defn status [repos]
  (->> (fleet/get-status repos)
       c/status-indicator
       response/html))

(defn summary [uri]
  (let [{:keys [repo number]} (parse-pr-path uri)
        result (github/generate-pr-summary repo number)]
    (response/html
     (if (:success result)
       (c/ai-summary result)
       (c/ai-summary-error (:summary result))))))

(defn workflows [repos]
  (response/html (c/workflow-status repos)))

(defn workflow-stream [uri req]
  (let [workflow-id-str (second (re-find #"/api/workflows/([^/]+)/stream" uri))
        workflow-id (try (java.util.UUID/fromString workflow-id-str)
                         (catch Exception _ nil))]
    (if-not workflow-id
      (response/bad-request "Invalid workflow ID")
      (sse/handle-stream workflow-id req))))

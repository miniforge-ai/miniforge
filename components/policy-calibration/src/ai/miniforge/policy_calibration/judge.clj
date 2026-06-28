(ns ai.miniforge.policy-calibration.judge
  "The LLM-as-judge for calibration: one batched call per fixture over all
   candidate rules, mapping the outcome to a calibration cell — a success value
   or an anomaly (backend failure / unparseable output). The LLM client and
   completion fn are injected, so this stays testable with a stub completion."
  (:require [ai.miniforge.policy-calibration.core :as core]
            [ai.miniforge.policy-pack.interface :as pp]
            [ai.miniforge.llm.interface :as llm]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private prompt-resource "policy-calibration/judge-prompt.edn")

(def ^:private templates
  "Judge prompt templates, loaded once from the classpath."
  (delay (edn/read-string (slurp (io/resource prompt-resource)))))

(defn- ->rule-kw
  "Coerce a model-emitted :rule-id (keyword, \":std/foo\" or \"std/foo\") to a keyword."
  [x]
  (cond (keyword? x) x
        (string? x)  (keyword (str/replace x #"^:" ""))
        :else        nil))

(defn- parse-violations
  "Parse the judge's response into a vector of violation maps, or ::unparseable."
  [content]
  (try
    (let [s (-> (or content "") (str/replace #"(?s)```(?:edn|clojure)?" "") str/trim)
          v (edn/read-string s)]
      (if (vector? v) (filterv map? v) ::unparseable))
    (catch Exception _
      (try (if-let [m (re-find #"(?s)\[.*\]" (or content ""))]
             (filterv map? (edn/read-string m))
             ::unparseable)
           (catch Exception _ ::unparseable)))))

(defn- render-rule
  "Render one rule as a `### <id>` heading block via the :rule-item template."
  [item rule]
  (pp/interpolate item
                  {:rule-id           (str (:rule/id rule))
                   :rule-title        (get rule :rule/title "")
                   :rule-description  (get rule :rule/description "")
                   :knowledge-content (get rule :rule/knowledge-content "")}))

(defn- rules-block [rules]
  (let [item (get @templates :rule-item "")]
    (str/join "\n\n" (map #(render-rule item %) rules))))

(defn- build-prompt [rules path content]
  (let [t @templates]
    {:system (get t :system "")
     :user   (pp/interpolate (get t :user "")
                             {:file-path path :file-content content
                              :rules-block (rules-block rules)})}))

(defn- rule-id->message-pair
  "[(->rule-kw rule-id) message] for a violation, or nil if it carries no rule-id."
  [violation]
  (when-let [k (->rule-kw (:rule-id violation))]
    [k (:message violation)]))

(defn- response->cell
  "Map an llm response to a calibration cell."
  [response]
  (if-not (llm/success? response)
    (core/backend-error "judge backend failed" {:error (:error response)})
    (let [viols (parse-violations (:content response))]
      (if (= ::unparseable viols)
        (core/format-error "judge output not parseable as EDN" {})
        (core/cell-success
         (keep (comp ->rule-kw :rule-id) viols)
         (keep rule-id->message-pair viols))))))

(defn make-judge
  "Build a calibration judge `(fn [rules fixture] -> cell)`. `client` is the llm
   client; `complete-fn` the completion fn (fn [client request] -> response)."
  [client complete-fn]
  (fn [rules fixture]
    (let [{:keys [system user]} (build-prompt rules (:rel fixture) (:content fixture))]
      (response->cell
       (complete-fn client {:system system :messages [{:role "user" :content user}]})))))

(comment
  ;; Build a judge with a stub completion to exercise response mapping:
  (let [j (make-judge :client (fn [_ _] {:success true :content "[{:rule-id :std/x :message \"m\"}]"}))]
    (j [{:rule/id :std/x}] {:rel "a.clj" :content "(def x 1)"}))
  :leave-this-here)

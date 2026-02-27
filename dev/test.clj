(ns dev.test
  (:require [clojure.string :as str]))

(defn- extract-check-context [checks]
  (str/join ", " (map #(str (:name %) "=" (-> % (get :conclusion :unknown) name)) checks)))

(defn build-pr-context-str
  "Build a text summary of PR data for the LLM system prompt."
  [{
    :keys [pr/behind-main? 
           pr/branch
           pr/ci-status
           pr/number
           pr/policy
           pr/readiness
           pr/repo
           pr/risk
           pr/status
           pr/title]
    :or   {
           ci-status :unknown
           status    :unknown}
    :as   pr}]
  (when pr
    (let [checks                                                (get pr :pr/ci-checks [])
          {:keys [readiness/score readiness/ready?]}            readiness
          {:keys [evaluation/passed? evaluation/packs-applied]} policy 
          risk-level                                            (get risk :risk/level :unkonwn)
          risk-score                                            (:risk/score risk)
          ci-str                                                (if (seq checks)
                                                                  (str "CI checks: "
                                                                       (extract-check-context checks))
                                                                  (str "CI status: " (name ci-status)))]
      (str "PR: " repo "#" 
           (number " — " title "\n"
                   "Branch: " branch "\n"
                   "Status: " (name status) "\n"
                   ci-str "\n"
                   "Behind main: " (if behind-main? "yes" "no") "\n"

                   (when readiness
                     (str "Readiness score: " score
                          (when ready? " (ready)")
                          "\n"))
                   
                   (when risk
                     (str "Risk level: " (name risk-level)
                          (when risk-score 
                            (str " (score: " (format "%.2f" (double risk-score)) ")"))
                          "\n"))
                   
                   (when policy
                     (str "Policy: " (if passed? "passed" "FAILED")
                          (when-let [packs packs-applied]
                            (str " (packs: " (str/join ", " packs) ")"))
                          "\n")))))))
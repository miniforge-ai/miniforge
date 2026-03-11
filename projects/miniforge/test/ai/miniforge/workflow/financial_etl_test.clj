(ns ai.miniforge.workflow.financial-etl-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.workflow.configurable :as configurable]
   [ai.miniforge.workflow.loader :as loader]
   [ai.miniforge.workflow.publish :as publish]
   [babashka.fs :as fs]))

(defn latest-artifact
  [exec-state artifact-type]
  (->> (:execution/artifacts exec-state)
       (filter #(= artifact-type (:artifact/type %)))
       last))

(defn build-etl-handlers
  [publisher]
  {:etl/acquire
   (fn [_phase exec-state _context]
     {:success? true
      :artifacts [(artifact/build-artifact
                   {:id (random-uuid)
                    :type :etl/raw-filing
                    :version "1.0.0"
                    :content {:issuer (get-in exec-state [:execution/input :issuer])
                              :filing/type "10-K"
                              :filing/text "Cash 100, Inventory 40, Total Liabilities 30"}
                    :metadata {:stage :acquire}})]
      :errors []
      :metrics {:duration-ms 5}})

   :etl/extract
   (fn [_phase exec-state _context]
     (let [parent (latest-artifact exec-state :etl/raw-filing)]
       {:success? true
        :artifacts [(artifact/build-artifact
                     {:id (random-uuid)
                      :type :etl/extracted-facts
                      :version "1.0.0"
                      :parents [(:artifact/id parent)]
                      :content {:cash 100
                                :inventory 40
                                :total-liabilities 30}
                      :metadata {:stage :extract}})]
        :errors []
        :metrics {:duration-ms 5}}))

   :etl/canonicalize
   (fn [_phase exec-state _context]
     (let [parent (latest-artifact exec-state :etl/extracted-facts)]
       {:success? true
        :artifacts [(artifact/build-artifact
                     {:id (random-uuid)
                      :type :etl/canonical-record
                      :version "1.0.0"
                      :parents [(:artifact/id parent)]
                      :content {:issuer "ACME"
                                :assets/current {:cash 100 :inventory 40}
                                :liabilities/total 30}
                      :metadata {:stage :canonicalize}})]
        :errors []
        :metrics {:duration-ms 5}}))

   :etl/evaluate
   (fn [_phase exec-state _context]
     (let [parent (latest-artifact exec-state :etl/canonical-record)]
       {:success? true
        :artifacts [(artifact/build-artifact
                     {:id (random-uuid)
                      :type :etl/valuation
                      :version "1.0.0"
                      :parents [(:artifact/id parent)]
                      :content {:ncav 110
                                :forced-liquidation-estimate 98
                                :confidence-score 0.82}
                      :metadata {:stage :evaluate
                                 :policy-pack :demo/liquidation-v1}})]
        :errors []
        :metrics {:duration-ms 5}}))

   :etl/publish-report
   (fn [_phase exec-state context]
     (let [valuation (latest-artifact exec-state :etl/valuation)
           publication (publish/publish! publisher
                                         {:publication/path "reports/acme-report.edn"
                                          :publication/content (:artifact/content valuation)}
                                         (:logger context))]
       {:success? (:success? publication)
        :artifacts [(artifact/build-artifact
                     {:id (random-uuid)
                      :type :etl/published-report
                      :version "1.0.0"
                      :parents [(:artifact/id valuation)]
                      :content publication
                      :metadata {:stage :publish-report}})]
        :errors (if (:success? publication)
                  []
                  [{:type :publication-failed
                    :message (:error publication)}])
        :metrics {:duration-ms 5}}))})

(deftest financial-etl-workflow-test
  (testing "financial ETL runs end-to-end with local artifacts and directory publication"
    (let [temp-dir (fs/create-temp-dir {:prefix "financial-etl-"})
          workflow (:workflow (loader/load-workflow :financial-etl "1.0.0" {}))
          publisher (publish/create-directory-publisher temp-dir)
          result (configurable/run-configurable-workflow
                  workflow
                  {:issuer "ACME"}
                  {:phase-handlers (build-etl-handlers publisher)})]
      (try
        (is (= :completed (:execution/status result)))
        (is (= #{:acquire :extract :canonicalize :evaluate :publish-report}
               (set (keys (:execution/phase-results result)))))
        (is (= 5 (count (:execution/artifacts result))))
        (is (fs/exists? (fs/path temp-dir "reports" "acme-report.edn")))
        (let [published (latest-artifact result :etl/published-report)
              valuation (latest-artifact result :etl/valuation)
              canonical (latest-artifact result :etl/canonical-record)
              extracted (latest-artifact result :etl/extracted-facts)]
          (is (= [(:artifact/id valuation)] (:artifact/parents published)))
          (is (= [(:artifact/id canonical)] (:artifact/parents valuation)))
          (is (= [(:artifact/id extracted)] (:artifact/parents canonical))))
        (is (= {:ncav 110
                :forced-liquidation-estimate 98
                :confidence-score 0.82}
               (read-string (slurp (str (fs/path temp-dir "reports" "acme-report.edn"))))))
        (finally
          (fs/delete-tree temp-dir))))))

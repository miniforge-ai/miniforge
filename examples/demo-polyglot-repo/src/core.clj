(ns demo.core
  "Portfolio analysis — demo file with intentional violations")

;; VIOLATION: (or (:key m) default) should be (get m :key default)
(defn portfolio-value [portfolio]
  (let [currency (or (:currency portfolio) "USD")
        shares   (or (:shares portfolio) 0)]
    {:currency currency
     :total    (* shares (or (:price portfolio) 0.0))}))

;; VIOLATION: inline anonymous function in pipeline
(defn top-holdings [portfolios n]
  (->> portfolios
       (mapcat (fn [p]
                 (map (fn [h]
                        (assoc h :portfolio-name (:name p)))
                      (:holdings p))))
       (sort-by :value >)
       (take n)))

;; VIOLATION: TODO without tracked issue
(defn calculate-risk-score [holdings]
  ;; TODO: implement VaR calculation
  (reduce + (map :weight holdings)))

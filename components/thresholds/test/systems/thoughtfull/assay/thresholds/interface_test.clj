(ns systems.thoughtfull.assay.thresholds.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.thresholds.interface :as thresholds]))

(defn- measurement
  ([name metrics]
   (measurement name :component metrics))
  ([name type metrics]
   {:brick {:name name :type type}
    :metrics metrics}))

(defn- check
  "Violations of config, merged over the defaults, as [brick level
  message]."
  ([config measurements]
   (check config measurements {}))
  ([config measurements findings]
   (map (juxt (comp :name :brick) :metric :level :message)
     (:violations (thresholds/check (thresholds/merge-config config)
                    measurements findings)))))

(defn- only
  "config with every default metric off, then config's metrics."
  [config]
  (merge-with merge
    (update-vals thresholds/default-config #(update-vals % (constantly nil)))
    config))

(deftest merge-config-test
  (let [merged (thresholds/merge-config
                 {:complexity {:function-depth {:error 12}
                               :function-forms nil}})]
    (is (= {:warning 8 :error 12} (thresholds/settings merged :function-depth))
      "settings merge over the defaults")
    (is (nil? (thresholds/settings merged :function-forms)) "nil turns it off")
    (is (= {:error 10} (thresholds/settings merged :function-complexity)))
    (is (= {:warning 0.5 :since "12 months" :min-shared 5
            :max-bricks-per-commit 5}
          (thresholds/settings merged :co-change))
      "options are settings too")))

(deftest invalid-config-test
  (let [invalid? (fn [re config]
                   (is (thrown-with-msg? clojure.lang.ExceptionInfo re
                         (thresholds/merge-config config))))]
    (invalid? #"older version of assay"
      {:function-thresholds {:complexity [{:rule :max :value 10}]}})
    (invalid? #"Unknown section :functions" {:functions {}})
    (invalid? #"Unknown metric :nope under :complexity"
      {:complexity {:nope {:warning 1}}})
    (invalid? #":broad-catches belongs under :errors, not :io"
      {:io {:broad-catches {:warning 0}}})
    (invalid? #"Unknown setting :max for :function-depth"
      {:complexity {:function-depth {:max 3}}})
    (invalid? #"must be a number"
      {:complexity {:function-depth {:warning "8"}}})
    (invalid? #"applies only to a brick's own value"
      {:complexity {:function-depth {:warning {:std-devs 2}}}}))
  (is (= ::invalid
        (try (thresholds/merge-config {:nope {}})
          (catch clojure.lang.ExceptionInfo e
            (when (keyword? (:type (ex-data e))) ::invalid))))
    "the error is typed"))

(deftest brick-test
  (testing ":max and :min come from the metric"
    (is (= [["b" :cohesion :warning "0.4 is below the minimum of 0.5"]
            ["a" :interop-density :warning "6 is above the maximum of 5"]]
          (check (only {:io {:interop-density {:warning 5}}
                        :modularity {:cohesion {:warning 0.5}}})
            [(measurement "a" {:interop-density 6 :cohesion 0.9
                               :workspace-references 10})
             (measurement "b" {:interop-density 1 :cohesion 0.4
                               :workspace-references 10})]))))
  (testing "a value past both thresholds is an error"
    (is (= [["a" :interop-density :error "20 is above the maximum of 10"]]
          (check (only {:io {:interop-density {:warning 5 :error 10}}})
            [(measurement "a" {:interop-density 20})]))))
  (testing "only the brick types the metric checks"
    (is (empty? (check (only {:modularity {:cohesion {:warning 0.5}}})
                  [(measurement "cli" :base {:cohesion 0.1
                                             :workspace-references 10})]))))
  (testing "only bricks past the metric's gate"
    (is (empty? (check (only {:modularity {:cohesion {:warning 0.5}}})
                  [(measurement "a" {:cohesion 0.1
                                     :workspace-references 9})])))))

(deftest std-devs-test
  (let [config (only {:complexity {:mean-function-complexity
                                   {:warning {:std-devs 2}}}})
        bricks (conj (mapv #(measurement (str "c" %)
                              {:mean-function-complexity % :functions 5})
                       [4 5 6 5])
                 (measurement "outlier" {:mean-function-complexity 20
                                         :functions 5}))]
    (testing "compares each brick with the others, excluding itself"
      (is (= [["outlier" :mean-function-complexity :warning
               (str "20 is 18.4 standard deviations above the mean of 4"
                 " other bricks (5 ± 0.82), over the limit of 2")]]
            (check config bricks))))
    (testing "reports the stats and the computed limit"
      (let [[v] (:violations (thresholds/check (thresholds/merge-config config)
                               bricks {}))]
        (is (= 4 (get-in v [:stats :peers])))
        (is (< 6.63 (:limit v) 6.64))))
    (testing "skips with too few peers"
      (is (empty? (check config (take-last 3 bricks))))
      (is (seq (check (only {:complexity {:mean-function-complexity
                                          {:warning {:std-devs 1
                                                     :min-peers 2}}}})
                 (take-last 3 bricks)))))
    (testing "identical peers"
      (is (= [["d" :mean-function-complexity :warning
               "2 is above every other brick (1)"]]
            (check config
              (for [[n v] [["a" 1] ["b" 1] ["c" 1] ["d" 2]]]
                (measurement n {:mean-function-complexity v
                                :functions 5}))))))
    (testing "bricks below the gate are neither checked nor peers"
      (is (= [["outlier" 3]]
            (map (juxt (comp :name :brick) (comp :peers :stats))
              (:violations
               (thresholds/check (thresholds/merge-config config)
                 (conj (vec (rest bricks))
                   (measurement "small" {:mean-function-complexity 30
                                         :functions 4}))
                 {})))))))
  (testing "a metric that checks components compares with components"
    (is (re-find #"above the mean of 5 other components"
          (last (first (check (only {:tests {:assertions-per-test
                                             {:warning {:std-devs 2}}}})
                         (concat (for [i (range 5)]
                                   (measurement (str "c" i)
                                     {:assertions-per-test (+ 2 (mod i 2))
                                      :tests 10}))
                           [(measurement "big" {:assertions-per-test 12
                                                :tests 10})
                            (measurement "cli" :base
                              {:assertions-per-test 500 :tests 10})]))))))))

(deftest function-test
  (let [bricks [{:brick {:name "a" :type :component}
                 :metrics {}
                 :functions [{:name "f" :ns 'a.core :file "a.clj" :line 3
                              :complexity 12 :depth 9 :depth-line 7}
                             {:name "g" :ns 'a.core :file "a.clj" :line 20
                              :complexity 2 :depth 2 :depth-line 21}]}]
        {:keys [bricks violations]}
        (thresholds/check
          (thresholds/merge-config
            (only {:complexity {:function-complexity {:error 10}
                                :function-depth {:warning 8}}}))
          bricks {})]
    (is (= [[:function-complexity :error "a.core/f"
             {:file "a.clj" :line 3 :name "f"} "12 is above the maximum of 10"]
            [:function-depth :warning "a.core/f"
             {:file "a.clj" :line 7 :name "f"} "9 is above the maximum of 8"]]
          (map (juxt :metric :level :subject :location :message) violations))
      "a function is identified by its namespace-qualified name, and depth
      is located at the deepest form")
    (is (= {:function-complexity 1 :function-depth 1 :function-forms nil}
          (select-keys (:metrics (first bricks))
            [:function-complexity :function-depth :function-forms]))
      "the brick's value counts its functions past the limit; nil when off")))

(deftest finding-test
  (let [b {:name "b" :type :component}
        findings {:positional-interface
                  [{:brick b :subject "wide" :value 6 :message "has 6"}
                   {:brick b :subject "narrow" :value 2 :message "has 2"}
                   {:brick {:name "cli" :type :base} :value 9 :message "x"}]}]
    (is (= [["b" :positional-interface :warning
             "has 6 (above the maximum of 4)"]]
          (check (only {:dependencies {:positional-interface {:warning 4}}})
            [] findings))
      "each finding's value against the limit; bases aren't checked")))

(deftest count-test
  (let [a {:name "a" :type :component}
        cli {:name "cli" :type :base}
        findings {:broad-catches [{:brick a :subject "1" :message "catches"}
                                  {:brick a :subject "2" :message "catches"}
                                  {:brick cli :subject "3" :message "catches"}]}
        result (fn [config]
                 (thresholds/check (thresholds/merge-config (only config))
                   [(measurement "a" {}) (measurement "cli" :base {})]
                   findings))]
    (testing "every finding is a violation when the brick's count is past"
      (is (= [["a" :warning "catches"] ["a" :warning "catches"]]
            (map (juxt (comp :name :brick) :level :message)
              (:violations (result {:errors {:broad-catches
                                             {:warning 0}}}))))))
    (testing "a limit above 0 is in the message"
      (is (= ["catches (2 in this brick, above the maximum of 1)"]
            (distinct (map :message
                        (:violations (result {:errors {:broad-catches
                                                       {:warning 1}}})))))))
    (testing "the brick's value is its count, even unchecked or off"
      (is (= [2 1]
            (map (comp :broad-catches :metrics)
              (:bricks (result {:errors {:broad-catches nil}}))))))))

(deftest defaults-test
  (testing "cohesion: components below 0.5"
    (is (= [["x" :cohesion :warning "0.4 is below the minimum of 0.5"]]
          (check {} [(measurement "x" {:cohesion 0.4
                                       :workspace-references 10})])))))

(deftest rows-test
  (let [a {:name "a" :type :component}
        b {:name "b" :type :component}
        f (fn [metric level value line]
            {:kind :function :metric metric :brick a :subject "a.core/f"
             :level level :value value :limit 1 :function-line 3
             :location {:file "a.clj" :line line :name "f"}})
        rows (thresholds/rows
               [(f :function-depth :warning 9 7)
                (f :function-complexity :error 12 3)
                {:metric :library-spread :brick a :subject "next.jdbc"
                 :level :warning :value 5 :limit 3
                 :location {:file "a.clj" :line 1}}
                {:metric :library-spread :brick b :subject "next.jdbc"
                 :level :warning :value 5 :limit 3
                 :location {:file "b.clj" :line 1}}
                {:metric :cohesion :brick b :level :warning :value 0.1
                 :limit 0.5}])]
    (is (= [[:function-rows :error ["a"] [{:file "a.clj" :line 3 :name "f"}]]
            [:cohesion :warning ["b"] []]
            [:library-spread :warning ["a" "b"]
             [{:file "a.clj" :line 1} {:file "b.clj" :line 1}]]]
          (map (juxt :group :level (comp #(map :name %) :bricks) :locations)
            rows))
      "a function's violations are one row, at its definition, with the
      worst level; a library's spread is one row across its bricks")
    (is (= [:complexity :modularity :io] (map :section rows))
      "a row's section is its metric's; errors first, then by how far
      past the limit")))

(deftest threshold-text-test
  (let [config (thresholds/merge-config
                 {:complexity {:function-depth {:error 12}}})]
    (is (= "warning > 8, error > 12"
          (thresholds/describe config :function-depth)))
    (is (= "warning < 0.5" (thresholds/describe config :cohesion)))
    (is (= "warning > mean + 2σ"
          (thresholds/describe config :assertions-per-test)))
    (is (nil? (thresholds/describe config :forms)))))

(deftest worse-level-test
  (is (= :error (thresholds/worse-level :warning :error)))
  (is (= :warning (thresholds/worse-level nil :warning)))
  (is (nil? (thresholds/worse-level nil nil))))

(deftest by-severity-test
  (is (= [:big-error :small-error :big-warning :no-value]
        (map :id
          (thresholds/by-severity
            [{:id :no-value :level :warning}
             {:id :big-warning :level :warning :value 40 :limit 10}
             {:id :small-error :level :error :value 11 :limit 10}
             {:id :big-error :level :error :value 0.1 :limit 0.5}])))))

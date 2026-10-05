(ns systems.thoughtfull.assay.dependencies.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [systems.thoughtfull.assay.dependencies.interface :as dependencies]))

(def ^:private workspace
  {:top-namespace "t" :interface-ns "interface"})

(defn- source
  [ns forms & requires]
  {:file (str ns ".clj")
   :ns ns
   :forms forms
   :requires (mapv (fn [r] {:ns r :line 3}) requires)})

(defn- brick
  [name type & sources]
  {:brick {:name name :type type}
   :metrics {}
   :sources (vec sources)})

(def ^:private acyclic
  ;; a -> b, c -> a, c -> b, x -> c
  [(brick "a" :component
     (source 't.a.interface 10)
     (source 't.a.core 90 't.b.interface 'clojure.string))
   (brick "b" :component
     (source 't.b.interface 50)
     (source 't.b.core 50 't.b.interface))
   (brick "c" :component
     (source 't.c.interface 25 't.c.core)
     (source 't.c.core 75 't.a.interface 't.b.interface 't.b.interface))
   (brick "x" :base
     (source 't.x.main 10 't.c.interface))])

(defn- metrics-by-name
  [analysis]
  (into {} (map (juxt (comp :name :brick) :metrics)) (:bricks analysis)))

(deftest analyze-test
  (let [analysis (dependencies/analyze workspace acyclic)
        m (metrics-by-name analysis)]
    (testing "edges, one per required interface, at the first require"
      (is (= #{["a" "b"] ["c" "a"] ["c" "b"] ["x" "c"]}
            (set (map (juxt :from :to) (:edges analysis)))))
      (is (= {:file "t.c.core.clj" :line 3}
            (:location (first (filter #(= ["c" "b"] [(:from %) (:to %)])
                                (:edges analysis)))))))
    (testing "afferent and efferent"
      (is (= {"a" [1 1] "b" [2 0] "c" [1 2] "x" [0 1]}
            (update-vals m (juxt :afferent :efferent)))))
    (testing "instability"
      (is (= 0.5 (get-in m ["a" :instability])))
      (is (= 0.0 (get-in m ["b" :instability])))
      (is (= 1.0 (get-in m ["x" :instability]))))
    (testing "abstractness: 1 - interface forms / all forms; bases are 0"
      (is (= 0.9 (get-in m ["a" :abstractness])))
      (is (= 0.5 (get-in m ["b" :abstractness])))
      (is (= 0.0 (get-in m ["x" :abstractness]))))
    (testing "no violations"
      (is (empty? (dependencies/check {} analysis))))))

(deftest isolated-brick-test
  (let [m (metrics-by-name
            (dependencies/analyze workspace
              [(brick "lonely" :component (source 't.lonely.interface 5))]))]
    (is (= {:afferent 0 :efferent 0 :instability nil :abstractness 0.0}
          (m "lonely")))))

(deftest check-test
  (let [;; b now requires c, so a -> b -> c -> a and b <-> c are cycles, and
        ;; b (stable) depends on c (less stable)
        bricks (assoc-in acyclic [1 :sources 1 :requires]
                 [{:ns 't.c.interface :line 9}])
        analysis (dependencies/analyze workspace bricks)
        violations (dependencies/check {} analysis)
        by-metric (group-by :metric violations)]
    (testing "stable dependencies"
      (is (= [["b" "c" :error {:file "t.b.core.clj" :line 9}]]
            (map (juxt (comp :name :brick) :subject :level :location)
              (:stable-dependencies by-metric))))
      (is (re-find #"depends on c \(instability 0\.5\), which is less stable than b \(0\.33\)"
            (:message (first (:stable-dependencies by-metric))))))
    (testing "cycles, reported for each brick in one"
      (is (= #{"a" "b" "c"}
            (set (map (comp :name :brick) (:dependency-cycle by-metric)))))
      (is (= #{"a, b, c"} (set (map :subject (:dependency-cycle by-metric))))))
    (testing "rules can be turned off or downgraded"
      (is (empty? (dependencies/check {:stable-dependencies nil :cycles nil}
                    analysis)))
      (is (every? #(= :warning (:level %))
            (dependencies/check {:stable-dependencies :warning
                                 :cycles :warning}
              analysis))))))

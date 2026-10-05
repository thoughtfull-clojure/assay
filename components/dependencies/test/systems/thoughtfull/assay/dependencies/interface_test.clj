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
    (is (= {:afferent 0 :efferent 0 :instability nil :abstractness 0.0
            :cohesion nil :clusters nil :unused-interface 0 :shared-keywords 0}
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

(defn- defs
  [& specs]
  (vec (for [[name & references] specs]
         {:name name :line 1 :references (set references)})))

(def ^:private cohesive
  ;; a: interface delegates to core; core has two unrelated groups:
  ;;   {f, helper} and {g}; core also uses b's interface twice.
  ;; b: interface exposes used and unused; nothing else refers to unused.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :sources [{:file "a/interface.clj" :ns 't.a.interface :forms 10
               :requires [{:ns 't.a.core :as 'core}]
               :definitions (defs ['f 'core/f] ['g 'core/g])}
              {:file "a/core.clj" :ns 't.a.core :forms 90
               :requires [{:ns 't.b.interface :as 'b}
                          {:ns 'clojure.string :as 'str}]
               :definitions (defs ['f 'helper 'b/used 'str/join]
                              ['helper 'b/used 'inc]
                              ['g 'map])}]}
   {:brick {:name "b" :type :component}
    :metrics {}
    :sources [{:file "b/interface.clj" :ns 't.b.interface :forms 5
               :requires [{:ns 't.b.core :refer ['impl]}]
               :definitions (defs ['used 'impl] ['unused 'impl])}
              {:file "b/core.clj" :ns 't.b.core :forms 20
               :requires []
               :definitions (defs ['impl])}]}])

(deftest cohesion-test
  (let [analysis (dependencies/analyze workspace cohesive)
        m (metrics-by-name analysis)]
    (testing "own references / workspace references; libraries don't count"
      ;; a: core/f, core/g, helper (own) vs b/used twice (other) = 3/5
      (is (= 0.6 (get-in m ["a" :cohesion])))
      ;; b: impl twice (referred), both own
      (is (= 1.0 (get-in m ["b" :cohesion]))))
    (testing "clusters among implementation definitions"
      (is (= 2 (get-in m ["a" :clusters])) "{f helper} and {g}")
      (is (= 1 (get-in m ["b" :clusters]))))
    (testing "unused interface"
      (is (= 2 (get-in m ["a" :unused-interface]))
        "nothing depends on a, so all of its interface is unused")
      (is (= ["unused"]
            (keep #(when (= "b" (get-in % [:brick :name])) (str (:name %)))
              (:unused-interface analysis)))))
    (testing "unused interface violations"
      (is (= [["b" "unused" :warning {:file "b/interface.clj" :line 1
                                      :name "unused"}]]
            (->> (dependencies/check {} analysis)
              (filter #(= :unused-interface (:metric %)))
              (filter #(= "b" (get-in % [:brick :name])))
              (map (juxt (comp :name :brick) :subject :level :location))))))))

(def ^:private connected
  ;; b's interface function wide is used by a; narrow is used too; unused
  ;; is wide but unused. Both bricks share :id; a also has :only-a.
  ;; Both bricks contain fragment 99 (40 forms), and a also contains a
  ;; smaller fragment 98 inside it, which b has too.
  [{:brick {:name "a" :type :component}
    :metrics {}
    :functions []
    :sources [{:file "a/core.clj" :ns 't.a.core :forms 100
               :requires [{:ns 't.b.interface :as 'b}]
               :definitions (defs ['f 'b/wide 'b/narrow])
               :keywords #{:id :only-a}
               :fragments [{:hash 99 :forms 40 :line 10 :end-line 20}
                           {:hash 98 :forms 31 :line 12 :end-line 15}]}]}
   {:brick {:name "b" :type :component}
    :metrics {}
    :functions [{:name "wide" :file "b/interface.clj" :line 3 :params 4}
                {:name "narrow" :file "b/interface.clj" :line 5 :params 1}
                {:name "unused" :file "b/interface.clj" :line 7 :params 5}]
    :sources [{:file "b/interface.clj" :ns 't.b.interface :forms 10
               :requires []
               :definitions (defs ['wide] ['narrow] ['unused])
               :keywords #{:id}
               :fragments [{:hash 99 :forms 40 :line 30 :end-line 40}
                           {:hash 98 :forms 31 :line 32 :end-line 35}]}]}])

(deftest connascence-test
  (let [analysis (dependencies/analyze workspace connected)
        violations (group-by :metric (dependencies/check {} analysis))]
    (testing "meaning: shared keywords"
      (is (= {"a" 1 "b" 1}
            (update-vals (metrics-by-name analysis) :shared-keywords))))
    (testing "position: only interface functions other bricks use"
      (is (= [["b" "wide" 4 {:file "b/interface.clj" :line 3 :name "wide"}]]
            (map (juxt (comp :name :brick) :subject :value :location)
              (:connascence-of-position violations)))))
    (testing "algorithm: the largest duplicated fragment, in each brick"
      (is (= #{["a" 40 10] ["b" 40 30]}
            (set (map (juxt (comp :name :brick) :value (comp :line :location))
                   (:duplicate-code violations)))))
      (is (re-find #"duplicates 40 forms in b \(b/interface.clj:30\)"
            (:message (first (filter #(= "a" (get-in % [:brick :name]))
                               (:duplicate-code violations)))))))
    (testing "settings merge over the defaults"
      (is (empty? (:duplicate-code
                   (group-by :metric
                     (dependencies/check {:duplicate-code {:min-forms 50}}
                       analysis)))))
      (is (= [:warning]
            (distinct (map :level (dependencies/check
                                    {:connascence-of-position {:max 2}}
                                    analysis))))))))

(deftest neighbors-test
  (is (= [{:brick {:name "a"} :depends-on ["b" "c"] :depended-on-by []}
          {:brick {:name "b"} :depends-on [] :depended-on-by ["a"]}]
        (dependencies/neighbors [{:brick {:name "a"}} {:brick {:name "b"}}]
          [{:from "a" :to "c"} {:from "a" :to "b"} {:from "a" :to "b"}]))))

(deftest mermaid-test
  (is (= (str "---\nconfig:\n  flowchart:\n    curve: step\n---\n"
           "graph TD\n"
           "  b0([\"#nbsp;#nbsp;#nbsp;#nbsp;cli#nbsp;#nbsp;#nbsp;#nbsp;\"])\n"
           "  b1[\"a\"]\n"
           "  b2[\"b\"]\n"
           "  b0 --> b1\n"
           "  b1 -.-> b2\n"
           "  b2 --> b1\n"
           "  linkStyle 1 stroke:#d1242f,stroke-width:2px\n"
           "  linkStyle 2 stroke:#d1242f,stroke-width:2px")
        (dependencies/mermaid
          [{:brick {:name "cli" :type :base}}
           {:brick {:name "a" :type :component}}
           {:brick {:name "b" :type :component}}]
          [{:from "cli" :to "a"} {:from "a" :to "b"} {:from "b" :to "a"}
           {:from "cli" :to "a"}]
          [{:metric :dependency-cycle :brick {:name "a"} :subject "a, b"}
           {:metric :dependency-cycle :brick {:name "b"} :subject "a, b"}
           {:metric :new-dependency :brick {:name "a"} :subject "b"}]))))

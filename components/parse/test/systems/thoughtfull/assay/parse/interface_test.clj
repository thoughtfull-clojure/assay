(ns systems.thoughtfull.assay.parse.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [rewrite-clj.node :as n]
   [systems.thoughtfull.assay.parse.interface :as parse]))

(deftest top-level-forms-test
  (testing "ignores comments, whitespace, and uneval"
    (is (= ["(ns foo)" "(defn f [] 1)"]
          (map n/string
            (parse/top-level-forms
              (parse/parse-string
                "; comment\n(ns foo)\n#_(ignored)\n(defn f [] 1)\n")))))))

(deftest head-symbol-test
  (let [head (comp parse/head-symbol first parse/top-level-forms
               parse/parse-string)]
    (is (= "defn" (head "(defn f [] 1)")))
    (is (= "if" (head "(clojure.core/if x y z)")))
    (is (nil? (head "[a b]")))
    (is (nil? (head "(:k m)")))))

(deftest position-test
  (is (= {:row 3 :col 1}
        (-> (parse/parse-string "\n\n(defn f [] 1)")
          parse/top-level-forms
          first
          meta
          (select-keys [:row :col])))))

(deftest ns-info-test
  (is (= {:ns 'a.b
          :line 2
          :requires [{:ns 'c.d :line 4 :as 'd :refer ['x 'y]}
                     {:ns 'e.f :line 5}
                     {:ns 'g.h :line 6}
                     {:ns 'i.j :line 7}
                     {:ns 'i.k :line 7 :as 'k}
                     {:ns 'l.m :line 8}]}
        (parse/ns-info
          (parse/parse-string
            (str "; header\n"
              "(ns a.b\n"
              "  (:require\n"
              "   [c.d :as d :refer [x y]]\n"
              "   e.f\n"
              "   [g.h]\n"
              "   [i j [k :as k]])\n"
              "  (:use l.m)\n"
              "  (:import (java.io File)))\n"
              "(defn f [] 1)")))))
  (is (nil? (parse/ns-info (parse/parse-string "(defn f [] 1)")))))

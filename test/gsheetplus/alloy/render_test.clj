(ns gsheetplus.alloy.render-test
  (:require
   [clojure.test :refer :all]
   [clojure.zip :as z]
   [clojure.string :as string]
   [gsheetplus.alloy.render :refer :all]
   [gsheetplus.alloy.examples :refer [context-map1]]
   [gsheetplus.alloy.grammar :as g])
  (:import
   clojure.lang.ExceptionInfo))

(def sample-extensions
  {:filters      {:upper-case string/upper-case
                  :minus2     (fn [x & args]
                                (apply - x 2 args))}
   :inline-ctags {:datetime (fn [context-map] (str (java.util.Date.)))}
   ;:block-ctags  {:count-rows (fn [ctag-cell context-map rows]
   ;                             (let [row-count (count rows)]
   ;                               ; cells is a Seq of the cells in one Row.
   ;                               ; Each cell is a sequence, b/c a cell can have more than one
   ;                               ; value after parsing.
   ;                               [(twod/->Row (twod/cell-row ctag-cell) [[row-count]] :update)]))}
   })

(deftest test-rewind-to-bookmark
  (let [data     [[10 {:a 20}]
                  [30]
                  [40 50 60]
                  [70 80]]
        loc      (z/vector-zip data)
        modified (-> loc z/next z/next z/next (set-bookmark :twenty)
                     z/next z/next (z/edit inc)
                     z/next z/next (z/insert-left 'A) z/next (z/insert-left 'B))]
    (is (= (-> modified (rewind-to-bookmark :twenty) (z/edit update :a inc) z/root)
           [[10 {:a 21, :bookmark :twenty}]
            [31]
            ['A 40 'B 50 60]
            [70 80]]))))

(deftest test-vec-append-at
  (is (= (vec-append-at [] [0] 2 "A") [2]))
  (is (= (vec-append-at [] [0 0] 3 "A") [[3]]))
  (is (= (-> []
             (vec-append-at [0 0] 2 "A")
             (vec-append-at [0 1] 3 "B")
             (vec-append-at [1 0] 4 "C")
             (vec-append-at [1 1] 5 "D"))
         [[2 3] [4 5]])))

(deftest test-evaluate
  ;; static value
  (is (= (evaluate sample-extensions context-map1 nil 7) 7))
  ;; name-spaced REF with no filters
  (is (= (evaluate sample-extensions context-map1 nil
                   (g/mkastnode :REF [0 0] :args [:a.b.c/s]))
         "fooBar"))
  ;; Name-spaced key in String form
  (is (= (evaluate sample-extensions
                   {:cabinetry {:color-&-finish {"cabinet_doors/primary" "foo"}}}
                   nil
                   (g/mkastnode :REF [0 0] :args [:cabinetry :color-&-finish :cabinet_doors/primary]))
         "foo"))
  ;; Undefined REF (falsy)
  (is (nil? (evaluate sample-extensions context-map1 nil
                      (g/mkastnode :REF [0 0] :args [:doesnotexist]))))
  ;; REF with filters
  (is (= (evaluate sample-extensions context-map1 nil
                   (g/mkastnode :REF [0 0] :args [:l3 0]
                                :filters [{:op :minus2 :args [7]}]))
         ; - 10 2 7
         1))
  ;; REF with filter that is NOT DEFINED
  (let [result (evaluate
                sample-extensions context-map1 nil
                (g/mkastnode :EXPR [0 0]
                             :expr (list '+ 1 (g/mkastnode
                                               :REF [0 0] :args [:l3 0]
                                               :filters [{:op :doesnotexist :args [7]}]))))]
    (is (eval-error? result))
    (is (re-find #"Render handle failure.*doesnotexist" (:err-msg result))))
  ;; EXPR
  (is (= (evaluate sample-extensions context-map1 nil (g/mkastnode :EXPR [0 0] :expr '(+ 1 3)))
         4))
  ;; EXPR with nested REF with filters
  (is (= (evaluate sample-extensions context-map1 nil
                   (g/mkastnode :EXPR [0 0]
                                :expr (list '+ 1 (g/mkastnode :REF [0 0] :args [:l3 0]
                                                              :filters [{:op :minus2}]))))
         9)))

(deftest test-handle
  (let [state (new-render-state context-map1)
        tmpl  [["{% for a in l2 %}"]
               ["{{a}}"]
               ["{% endfor %}"]]
        loc   (z/vector-zip tmpl)]

    ; Static value
    (is (= (:value (handle nil state nil (g/mkastnode :VALUE [0 0] :value "foo") loc)) "foo"))

    ; REFs
    (is (= (:value (handle nil state nil (g/mkastnode :REF [1 0] :args [:a :b]) loc)) 30))
    (is (= (:value (handle nil state nil (g/mkastnode :REF [1 0] :args [:a :b]) loc)) 30))
    (is (= (:value (handle nil state nil (g/mkastnode :REF [1 0] :args [:a.b.c/s]) loc)) "fooBar"))

    ; EXPR
    (is (= (:value (handle nil state nil
                           (g/mkastnode :EXPR [0 1]
                                        :expr (list
                                               '>=
                                               (g/mkastnode :REF [0 1] :args [:x] :filters [])
                                               1))
                           loc))
           true))
    (is (= (:value (handle nil state nil (g/mkastnode :EXPR [0 1] :expr '(+ 2 3)) loc)) 5))

    ; WITH / ENDWITH
    (is (= (-> (handle nil state nil (g/mkastnode :WITH [1 1] :k :foo :v "bar") loc)
               :new-state
               ((juxt #(-> % :context-map :foo) :level)))
           ["bar" :WITH]))
    (is (true? (:drop-state? (handle nil (assoc state :level :WITH) nil
                                     (g/mkastnode :ENDWITH [0 0]) loc))))))

(deftest test-handle-ctags
  (let [state (new-render-state context-map1)
        tmpl  [["anything"]]
        loc   (z/vector-zip tmpl)]

    ; Inline ctag
    (is (re-matches #".*\d{4}" ; matching the year portion
                    (:value (handle sample-extensions state nil
                                    (g/mkastnode :CTAG [0 0] :ctag :datetime) loc))))

    ; Block CTAGs are not full implemented, so these tests are minimal...

    ; A Start ctag
    ;extensions state level-stack node loc
    (let [{state1 :new-state} (handle sample-extensions
                                      (assoc state :eval? false) nil
                                      (g/mkastnode :CTAG [0 0] :ctag :count-rows) loc)]
      (is (not (:collector? state1)))
      (is (not (:eval? state1)))
      (is (= (:for/loop-indices state1) nil)))

    ; An END ctag
    (let [result (handle sample-extensions
                         (assoc state :eval? false)
                         nil
                         (g/mkastnode :CTAG [0 0] :ctag :count-rows :end? true)
                         loc)]
      (is (= (:value result) :gsheetplus.alloy.render/no-eval)))))

(deftest test-handle-if-else-rows-block
  (let [state (new-render-state context-map1)
        tmpl  [["anything"]]
        loc   (z/vector-zip tmpl)]

    ; IF
    (let [{:keys [new-state]}
          (handle nil state nil
                  (g/mkastnode
                   :IF [0 0] :test (g/mkastnode :REF [1 0] :args [:a :b])) ; => 30
                  loc)
          {:keys [context-map]}
          new-state]
      (is (= (-> context-map :gsheetplus.alloy.render/test)) 30)
      (is (= (dissoc new-state :context-map)
             {:level           :IF
              :eval?           true
              :if-branch-taken [0 0]
              :bookmark        nil
              :collector?      nil})))

    ; ELSE, already branched
    (let [{:keys [new-state drop-state?]}
          (handle nil (assoc state :if-branch-taken [0 0] :level :IF) nil
                  (g/mkastnode :ELSE [2 0])
                  loc)]
      (is (true? drop-state?))
      (is (= (dissoc new-state :context-map)
             {:level           :IF
              :eval?           false
              :if-branch-taken [0 0]
              :bookmark        nil
              :collector?      nil})))

    ; ELSE, activated
    (let [{:keys [new-state]}
          (handle nil (assoc state :level :IF) nil
                  (g/mkastnode :ELSE [2 0])
                  loc)]
      (is (= (dissoc new-state :context-map)
             {:level           :IF
              :eval?           true
              :if-branch-taken [2 0]
              :bookmark        nil
              :collector?      nil})))

    ; ELIF, already taken
    (let [{:keys [new-state]}
          (handle nil (assoc state :level :IF :if-branch-taken [0 0]) nil
                  (g/mkastnode :ELIF [2 0] :test "truthy")
                  loc)]
      (is (= (dissoc new-state :context-map)
             {:level           :IF
              :eval?           false
              :if-branch-taken [0 0]
              :bookmark        nil
              :collector?      nil})))

    ; ELIF, activated
    (let [{:keys [new-state]}
          (handle nil (assoc state :level :IF) nil
                  (g/mkastnode :ELIF [2 0] :test "truthy")
                  loc)]
      (is (= (dissoc new-state :context-map)
             {:level           :IF
              :eval?           true
              :if-branch-taken [2 0]
              :bookmark        nil
              :collector?      nil})))

    ; ENDIF
    (let [{:keys [drop-state?]}
          (handle nil (assoc state :level :IF) nil
                  (g/mkastnode :ENDIF [2 0])
                  loc)]
      (is (true? drop-state?)))))

(deftest test-handle-for-loop
  (let [state (new-render-state context-map1)
        tmpl  [["{% for a in l2 %}"]
               ["{{a}}"]
               ["{% endfor %}"]]
        loc   (z/vector-zip tmpl)]

    ; new entry to for loop
    (let [{:keys [new-state bookmark]}
          (handle nil state nil
                  (g/mkastnode :FOR [0 0], :k :i, :seq '(a b))
                  loc)
          {:keys [context-map]}
          new-state]
      (is (= bookmark [0 0]))
      (is (= (-> context-map :i)) 'a)
      (is (= (dissoc new-state :context-map)
             {:level            :FOR
              :bookmark         [0 0]
              :for/remaining    ['b]
              :for/loop-var     :i
              :for/loop-indices [0]
              :eval?            true
              :collector?       true
              :if-branch-taken  nil})))

    ; entry to existing for loop
    (let [{:keys [new-state bookmark]}
          (handle nil (assoc state :bookmark [0 0]) nil
                  (g/mkastnode :FOR [0 0] :k :i :seq '(a b))
                  loc)]
      (is (= new-state nil))
      (is (= bookmark nil)))

    ; ENDFOR, remaining
    (let [{:keys [new-state drop-state? rewind]}
          (handle nil
                  (-> state
                      (assoc :level :FOR
                             :bookmark [0 0]
                             :for/remaining '[a b]
                             :for/loop-var :i
                             :for/loop-indices [0])
                      (assoc-in [:context-map :gsheetplus.alloy.render/loop-iter] 0))
                  nil
                  (g/mkastnode :ENDFOR [2 0])
                  loc)]
      (is (= (-> new-state :context-map :i)) 'a)
      (is (= (-> new-state :context-map :gsheetplus.alloy.render/loop-iter)) 1)
      (is (= (dissoc new-state :context-map)
             {:level            :FOR
              :bookmark         [0 0]
              :for/remaining    ['b]
              :for/loop-var     :i
              :for/loop-indices [1]
              :eval?            true
              :collector?       nil
              :if-branch-taken  nil}))
      (is (true? drop-state?))
      (is (= rewind [0 0])))

    ; ENDFOR, no more
    (let [{:keys [new-state drop-state? rewind last-loop-iter]}
          (handle nil
                  (-> state
                      (assoc :level :FOR
                             :bookmark [0 0]
                             :for/remaining '[]
                             :for/loop-indices [1])
                      (assoc-in [:context-map :gsheetplus.alloy.render/loop-iter] 0))
                  nil
                  (g/mkastnode :ENDFOR [2 0])
                  loc)]
      (is (= last-loop-iter 1))
      (is (nil? new-state))
      (is (true? drop-state?))
      (is (nil? rewind)))))

(deftest test-simple-render-ast
  (let [zipper (z/vector-zip [["{{x}} {{x}}{{DATA.a}}{{DATA.$}}{{this-&-that}}{{some-ns/-x}}"]])
        result (->> (g/parse-struct g/the-parser zipper)
                    (render-ast context-map1 nil))]
    ; Row of Cells, where each Cell is
    ; a Seq of ASTNodes.
    (is (= (map :op (ffirst result)) [:REF :VALUE :REF :REF :REF :REF :REF]))
    (is (= (map :value (ffirst result))
           [1 " " 1 "A" "one" 1 2]))))

(deftest test-render-ast-with-filter
  (let [zipper (z/vector-zip [["{{a..b..c/s|upper-case}}"]])
        result (->> (g/parse-struct g/the-parser zipper)
                    (render-ast context-map1 sample-extensions))]
    (is (= result
           [[[(g/mkastnode :REF [0 0] :value "FOOBAR"
                           :args [:a.b.c/s]
                           :filters [{:args nil, :op :upper-case}])]]]))))

(deftest test-render-ast-with-filter-with-args
  (let [zipper (z/vector-zip [["{{x|minus2:10}}"]])
        result (->> (g/parse-struct g/the-parser zipper)
                    (render-ast context-map1 sample-extensions))]
    (is (= (-> result first first first :value) -11))))

(deftest test-render-ast-default-fn
  (let [zipper (z/vector-zip [["{{notanx|default:\"-\"}}"]])
        result (->> (g/parse-struct g/the-parser zipper)
                    (render-ast context-map1
                                (update sample-extensions :filters
                                        assoc :default (fn [v arg1]
                                                         (if (string/blank? v) arg1 v)))))]
    (is (= (-> result first first first :value) "-"))))

(deftest test-render-ast-with-inline-ctag
  (let [zipper (z/vector-zip [["{% datetime %}"]])
        result (->> (g/parse-struct g/the-parser zipper)
                    (render-ast context-map1 sample-extensions))]
    (is (= (-> result first first first :ctag) :datetime))
    (is (re-matches #".*\d{4}" (-> result first first first :value)))))

; This feature is not fully built...
;(deftest test-render-ast-with-content-ctag
;  (let [zipper (z/vector-zip [["{% count-rows %}" "Fooo"]
;                              ["{% end-count-rows %}"]])
;        [[[c1] [c2]] [[c3]]] (->> (g/parse-struct g/the-parser zipper)
;                                  (render-ast context-map1 sample-extensions))]
;    (are [cell expected]
;      (= ((juxt :op :ctag :end? :value) cell) expected)
;      c1 [:CTAG :count-rows false nil]
;      c2 [:VALUE nil nil "Fooo"]
;      c3 [:CTAG :count-rows true nil])))

(deftest test-render-ast-nested-loops
  (let [empty-for-rows-block '(([]))
        zipper               (z/vector-zip [["{% for outer in l2 %}"] ; [a b c]
                                            ["{% for inner in l3 %}"] ; [10, 20]
                                            ["{{inner}} {{outer}}"]
                                            ["{%endfor%}"]
                                            ["{%endfor%}"]])]
    (is (= (->> (g/parse-struct g/the-parser zipper)
                (render-ast context-map1 nil)
                (map (fn [row]
                       (map (fn [col]
                              (map (fn [cell] (:collector cell))
                                   col))
                            row))))
           [empty-for-rows-block
            empty-for-rows-block
            '(([[10 20] [10 20] [10 20]]
               [[" " " "] [" " " "] [" " " "]] ; This is the space (" ") between the two REFs.
               [[a a] [b b] [c c]]))
            empty-for-rows-block
            empty-for-rows-block])))
  ; Three deep for-loop. Plus an EXPR test.
  (let [zipper (z/vector-zip [["{% for a in l %}"] ; 3 items
                              ["{% for b in l2 %}"] ; 3 items
                              ["{% for c in l3 %}"] ; [10, 20]
                              ["{{c}}" "{{ p }}" "{{ c +1}}"]
                              ["{% endfor %}"]
                              ["{% endfor %}"]
                              ["{% endfor %}"]])
        [cell1 cell2 cell3]
        (->> (g/parse-struct g/the-parser zipper)
             (render-ast context-map1 nil)
             (drop 3)
             first)]
    (is (= (->> cell1 first :collector)
           (repeat 3 [[10 20] [10 20] [10 20]])))
    (is (= (->> cell2 first :collector)
           (repeat 3 [[nil nil] [nil nil] [nil nil]])))
    (is (= (->> cell3 first :collector)
           (repeat 3 [[11 21] [11 21] [11 21]])))))

(deftest test-render-ast-nested-eval-false
  (let [zipper (z/vector-zip [["{% if f-val[1] %}"] ; nil
                              ["{% for c in l3 %}"] ; [10, 20]
                              ["{{c}}" "foo"]
                              ["{% endfor %}"]
                              ["{% endif %}"]])]
    (is (= (->> (g/parse-struct g/the-parser zipper)
                (render-ast context-map1 nil)
                (drop 2)
                (take 1)
                first
                (map #(map :value %)))
           [[:gsheetplus.alloy.render/no-eval]
            [:gsheetplus.alloy.render/no-eval]]))))

(deftest test-render-ast-nested-eval-mixed
  (let [zipper (z/vector-zip [["{% for v in f-val %}"] ; [true false]
                              ["{% if v %}"]
                              ["{{v}}" "foo"]
                              ["{% endif %}"]
                              ["{% endfor %}"]])]
    (is (= (->> (g/parse-struct g/the-parser zipper)
                (render-ast context-map1 nil)
                (drop 2)
                (take 1)
                first)
           [(list (g/mkastnode :REF [2 0] :args [:v] :filters []
                               :collector ['a :gsheetplus.alloy.render/no-eval 'c]))
            (list (g/mkastnode :VALUE [2 1] :value "foo"
                               :collector ["foo" :gsheetplus.alloy.render/no-eval "foo"]))]))))

(deftest test-render-eval-error-in-if
  (let [zipper   (z/vector-zip [["{%if l3|badbad%}YES{%endif%}"]])
        parsed   (g/parse-struct g/the-parser zipper)
        rendered (render-ast context-map1 nil parsed)]
    (is (= (map :value (get-in rendered [0 0]))
           [:gsheetplus.alloy.render/eval-error "YES" :gsheetplus.alloy.render/no-value]))))

(deftest test-render-simple-if-not=
  (let [zipper   (z/vector-zip [["{%if x!=2 %}YES{%endif%}"]])
        parsed   (g/parse-struct g/the-parser zipper)
        rendered (render-ast context-map1 nil parsed)]
    (is (= (-> rendered first first second :value)
           "YES"))))

(deftest test-str-join-collectors
  ; empty collectors
  ; NOTE: This condition should not occur
  ;       (is (nil? (str-join-collectors 1 [])))
  ; 3 items in cell, loop1 len = 2
  (is (= (str-join-collectors 1 '[[10 20]
                                  ["x" "y"]
                                  [a b]])
         ["10xa" "20yb"]))
  ; 1 item in cell, loop1 len = 2, loop2 len = 2
  (is (= (str-join-collectors 2 '[[[10 "a"]
                                   [30 40]]])
         [[10 "a"]
          [30 40]]))
  ; 3 items in cell, loop1 (outer) len = 3, loop2 (inner) len = 2
  (is (= (str-join-collectors 2 '[[[10 20] [10 20] [10 20]]
                                  [[" " " "] [" " " "] [" " " "]]
                                  [[a a] [b b] [c c]]])
         [["10 a" "20 a"]
          ["10 b" "20 b"]
          ["10 c" "20 c"]]))
  ; 3 items in cell, loop1 (outer) len = 3, loop2 (inner) len = 2, loop3 (most inner) len = 1
  (is (= (str-join-collectors 3 '[[[[10] [20]] [[10] [20]] [[10] [20]]]
                                  [[[" "] [" "]] [[" "] [" "]] [[" "] [" "]]]
                                  [[[a] [a]] [[b] [b]] [[c] [c]]]])
         [[["10 a"] ["20 a"]]
          [["10 b"] ["20 b"]]
          [["10 c"] ["20 c"]]]))

  ; 1 items in cell, loop1 len = 2, vector values
  (is (= (str-join-collectors 1 [[[1 2] [3 4]] ["x" "y"]])
         ["[1 2]x" "[3 4]y"]))

  ; 4 items in cell, loop1 len = 2, w/ empty values and :gsheetplus.alloy.render/no-eval
  (is (= (str-join-collectors 1 [[]
                                 ["YES" "OTHER"]
                                 [1 :gsheetplus.alloy.render/no-eval]
                                 []])
         ["YES1" "OTHER"])))

(deftest test-single-or-reduce-str
  (are [coll expected]
       (= (single-or-reduce-str coll) expected)
    [10] 10
    [10.1] 10.1
    [10 20] "1020"
    ["a" 10] "a10"
    ["a" " " "b"] "a b"
    ["a"] "a"
    [:gsheetplus.alloy.render/no-value "a"] "a"
    [:gsheetplus.alloy.render/no-value] ""
    [:gsheetplus.alloy.render/no-eval] ""
    [:gsheetplus.alloy.render/eval-error] :gsheetplus.alloy.render/eval-error
    [] ""))

(deftest test-row->items
  (let [mk-item (fn [op path value collector]
                  (->Item op path nil nil nil value collector nil))]
    ; Separate cells
    (is (= (row->items 0 [[(g/mkastnode :VALUE [0 0] :value 1)]
                          [(g/mkastnode :VALUE [0 1] :value 2)]])
           [(mk-item :VALUE [0 0] 1 nil) (mk-item :VALUE [0 1] 2 nil)]))
    ; Multiple values in one cell
    (is (= (row->items 0 [[(g/mkastnode :VALUE [0 0] :value "a")
                           (g/mkastnode :VALUE [0 0] :value 2)]])
           [(mk-item :MERGED-VALUE [0 0] "a2" nil)]))
    ; All no-value / no-eval entries
    (is (= (row->items 0 [[(g/mkastnode :REF [0 0] :value :gsheetplus.alloy.render/no-eval)
                           (g/mkastnode :WITH [0 1] :value :gsheetplus.alloy.render/no-value)]])
           [(mk-item :REF [0 0] :gsheetplus.alloy.render/remove-row nil)]))
    ; Multiple collectors in one cell
    (is (= (row->items 2 [[(g/mkastnode :VALUE [0 0] :collector [[1 1] [1 1] [1 1]])]
                          [(g/mkastnode :VALUE [0 1] :collector '[[d r] [m f] [s l]])
                           (g/mkastnode :VALUE [0 1] :collector '[[o e] [e a] [o a]])]])
           [(mk-item :VALUE [0 0] nil [[1 1] [1 1] [1 1]])
            (mk-item :MERGED-VALUE [0 1] nil [["do" "re"] ["me" "fa"] ["so" "la"]])]))))

(deftest test-unroll-inline-if
  (let [zipper (z/vector-zip [["foo {% if x = 1%}YES{%else %}NO{%endif%}"]])
        parsed (g/parse-struct g/the-parser zipper)]
    (is (= (unroll (render-ast context-map1 nil parsed))
           [(map->Item {:op        :MERGED-VALUE
                        :path      [0 0]
                        :collector nil
                        :value     "foo YES"})]))
    (is (= (unroll (render-ast (dissoc context-map1 :x) nil parsed))
           [(map->Item {:op        :MERGED-VALUE
                        :path      [0 0]
                        :collector nil
                        :value     "foo NO"})]))))

(deftest test-unroll-for-inline-if-with-complex-value
  (let [zipper   (z/vector-zip [["{% for inner in l3 %}"]
                                ; test mixed value in `if`
                                ["{%if inner=10%}YES{{x}}{%endif%}"]
                                ; test eval error (from non-existant filter)
                                ["{%if inner=10%}YES{{x|badfilter}}{%endif%}"]
                                ["{%endfor%}"]])
        rendered (render-ast context-map1
                             nil
                             (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item {:op            :FOR,
                        :path          [0 0]
                        :loop-count    2
                        :end-token-row 3
                        :children      [(map->Item
                                         {:op        :MERGED-VALUE
                                          :path      [1 0]
                                          :collector ["YES1" ""]})
                                        (map->Item
                                         {:op        :MERGED-VALUE
                                          :path      [2 0]
                                          :collector [:gsheetplus.alloy.render/eval-error ""]
                                          :err-msg   "Render handle failure: Filter op (badfilter) not found in the extensions at (2 0)"})]})
            (map->Item {:op        :ENDFOR
                        :path      [3 0]
                        :collector nil
                        :value     :gsheetplus.alloy.render/remove-row})]))))

(deftest test-unroll-for-inline-if-with-complex-value-2
  (let [zipper   (z/vector-zip [["{%if l3|badbad%}YES{%endif%}"]])
        rendered (render-ast context-map1 nil (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item {:op      :MERGED-VALUE
                        :err-msg "Render handle failure: Filter op (badbad) not found in the extensions at (0 0)"
                        :path    [0 0]
                        :value   :gsheetplus.alloy.render/eval-error})]))))

(deftest test-unroll-empty-for-loop
  (let [zipper   (z/vector-zip [["{% for q in empty %}"] ; [10, 20]
                                ["{{q}}"]
                                ["{%endfor%}"]])
        rendered (render-ast context-map1 nil (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item {:op            :FOR
                        :path          [0 0]
                        :loop-count    0
                        :end-token-row 2
                        :children      [(map->Item
                                         {:op        :REF
                                          :path      [1 0]
                                          :collector [""]})]})
            (map->Item {:op        :ENDFOR
                        :path      [2 0]
                        :collector nil
                        :value     :gsheetplus.alloy.render/remove-row})]))))

(deftest test-unroll-for-loop
  (let [zipper   (z/vector-zip [["{% for q in l3 %}"] ; [10, 20]
                                ["{{q}}"]
                                ["{%endfor%}"]])
        rendered (render-ast context-map1 nil (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item {:op            :FOR,
                        :path          [0 0]
                        :loop-count    2
                        :end-token-row 2
                        :children      [(map->Item
                                         {:op        :REF
                                          :path      [1 0]
                                          :collector [10 20]})]})
            (map->Item {:op        :ENDFOR
                        :path      [2 0]
                        :collector nil
                        :value     :gsheetplus.alloy.render/remove-row})]))))

(deftest test-unroll-nested-for
  (let [zipper   (z/vector-zip [["{% for outer in l2 %}"] ; [a b c]
                                ["{% for inner in l3 %}"] ; [10, 20]
                                ["{{inner}} {{outer}}"]
                                ["{%endfor%}"]
                                ["{%endfor%}"]])
        rendered (render-ast context-map1 nil (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item
             {:op            :FOR,
              :path          [0 0]
              :loop-count    3
              :end-token-row 4
              :children      [(map->Item
                               {:op            :FOR
                                :path          [1 0]
                                :loop-count    2
                                :end-token-row 3
                                :children      [(map->Item
                                                 {:op        :MERGED-VALUE
                                                  :path      [2 0]
                                                  :collector [["10 a" "20 a"]
                                                              ["10 b" "20 b"]
                                                              ["10 c" "20 c"]]})]})
                              (map->Item
                               {:op        :ENDFOR
                                :path      [3 0]
                                :collector nil
                                :value     :gsheetplus.alloy.render/remove-row})]})
            (map->Item
             {:op        :ENDFOR
              :path      [4 0]
              :collector nil
              :value     :gsheetplus.alloy.render/remove-row})]))))

(deftest test-unroll-for-inside-falsy-if
  (let [zipper   (z/vector-zip [["{% if 1 = 2 %}"] ; [a b c]
                                ["{% for inner in l3 %}"] ; [10, 20]
                                ["{{inner}}"]
                                ["{%endfor%}"]
                                ["{%endif%}"]])
        rendered (render-ast context-map1 nil (g/parse-struct g/the-parser zipper))]
    (is (= (unroll rendered)
           [(map->Item {:op        :IF
                        :path      [0 0]
                        :collector nil
                        :value     :gsheetplus.alloy.render/remove-row})
            (map->Item {:op            :FOR
                        :path          [1 0]
                        :loop-count    nil ; Would be 2 for a truthy IF stmt
                        :end-token-row 3
                        :children      [(map->Item
                                         {:op        :REF
                                          :path      [2 0]
                                          :collector nil
                                          :value     :gsheetplus.alloy.render/remove-row})]})
            (map->Item
             {:op        :ENDFOR
              :path      [3 0]
              :collector nil
              :value     :gsheetplus.alloy.render/remove-row})
            (map->Item {:op        :ENDIF
                        :path      [4 0]
                        :collector nil
                        :value     :gsheetplus.alloy.render/remove-row})]))))

(ns gsheetplus.alloy.render
  (:require
   [clojure.string :as string]
   [clojure.walk]
   [clojure.zip :as z]
   [clojure.tools.logging :as log]
   [gsheetplus.alloy.grammar :as g]
   [gsheetplus.alloy.grammar :as grammar])
  (:import
   clojure.lang.ExceptionInfo))

(defn extend-size
  "Extend collection to be at least `target-count` in length. Extra values concatenated
   on the end will have value `default-value`."
  [target-count default-value coll]
  (if (> target-count (count coll))
    (concat coll (repeat (- target-count (count coll)) default-value))
    coll))

(defrecord RenderState [context-map eval? level if-branch-taken collector? bookmark])
(defn new-render-state
  [context-map]
  (map->RenderState {:context-map context-map :eval? true}))

(defn set-bookmark
  [loc bookmark]
  (if (map? (z/node loc))
    (z/edit loc assoc :bookmark bookmark)
    (throw (ex-info "Can not set a bookmark on a non-map node" {:loc loc}))))

(defn rewind-to-bookmark
  [zipper bookmark]
  (log/trace (str "Rewinding to " bookmark))
  (loop [loc zipper]
    (let [loc-bookmark (:bookmark (z/node loc))]
      (cond
        (nil? loc) (throw (ex-info "Could not rewind to the specified loc." {}))
        (= loc-bookmark bookmark) loc
        :else (recur (z/prev loc))))))

(defn template-err
  [msg state-stack loc]
  (throw (ex-info msg {:levels (map :level state-stack)
                       :path   (-> loc z/node :path)
                       :loc    (delay loc)})))

(defn rc-error
  [node fmt-msg & format-args]
  (str (apply format fmt-msg format-args)
       (if (grammar/ast-node? node) (format " at %s" (:path node)))))

(defrecord EvalError [err-msg])
(defn eval-error?
  [x]
  (or (instance? EvalError x)
      (= x ::eval-error)))

(defn evaluate
  [extensions context-map level-stack x]
  (log/trace (str "Evaluate " (type x) " " x))
  (try
    (cond
      (and (g/ast-node? x) (= (:op x) :REF))
      (let [v (reduce (fn [m k]
                        (cond (contains? m k) (get m k)
                              (and (keyword? k) (contains? m (name k))) (get m (name k))
                              (and (keyword? k) (namespace k)) (get m (format "%s/%s"
                                                                              (namespace k) (name k)))
                              :else nil))
                      context-map (:args x))
            filters (map (fn [{fargs :args, opkey :op}]
                           #(apply
                             (if (get-in extensions [:filters opkey])
                               (get-in extensions [:filters opkey])
                               (let [err (rc-error
                                          x
                                          "Filter op (%s) not found in the extensions"
                                          (name opkey))]
                                 (throw
                                  (ex-info
                                   err
                                   {:filter                 opkey
                                    :extensions-defined-for (keys (:filters extensions))}))))
                             %
                             fargs))
                         (:filters x))]
        (reduce (fn [ret f] (f ret)) v filters))

      (and (g/ast-node? x) (= (:op x) :EXPR))
      (let [expr-syms (map (partial evaluate extensions context-map level-stack) (:expr x))
            err (first (filter eval-error? expr-syms))]
        (if err err (eval expr-syms)))

      (g/ast-node? x)
      (throw (ex-info
              (rc-error
               x "Evaluation error, EXPRs can only contains REFs and EXPRs, but saw %s" (:op x))
              {}))

      (= x '!=)
      'not=

      :else
      x)
    (catch Exception e
      (let [msg (str "Render handle failure: " (or (.getMessage e) (str e)))]
        (log/error e (str msg " "
                          (merge (if (instance? ExceptionInfo e) (ex-data e))
                                 {:node   x
                                  :path   (if (g/ast-node? x) (:path x))
                                  :levels (try @level-stack (catch Exception _))
                                  :meta   (meta x)})))
        (->EvalError msg)))))

(defn tagname
  [tag-key]
  (string/lower-case (name tag-key)))

(defn validate-state-level!
  [state level-stack expected-start-tag node]
  (let [end-tag (:op node)]
    (when-not (= (:level state) expected-start-tag)
      (throw (ex-info (format "Unbalanced tree, block tag `%s` without matching start tag `%s`."
                              (tagname end-tag) (tagname expected-start-tag))
                      {:node   node
                       :path   (:path node)
                       :levels (try @level-stack (catch Exception _))
                       :meta   (meta node)})))))

(defn inc-last
  [indexes]
  (-> indexes pop (conj (inc (last indexes)))))

;; handlers

(defrecord Handled [new-state drop-state? value rewind bookmark last-loop-iter loop-count
                    err-msg])
(defn mk-handled
  [& {:as m}]
  (map->Handled m))

(defmulti handle
  (fn [extensions state level-stack node loc] (:op node)))

(defmethod handle :VALUE
  [extensions state level-stack node loc]
  (if (:eval? state)
    (mk-handled :value (:value node))
    (mk-handled :value ::no-eval)))

(defmethod handle :REF
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (if (:eval? state)
    (mk-handled :value (evaluate extensions (:context-map state) level-stack node))
    (mk-handled :value ::no-eval)))

(defmethod handle :EXPR
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (if (:eval? state)
    (mk-handled :value (evaluate extensions (:context-map state) level-stack node))
    (mk-handled :value ::no-eval)))

(defmethod handle :WITH
  [extensions state level-stack node loc]
  (if (:eval? state)
    (let [value (evaluate extensions (:context-map state) level-stack (:v node))]
      (mk-handled :value (if (eval-error? value) value ::no-value)
                  :new-state (-> state
                                 (assoc-in [:context-map (:k node)] value)
                                 (assoc :level :WITH))))
    (mk-handled :value ::no-eval
                :new-state (-> state
                               (assoc :level :WITH)
                               (assoc :eval? false)))))

(defmethod handle :ENDWITH
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (validate-state-level! state level-stack :WITH node)
  (mk-handled :drop-state? true :value ::no-value))

(defn inline-f-err-handler
  [node level-stack thunk]
  (try
    (thunk)
    (catch Exception e
      (log/error e (str "Render handle failure, exception in inline-ctag: "
                        (or (.getMessage e) e) " "
                        (merge (if (instance? ExceptionInfo e) (ex-data e))
                               {:node   node
                                :path   (:path node)
                                :levels @level-stack
                                :meta   (meta node)})))
      ::eval-error)))

(defmethod handle :CTAG
  [extensions {:keys [context-map] :as state} level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (let [inline-f (get-in extensions [:inline-ctags (:ctag node)])
        block-f (get-in extensions [:block-ctags (:ctag node)])
        eval? (:eval? state)
        end? (:end? node)]
    (cond

      ; Error
      (and (not (or inline-f block-f)) eval?)
      (let [msg (rc-error node
                          "Render handle failure: ctag (%s) op not found in the extensions"
                          (name (:ctag node)))]
        (log/error (str msg " " {:path         (:path node)
                                 :levels       @level-stack
                                 :meta         (meta node)
                                 :ctag         (:ctag node)
                                 :inline-ctags (keys (:inline-ctags extensions))
                                 :block-ctags  (keys (:block-ctags extensions))}))
        (mk-handled :value ::eval-error :err-msg msg))

      (and (not (or inline-f block-f)) (not eval?))
      (mk-handled :value ::no-eval)

      ; INLINE

      (and inline-f eval?)
      (mk-handled :value (inline-f-err-handler node level-stack #(inline-f context-map)))

      (and inline-f (not eval?))
      (mk-handled :value ::no-eval)

      ; START OF A BLOCK TAG

      (and (not end?) eval?)
      (mk-handled :value nil
                  ; Note: Execution of block-f won't happen until the end
                  :new-state (-> state
                                 (assoc :level (:ctag node))
                                 (update :for/loop-indices #(-> % (or []) (conj 0)))
                                 (assoc :collector? true)))

      (and (not end?) (not eval?))
      (mk-handled :value ::no-eval
                  :new-state (-> state
                                 (assoc :level (:ctag node))
                                 (assoc :eval? false)))

      ; END OF A BLOCK TAG

      (and end? eval?)
      (mk-handled
        ; Process content with block-f, but we do this in `unroll-rows`
       :drop-state? true)

      (and end? (not eval?))
      (mk-handled :value ::no-eval
                  :drop-state? true))))

(defmethod handle :FOR
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (when (some (partial = :debug) (:options node))
    (log/debug (str "  With context " state)))
  (when-not (zero? (-> node :path second))
    (throw (ex-info "`for` can only be used at the start of a row." {:path (-> node :path)})))
  (if (:eval? state)
    (let [lst (or (evaluate extensions (:context-map state) level-stack (:seq node)) [])
          lst (if (sequential? lst)
                lst
                (let [err-msg
                      (rc-error node "`for` expects a sequential values, but has `%s`" (type lst))]
                  (log/error (str err-msg " " {:node   node
                                               :path   (:path node)
                                               :levels @level-stack
                                               :meta   (meta node)}))
                  (->EvalError err-msg)))]

      (cond
        (eval-error? lst)
        (mk-handled :value ::eval-error
                    :new-state (-> state
                                   (assoc-in [:context-map ::loop-iter] 0)
                                   (assoc :level :FOR)
                                   (assoc :for/remaining [])
                                   (assoc :for/loop-var (:k node))
                                   (update :for/loop-indices #(-> % (or []) (conj 0)))
                                   (assoc :eval? false)
                                   (assoc :bookmark (:path node))
                                   (assoc :collector? true))
                    :loop-count (count lst)
                    :bookmark (:path node))
        (not= (:bookmark state) (:path node))
        (mk-handled :value ::no-value
                    :new-state (-> state
                                   (assoc-in [:context-map (:k node)] (first lst))
                                   (assoc-in [:context-map ::loop-iter] 0)
                                   (assoc :level :FOR)
                                   (assoc :for/remaining (seq (rest lst)))
                                   (assoc :for/loop-var (:k node))
                                   (update :for/loop-indices #(-> % (or []) (conj 0)))
                                   (assoc :eval? (boolean (seq lst)))
                                   (assoc :bookmark (:path node))
                                   (assoc :collector? true))
                    :loop-count (count lst)
                    :bookmark (:path node))
        :else
        (mk-handled :value ::no-value)))
    (mk-handled :value ::no-value
                :new-state (-> state
                               (assoc :level :FOR)
                               (assoc :eval? false)
                               ; just to be explicit:
                               (assoc :remaining nil)))))

(defmethod handle :ENDFOR
  [extensions {:keys [for/remaining for/loop-var bookmark context-map] :as state}
   level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (validate-state-level! state level-stack :FOR node)
  (when-not (zero? (-> node :path second))
    (throw (ex-info "`endfor` can only be used at the start of a row." {})))
  ; Use (seq remaining) to distinguish empty list from a list with a falsy value
  (if (seq remaining)
    (let [next-loop-iter (-> context-map ::loop-iter inc)]
      (mk-handled :value ::no-value
                  :new-state (-> state
                                 (assoc-in [:context-map loop-var] (first remaining))
                                 (assoc-in [:context-map ::loop-iter] next-loop-iter)
                                 (assoc :for/remaining (seq (rest remaining)))
                                 (update :for/loop-indices inc-last))
                  :drop-state? true
                  :rewind bookmark))
    (mk-handled :value ::no-value
                :last-loop-iter (last (:for/loop-indices state))
                :drop-state? true)))

(defmethod handle :IF
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (if (:eval? state)
    ; This could be, for example, a nested IF statement
    (let [tst (evaluate extensions (:context-map state) level-stack (:test node))]
      (mk-handled :value (if (eval-error? tst) tst ::no-value)
                  :new-state (-> state
                                 (assoc-in [:context-map ::test] tst)
                                 (assoc :level :IF)
                                 (assoc :eval? (boolean tst))
                                 (assoc :if-branch-taken (if tst (:path node))))))
    (mk-handled :value ::no-value
                :new-state (-> state
                               (assoc :level :IF)
                               (assoc :eval? false)
                               (assoc :if-branch-taken ::no-eval)))))

(defmethod handle :ELIF
  [extensions {:keys [if-branch-taken] :as state} level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (let [tst (and (not if-branch-taken)
                 (evaluate extensions (:context-map state) level-stack (:test node)))]
    (mk-handled :value (if (eval-error? tst) tst ::no-value)
                :new-state (-> state
                               (assoc-in [:context-map ::test] tst)
                               (assoc :eval? (boolean tst))
                               (assoc :if-branch-taken (or if-branch-taken (and tst (:path node)))))
                :drop-state? true)))

(defmethod handle :ELSE
  [extensions {:keys [if-branch-taken] :as state} level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (validate-state-level! state level-stack :IF node)
  (mk-handled :value ::no-value
              :new-state (-> state
                             (assoc :eval? (not if-branch-taken))
                             (assoc :if-branch-taken (or if-branch-taken (:path node))))
              :drop-state? true))

(defmethod handle :ENDIF
  [extensions state level-stack node loc]
  (log/trace (str "Processing " {:node node}))
  (validate-state-level! state level-stack :IF node)
  (mk-handled :value ::no-value
              :drop-state? true))

;; /handle

(defn vec-append-at
  [coll loop-indices value context-string]
  (log/tracef "Collecting %s into %s %s at %s." value context-string coll loop-indices)
  (let [[i & more-i] loop-indices]
    (cond
      (and (seq more-i) (contains? coll i))
      (update-in coll [i] vec-append-at more-i value context-string)
      (seq more-i)
      (vec-append-at (conj coll []) loop-indices value context-string)
      :else
      (conj coll value))))

(defn ast-zip
  "Returns a zipper for nested vectors, given a root vector/seq.
  This is more forgiving than clojure.zip/vector-zip and accepts
  seqs or vectors as branches.
  We need this (for our second pass, the one on the AST) b/c our
  incoming templates are vector trees, but instaparse returns seqs."
  {:added "1.0"}
  [root]
  (z/zipper #(or (vector? %) (seq? %))
            seq
            (fn [node children]
              (with-meta (vec children) (meta node)))
            root))

(defn filter-ast
  [x]
  (cond
    (sequential? x)
    (filter #(or (and (sequential? %) (seq %))
                 (grammar/ast-node? %))
            (map filter-ast x))
    (grammar/ast-node? x)
    x
    :else
    nil))

(defn render-ast
  [context-map extensions ast]
  (log/info "Render ast (alloy)")
  (let [ast2 (seq (filter-ast ast))]
    (log/tracef "AST:\n%s\nFilter extensions provided for: %s"
                ast2 (or (-> extensions :filters keys) "None"))
    (let [ast-zipper (ast-zip ast2)]
      (z/root
       (loop [loc ast-zipper
              state-stack (list (new-render-state context-map))]
         (when (or (z/end? loc)
                   (and (meta loc) (not (z/branch? loc))))
           (let [n (z/node loc)]
             (log/trace (str "Render with stack "
                             {:node              (cond
                                                   (z/end? loc) :end
                                                   (g/ast-node? n) (into {} n)
                                                   (meta n) (:path (meta n))
                                                   :else n)
                              :state-stack-depth (count state-stack)
                              :state-stack-top   (dissoc (peek state-stack) :context-map)}))))

         (cond

           (and (z/end? loc) (not= (count state-stack) 1))
           (template-err "Unbalanced template" state-stack loc)

           (z/end? loc) loc

           (z/branch? loc) (recur (z/next loc) state-stack)

           :else
           (let [node (z/node loc)
                 {:keys [new-state drop-state? value rewind bookmark last-loop-iter loop-count]}
                 (handle extensions (peek state-stack)
                         (delay (reverse (map :level state-stack)))
                         node loc)

                 [value err-msg] (if (eval-error? value)
                                   [::eval-error (:err-msg value)]
                                   [value nil])

                  ; get this value before looking at the updated stack:
                 collector? (-> state-stack peek :collector?)
                 updated-stack (cond-> state-stack
                                 drop-state? pop
                                 new-state (conj new-state))
                 loop-indices (-> updated-stack peek :for/loop-indices)
                 loc2 (cond
                         ; Inside a loop
                        (and collector? (not= value ::no-value))
                        (z/edit loc update :collector vec-append-at loop-indices value
                                "collector")
                         ; An evaluated REF or a static value outside a loop, or
                         ; in the true part of a conditional. Or (inside false
                         ; part of IF or empty FOR), value could be ::no-eval
                        :else
                        (z/edit loc assoc :value value))
                 updated-loc (cond-> loc2
                               err-msg (z/edit assoc :err-msg err-msg)
                               bookmark (set-bookmark bookmark)
                               loop-count (z/edit assoc :loop-count loop-count)
                               last-loop-iter (z/edit
                                               update :max-indices vec-append-at
                                               loop-indices last-loop-iter "max-indices")
                               rewind (rewind-to-bookmark
                                       (-> updated-stack peek :bookmark)))]
             (recur (z/next updated-loc) updated-stack))))))))

(defn single-or-reduce-str
  [coll]
  (let [err? (some (partial = ::eval-error) coll)
        coll2 (remove #{::no-value ::no-eval} coll)]
    (cond
      err? ::eval-error
      (= (count coll2) 1) (first coll2)
      :else (reduce str coll2))))

(defn str-join-collectors
  "Within each cell, combine (str join) the collector values at each set of loop indices.
  Example:
    loop-depth = 2
    collectors =
      [ [ [10 20] [10 20] [10 20]]       ; (a)
        ['[a a]  '[b b]  '[c c]]   ]     ; (b)
    =>
    [ [\"10a\" \"20a\"]
      [\"10b\" \"20b\"]
      [\"10c\" \"20c\"] ]
  This is:
    1. Two Nodes in each cell, (a) and (b)
    2. Outer loop length 3
    3. Inner loop length 2"
  [loop-depth collectors]
  (assert (not (zero? loop-depth)))
  (let [length (reduce max (map count collectors))
        collectors2 (map (partial extend-size length nil) collectors)]
    (apply mapv
           (fn [& args]
             (if (= loop-depth 1)
               (single-or-reduce-str args)
               (str-join-collectors (dec loop-depth) args)))
           collectors2)))

(defrecord Item [op path loop-count end-token-row children value collector err-msg])

(defn row->items
  "Pull value from the collector of each cell-seq item, based on the indices. If the cell
  value is a literal just use the value as is."
  [loop-depth row]
  (let [remove-row? (every?
                     (fn [cell-seq] (->> cell-seq (map :value) (every? #{::no-value ::no-eval})))
                     row)]
    (cond
      remove-row?
      [(map->Item {:op        (-> row first first :op)
                   :path      (-> row first first :path)
                   :value     ::remove-row
                   :collector nil})]
      :else
      (for [cell-seq row
            :let [{:keys [op path]} (first cell-seq)
                  op (if (>= (count cell-seq) 2) :MERGED-VALUE op)]]
        (if (zero? loop-depth)
          (map->Item {:op      op
                      :path    path
                      :err-msg (some->> cell-seq
                                        (map :err-msg)
                                        (remove nil?)
                                        seq                 ; so we get `nil` and not "" if empty
                                        (string/join "; "))
                      :value   (single-or-reduce-str (map :value cell-seq))})
          (map->Item {:op        op
                      :path      path
                      :err-msg   (some->> cell-seq
                                          (map :err-msg)
                                          (remove nil?)
                                          seq               ; so we get `nil` and not "" if empty
                                          (string/join "; "))
                      :collector (str-join-collectors loop-depth (map :collector cell-seq))}))))))

(defn unroll*
  [loop-depth [row & remaining]]
  ; Note: cells `c1` are Seqs of values or ASTNodes. `c1a` is the first value/ASTNode.
  ;       This fn is mostly concerned with the `c1a` because it operates on ROWS BLOCK
  ;       instructions.  I.e., those that appear as the first value in the first
  ;       cell of a Row.
  (let [[{:keys [op path loop-count] :as c1a} :as c1] (first row)]
    (cond

      (and (empty? row) (empty? remaining))
      [nil nil []]

      (= op :FOR)
      (let [[processed unprocessed etris] (unroll* (inc loop-depth) remaining)
            [endfor-row & u2] unprocessed
            [p3 u3 etris2] (unroll* loop-depth u2)]
        [(concat
          [(map->Item {:op            op
                       :path          path
                       :loop-count    loop-count
                       :end-token-row (last etris)
                       :children      processed})]
          (row->items loop-depth endfor-row)
          p3)
         u3
         (into (pop etris) etris2)])

      (= op :ENDFOR)
      [[]
       (cons row remaining)
       [(first path)]]

      :else
      (let [[processed unprocessed etris] (unroll* loop-depth remaining)]
        [(concat (row->items loop-depth row) processed)
         unprocessed
         etris]))))

(defn unroll
  [rows]
  (log/info "Starting unroll pass...")
  (first (unroll* 0 rows)))

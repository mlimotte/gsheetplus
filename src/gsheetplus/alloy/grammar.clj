(ns gsheetplus.alloy.grammar
  (:require
   [clojure.string :as string]
   [clojure.zip :as z]
   [clojure.tools.logging :as log]
   [instaparse.core :as insta])
  (:import
   java.text.NumberFormat))

(defrecord ASTNode [op path collector])
(defn mkastnode
  [op path & {:as kvs}]
  (map->ASTNode
   (merge {:collector [] :max-indices []}
          kvs
          {:op op :path path})))
(defn ast-node?
  [x]
  ;; Using this logic instead of `instance?`, to facilitate testing in the REPL
  (and x (= (-> x type .getName) "gsheetplus.alloy.grammar.ASTNode")))

(def grammar
  "
  (* / is like alternation, but as an ordered choice *)
  <START> = (VAR / TAG_BLOCK / CHARN)*

  <VAR> = <'{{'> <S> EXPR <S> <'}}'>
  <TAG_BLOCK> = <'{%'> <S> TAG <S> <'%}'>

  <TAG> = (FOR / ENDFOR / IF / ELSE / ELIF / ENDIF / WITH / ENDWITH / CTAG )

  CTAG = (('end-' IDENTIFIER) / IDENTIFIER) <S> EXPR* <S> OPTIONS?

  FOR = <'for'> <S> IDENTIFIER <S> <'in'> <S> EXPR <S> OPTIONS?
  ENDFOR = <'endfor'>

  WITH = <'with'> <S> IDENTIFIER <S> <'='> <S> EXPR <S> OPTIONS?
  ENDWITH = <'endwith'>

  IF = <'if'> <S> EXPR <S> OPTIONS?
  ELIF = <'elif'> <S> EXPR <S> OPTIONS?
  ELSE = <'else'>
  ENDIF = <'endif'>

  <ARG> = (BOOLEAN/NUMBER/REF/STRING/KEYWORD)

  REF = IDENTIFIER ((<'.'> (IDENTIFIER|NUMBER)) | INDEX)* (<'|'> FILTER)*

  (* EXPR matches infix notation, though we rewrite as prefix notation in `normalize-ast` *)
  EXPR = ARG / (<'('> <S> EXPR <S> <')'> | EXPR <S> OP <S> EXPR | EXPR <'|'> FILTER (<'|'> FILTER)*)
  OP = #'=|!=|<=|>=|<|>|\\+|-|\\*|/'

  INDEX = <'['> <S> #'[0-9]+' <S> <']'>
  REF_NO_FILTER = IDENTIFIER ((<'.'> (IDENTIFIER|NUMBER)) | INDEX)*
  FILTER = IDENTIFIER (<':'> (REF_NO_FILTER|STRING|NUMBER))*

  OPTIONS = <'['> <S> IDENTIFIER (<S> IDENTIFIER)* <S> <']'>\n

  <SIMPLE_IDENTIFIER> = #'[$&a-zA-Z_-]([$&a-zA-Z0-9_-]|\\?)*'
  (* Identifier could be clojure style namespace/symbol *)
  IDENTIFIER = SIMPLE_IDENTIFIER (<'..'> SIMPLE_IDENTIFIER)* ('/' SIMPLE_IDENTIFIER)?

  <S> = #'\\s*'?
  <STRING> = <S> <'\"'> #'[^\"]*' <'\"'> <S>
  KEYWORD = <':'> IDENTIFIER
  NUMBER = #'-?[0-9]+(\\.[0-9]+)?'
  BOOLEAN = <S> ('true'|'false'|'True'|'False') <S>

  <CHARN> = #'[^{]+' | CHARN? '{' !'{' !'%' CHARN
  ")

(def number-format #(.parse (NumberFormat/getInstance) %))

(defn- normalize-ast-ref
  [path & args]
  (let [[ks filters] (split-with (complement vector?) args)]
    (mkastnode :REF path
               :args (mapv #(if (string? %) (keyword %) %) ks)
               :filters (mapv (fn [[_ fname & args]]
                                {:op   (keyword fname)
                                 :args args})
                              filters))))

(defn normalize-ast
  "Convert the parsed units into Records. The original (instaparse) metadata is retained."
  [insta-seq path]
  (-> (insta/transform
       {:REF           (partial normalize-ast-ref path)
        :REF_NO_FILTER (partial normalize-ast-ref path)
        :INDEX         (fn [index-str] (Integer/valueOf index-str))
        :WITH          (fn [var-name value & [options]]
                         (mkastnode :WITH path
                                    :options (map keyword (rest options))
                                    :k (keyword var-name)
                                    :v value))
        :ENDWITH       (fn [& _] (mkastnode :ENDWITH path))
        :IF            (fn [test & [options]]
                         (mkastnode :IF path :test test :options (map keyword (rest options))))
        :ELIF          (fn [test & [options]]
                         (mkastnode :ELIF path :test test :options (map keyword (rest options))))
        :ELSE          (fn [& _] (mkastnode :ELSE path))
        :ENDIF         (fn [& _] (mkastnode :ENDIF path))

        :CTAG          (fn [& args]
                         (let [end?    (= (first args) "end-")
                               [ctag & other-args] (if end? (rest args) args)
                               options (if (= (-> other-args last first) :OPTIONS)
                                         (mapv keyword (-> other-args last rest))
                                         nil)]
                           (mkastnode :CTAG path
                                      :ctag (keyword ctag)
                                      :end? end?
                                      :args (if (empty? options)
                                              other-args
                                              (butlast other-args))
                                      :options options)))

        :EXPR          (fn [& args]
                         (let [[no-filter-args filters]
                               (split-with (complement #(and (sequential? %) (-> % first (= :FILTER))))
                                           args)]
                           (if (= (count no-filter-args) 1)
                             (first no-filter-args)
                             (let [[args1 [sym & args2]]
                                   (split-with (complement symbol?) no-filter-args)
                                   expr
                                   (sequence (concat [sym] args1 args2))]
                               (when-not sym
                                  ; This shouldn't ever happen, b/c the grammar does
                                  ; not allow it.  But we double check as a precaution.
                                 (throw (ex-info "No symbol (op) found in EXPR."
                                                 {:args no-filter-args, :path path})))
                               (mkastnode :EXPR path :expr expr
                                          :filters (mapv (fn [[_ fname & args]]
                                                           {:op   (keyword fname)
                                                            :args args})
                                                         filters))))))

        :NUMBER        number-format
        :BOOLEAN       #(Boolean/valueOf %)
        :KEYWORD       (fn [s] (keyword s))
        :OP            (fn [s] (symbol s))
        :IDENTIFIER    (fn [& args]
                         (let [[ns [_ k]] (split-with (partial not= "/") args)]
                           (if k
                             (str (string/join "." ns) "/" k)
                             (string/join "." ns))))
        :FOR           (fn [var-name value & [options]]
                         (mkastnode :FOR path
                                    :k (keyword var-name)
                                    :options (map keyword (rest options))
                                    :seq value))
        :ENDFOR        (fn [& _] (mkastnode :ENDFOR path))}
       insta-seq)
      ; Normalize any static values
      (->> (map #(if (ast-node? %) % (mkastnode :VALUE path :value %))))
      (with-meta {:path path})
      doall))

(defn vec-index
  [node]
  (loop [n (node 1) acc (list)]
    (let [idx (-> n :l count)]
      (if (nil? (:ppath n))
        (cons idx acc)
        (recur (:ppath n) (cons idx acc))))))

(defn zipper-map
  "Apply the function, f, to each loc and node value in the zipper."
  [f zipper]
  (loop [loc zipper]
    (cond
      (z/end? loc) loc
      (z/branch? loc) (recur (z/next loc))
      :else (recur (z/next (z/replace loc (f loc (z/node loc))))))))

(defn parse-struct
  [parser zipper]
  (log/info "Parse structure (alloy)")
  (z/root
   (zipper-map
    (fn [loc v]
      (cond
        (nil? v)
        nil
        (string? v)
        (let [path      (vec-index loc)
              insta-seq (parser v)]
          (when (insta/failure? insta-seq)
            (throw (ex-info (format "Could not parse at %s: %s" path v)
                            {:err insta-seq})))
          (normalize-ast insta-seq path))
        :else
        (list (mkastnode :VALUE (vec-index loc) :value v))))
    zipper)))

(def the-parser (insta/parser grammar))

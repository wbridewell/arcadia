(ns ^{:doc
      "Utility functions for causal reasoning via mental models. In the following,
  we represent mental models as lists of boolean pairs. Each boolean pair represents
  a possibility in the model with the listed truth values for the causal antecedent and
  consequent. As causal relations can be instantiated in several models,
  we define causal relation by their list of models."}
  arcadia.utility.causal
  (:require [arcadia.utility.vectors :as vec]
            [arcadia.utility
             [relations :refer [to-string]]
             [general :refer [apply-if]]]
            [clojure.math.combinatorics :as combo]))

(def causal-relations
  [{:name "causes"
    :models (list ;; strong causation
                  [[true true] [false false]]
                  ;; weak causation
                  [[true true] [false true] [false false]])}

   {:name "prevents"
    :models (list ;; strong prevention
                  [[true false] [false true]]
                  ;; weak prevention
                  [[true false] [false true] [false false]])}

   {:name "enables"
    :models (list [[true true] [true false] [false false]])}

   {:name "enables not"
    :models (list [[true true] [true false] [false true]])}

   {:name "has no effect on" ;; either the antecedent or consequent is fixed
    :models (list [[true true] [false true]]
                  [[true false] [false false]]
                  [[true true] [true false]]
                  [[false true] [false false]])}])

(defn possibility-count
  "Returns the number of possibilies stored in the causal model,
  calculated as the number of rows in the table."
  [model]
  (count model))

(defn relation-count
  "Returns the number of causal relations stored in the model,
  calculated as the number of columns in the table."
  [model]
  (count (first model)))

(defn consistent-possibilites?
  "Where p1 is a possibility A...B and p2 is a possibility B...C,
  returns true iff p1 and p2 have the same value of B"
  [p1 p2]
  (= (last p1) (first p2)))

(defn merge-possibilities
  "Where p1 is a possibility A...B and p2 is a possibility B...C,
  returns an extended possibility A...B...C."
  [p1 p2]
  (concat p1 (rest p2)))

(defn merge-models
  "Where m1 is a causal model of A...B and m2 is a causal model of B...C,
  this function returns a unified causal model of A...B...C.
  When called with more than two models, this function merges them in forward order."
  ([m1 m2]
   (mapcat #(map (partial merge-possibilities %)
                 (filter (partial consistent-possibilites? %) m2))
           m1))
  ([m1 m2 & models]
   (if (seq models)
     (apply merge-models (merge-models m1 m2) (first models) (rest models))
     (merge-models m1 m2))))

(defn to-vector
  "Takes a mental model (list of boolean pairs) and returns a vector describing
  its contents according to the formula: [a & b, a & ~b, ~a & b, ~a & ~b]
  where a is the antecedent and b is the consequent. Models with more than
  two causal variables are handled similarly. In general for models with
  c columns, the vector will have length 2^c"
  [model]
  (if (empty? model) []
    (->> [true false]
         (repeat (relation-count model))     ;; make c boolean pairs (one per column)
         (apply combo/cartesian-product)    ;; get all possibilities
         (mapv #(possibility-count (filter #{%} model))))))   ;; count the possiblities in the model

(defn extract-values
  "Converts a model of relations to a model of boolean values"
  [model]
  (map #(map (comp true? :value :arguments) %) model))

(defn model-similarity
  "Calculates the similarity between mental models as the
  cosine similarity between their vectorized representations.
  If either of the models is empty, similarity is set to 0."
  [model1 model2]
  (or (vec/cosine-similarity (to-vector model1) (to-vector model2))
      0))

(defn causal-score
  "Calculates the maximum similarity between the model and the causal relation"
  [model relation]
  (apply max (map (partial model-similarity model) (:models relation))))

(defn get-causal-relation
  "Determines the causal relation demonstrated in the model by finding
  the relation with maximum similarity to the model"
  [model causal-relations]
  (:name (apply max-key (partial causal-score model) causal-relations)))

(defn causal-relation->str
  "Creates a string representation of a causal relation for printing"
  [r]
  (str (when (-> r :arguments :omissive?) "¬")
       (-> r :arguments :antecedent (to-string false false))
       " " (-> r :arguments :relation) " "
       (-> r :arguments :consequent (to-string false false))))

(defn omissive
  "Converts all of the causal relations to their omissive equivalents
  by negating the value of the antecedent."
  [relation]
  (update relation :models #(map (partial map (fn [[a c]] (vector (not a) c))) %)))

(defn causal-relation
  "Creates an interlingua element describing the causal relation
  between a causal antecedent and consequent."
  [model antecedent consequent]
  (when (and model antecedent consequent)
    ;; if the relation is NO-EFFECT, make sure it isn't
    ;; being treated unnecessarily as omissive
    (apply-if #(-> % :arguments :relation (= "has no effect on"))
              assoc-in
              {:name "causal-relation"
               :arguments (if (-> model first first)
                            {:antecedent antecedent
                             :consequent consequent
                             :model model
                             :relation (get-causal-relation model causal-relations)}
                            {:omissive? true
                             :antecedent antecedent
                             :consequent consequent
                             :model model
                             :relation (get-causal-relation model (map omissive causal-relations))})
               :type "instance"
               :world nil}
              [:arguments :omissive?] false)))

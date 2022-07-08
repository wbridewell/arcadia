(ns
  ^{:doc "Utility functions for creating and comparing
         relations between objects in the visual scene."}
  arcadia.utility.relations
  (:require clojure.string
            [arcadia.utility [descriptors :as d]]))

(defn relation
  "This function can be used by models to create relations to track.
  Arity can either be a natural number (for fixed arity), or
  a predicate over the natural numbers (for multiple arity).

  sr-links is a disjunctive sequence of constraints,
  representing the Disjunctive Normal Form (DNF) breakdown of the relation.
  That is, the relation is true iff any of the stimulus constraints are satisfied
  within a cycle. For relations, response functions must return an
  instance template (using instantiate) to instantiate the relation.

  Ordered should be true when the relation is asymmetric
  Unique should be true if the arguments cannot be identical (nothing can collide with itself)
  By default, relations are both ordered and unique."
  ([predicate-name arity ordered unique & sr-links]
   {:predicate-name predicate-name
    :sr-links sr-links
    :arity (if (number? arity)
             #(= % arity)
             arity)
    :ordered ordered
    :unique unique}))

(defn get-arg-labels
  "Returns a sequence of string labels describing the objects related by r."
  [r]
  (->> r :arguments :argument-descriptors (map :label)))

(defn valid-relation?
  "A relation is valid if its predicate name is not blank,
  none of its argument labels are blank, the arity is correct,
  and all of the arguments are unique if they should be."
  [r]
  (and (= (:name r) "relation")
       (= (:type r) "instance")
       (not (clojure.string/blank? (:predicate-name (:arguments r))))
       (not-any? clojure.string/blank? (get-arg-labels r))
       (= (:arity (:arguments r)) (count (:arguments (:arguments r))))
       (or (not (:unique (:arguments r)))
           (apply distinct? (get-arg-labels r)))))

(defn instantiate
  "Create a request to instantiate the relation over the given objects
  as a result of the given justification. By default, the relation is
  true in the real context. Optionally a sequence of truth values
  and a context can be provided"
  ([relation objects justifications]
   (instantiate relation objects [true] justifications))
  ([relation objects values justifications]
   (instantiate relation objects values "real" justifications))
  ([relation objects values context justifications]
   {:name "instantiate"
    :arguments {:relation relation
                :objects objects
                :values values
                :context context
                :justifications justifications}
    :type "action"
    :world nil}))

(defn to-string
  "Create a string representation of the predicate applied
  to the objects represented by obj-labels.
  If value? is true, include a negation symbol for false relations.
  If context? is true, include the parenthesized context string.
  By default, only outputs the relation string."
  ([relation] (to-string relation false))
  ([relation value?] (to-string relation value? false))
  ([relation value? context?]
   ;; sort unordered arguments so that to-string always returns the same value
   (let [labels (if (:ordered (:arguments relation))
                  (get-arg-labels relation)
                  (sort (get-arg-labels relation)))]
     (str (when (and value? (-> relation :arguments :value not)) "¬")
          (-> relation :arguments :predicate-name) "("
          (clojure.string/join ", " labels) ")"
          (when context? (str " (" (-> relation :arguments :context) ")"))))))

(defn relate
  "Create an instance of a relation from the given justifications.
  Justifications can be events that have recently occurred, results of
  predictive mechanisms such as visual scans, or inference schema.

  Relation arguments can either be ordered or unordered, but are
  always stored in a list to avoid problems with duplicate arguments."
  ([instantiation-request object-descriptors source]
   (let [args (:arguments instantiation-request)]
     (map (fn [value] (relate (:relation args) value
                              (:justifications args) (:objects args)
                              (map #(d/get-descriptor % object-descriptors) (:objects args))
                              (:context args) source))
          (:values args))))
  ([relation value justifications arguments argument-descriptors context source]
   (let [r {:name "relation"
            :arguments {:predicate-name (:predicate-name relation)
                        :ordered (:ordered relation)
                        :unique (:unique relation)
                        ;; set arity to nil if it doesn't match
                        :arity (when ((:arity relation) (count arguments))
                                 (count arguments))
                        :relation relation
                        :argument-descriptors argument-descriptors
                        :arguments (seq arguments)
                        :value value
                        :justifications justifications
                        :context context}
            :world nil
            :source source
            :type "instance"}]

     ;; make sure that r is valid before returning it
     (if (valid-relation? r) r
       (throw (Exception. (str "Invalid instance of " (:predicate-name relation)
                               " over " (mapv :label argument-descriptors)
                               " with value=" value
                               ", context=" context
                               ", arity=" (:arity (:arguments r))
                               ", ordered=" (:ordered relation)
                               ", and unique=" (:unique relation))))))))

(defn relation-equals
  "Two relations are equal if they have the same name,
  context, and object arguments.
  By default, a relation's value effects relation equality.
  In order to ignore value, call with use-value? as false"
  ([r1 r2] (relation-equals r1 r2 true))
  ([r1 r2 use-value?] (relation-equals r1 r2 use-value? true))
  ([r1 r2 use-value? use-context?]
   (and (= (:name r1) (:name r2) "relation")
        (= (:predicate-name (:arguments r1))
           (:predicate-name (:arguments r2)))
        (or (not use-context?)
            (= (:context (:arguments r1))
               (:context (:arguments r2))))
        (or (not use-value?)
            (= (:value (:arguments r1))
               (:value (:arguments r2))))
        (= (:unique (:arguments r1))
           (:unique (:arguments r2)))
        (= (:ordered (:arguments r1))
           (:ordered (:arguments r2)))
        (if (:ordered (:arguments r1))
          (= (get-arg-labels r1) (get-arg-labels r2))
          (= (frequencies (get-arg-labels r1))
             (frequencies (get-arg-labels r2)))))))

(defn instance-descriptor
  "Create a descriptor matching a valid instance of a relation.
  Defaults to match all instances of the relation with any value."
  [relation & {:as args}]
  (d/extend-descriptor (apply d/descriptor (apply concat (dissoc args :arguments)))
                       :name "relation" :type "instance"
                       :predicate-name (:predicate-name relation)
                       :arguments (if-not (:arguments args) d/ANY-VALUE
                                          #(d/descriptors-match? (:arguments args) %
                                                                 :ordered (:ordered relation)))))

(defn relation-event-descriptor
  "Create a descriptor matching an event denoting the truth
  of a relation in the real world."
  [relation arg-descriptors]
  (d/descriptor :name "event-stream" :event-name (:predicate-name relation)
                :objects #(d/descriptors-match? arg-descriptors % :ordered (:ordered relation))))


(defn truth-values
  "Given the current state of accessible content, get the known truth values
  for the relation as applied to objects matching object-descriptors.

  A truth value is known for relation instances that are being perceived,
  being memorized, or have been memorized into working-memory."
  ([content relation & args]
   (truth-values (d/filter-matches (apply instance-descriptor relation args)
                                 content)))
  ([relations] (set (map (comp :value :arguments) relations))))

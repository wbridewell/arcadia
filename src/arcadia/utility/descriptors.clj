(ns ^{:doc "Utility functions for creating and matching descriptors for interlingua elements.
Descriptors are analogous to regular expressions for interlingua elements.
An interlingua element is a Clojure map including :name, :arguments,
:type, and :world keys, where the :arguments value is another Clojure map.

A descriptor is a similar Clojure map where each value is a test against
the corresponding value of an interlingua element, taking the form of either
a raw value (a string, boolean, etc) to match against with =, or a predicate.
For a descriptor to match an interlingua element, every value in the descriptor
must match the corresponding value in the interlingua element.

When forming relations about objects it is useful to be able to refer to
them by name. An object descriptor is a descriptor describing a visual
object (i.e. {:name \"object\" :type \"instance\"}). Optionally, object
descriptors (and descriptors in general) can be labeled, so that objects
can be referred to using the same label over time without relying on
perceptual constancy to resolve identity. This way it's easy to represent
something task-specific like \"A is the green thing\" between components.

Finally, sequences of descriptors are used for conjunctive constraint matching.
This is used in representing SR-links for tasks, where the stimulus is a
number of interlingua elements to be matched.
"}
  arcadia.utility.descriptors
  (:require [clojure.math.combinatorics :as combo]
            [arcadia.utility.general :as g]))

;; when we want any value to match a descriptor's property,
;; simply use a predicate which always returns true
(def ANY-VALUE (constantly true))

;; A descriptor's toplevel keys are the same as an interlingua element's,
;; but can also include a :label which applies to any matching element
(def ^:private toplevel-keys [:name :type :world :label])

;;-------------------------------Making Descriptors-------------------------------
(defn descriptor
  "Creates a standardized descriptor used to predicate over
  interlingua elements. In order for a descriptor to apply to
  an element, the :name must be equal, and the element's :arguments
  must contain all of the :arguments of the descriptor"
  ([& {:as args}]
   (assoc (select-keys args toplevel-keys)
          :arguments (apply dissoc args toplevel-keys))))


;;-------------------------------Matching Descriptors-------------------------------

(defn- extract-arguments
  "Extracts all of the arguments of the element relevant to the descriptor"
  [element descriptor]
  ((apply juxt (keys (:arguments descriptor)))
   (:arguments element)))

(defn property-matches?
  "Given a descriptor property and a value, determines whether the value has
  the given property. This is true if they are equal, or if the property is
  a predicate which applies to the value."
  [property value]
  (or (= property value)
      (try (property value) (catch Exception e))))

(defn descriptor-matches?
  "Given an element and a descriptor, returns true if
  the element has the same name and all of the arguments
  of the element match the descriptor arguments."
  [descriptor element] 
  (and (every? #(property-matches? (% descriptor) (% element))
               (keys (dissoc descriptor :label :arguments))) ;; exclude label from matching
       (or (empty? (:arguments descriptor))
           (every? identity (map property-matches?
                                 (vals (:arguments descriptor))
                                 (extract-arguments element descriptor))))))

(defmacro element-matches?
  "Returns true if the element matches a descriptor made from the key/value pairs in args."
  [element & args]
  `(descriptor-matches? ~(apply descriptor args) ~element))


(defn descriptor-mismatches
  "Given an element and a (presumably mismatching) descriptor, finds the
  arguments of the descriptor which don't match the element.
  *Very* useful for debugging."
  [descriptor element]
  (concat (remove #(property-matches? (% descriptor) (% element))
                  (keys (dissoc descriptor :arguments)))
          (remove #(property-matches? (% (:arguments descriptor)) (% (:arguments element)))
                  (keys (:arguments descriptor)))))



(defn filter-matches
  "Obtain the elements of seq which match the descriptor. If a predicate is
  provided, returns the elements of seq which match the descriptor and
  are true of the predicate."
  ([descriptor seq]
   (filter (partial descriptor-matches? descriptor) seq))
  ([descriptor pred seq]
   (filter #(and (descriptor-matches? descriptor %) (pred %)) seq)))

(defmacro filter-elements
  "Filter a seq by a descriptor made from the key/value pairs in args."
  [seq & args]
  `(filter-matches ~(apply descriptor args) ~seq))



(defn remove-matches
  "Obtain the elements of seq which do not match the descriptor"
  [descriptor seq]
  (remove (partial descriptor-matches? descriptor) seq))

(defmacro remove-elements
  "Remove elements from a seq that match a descriptor made from the key/value pairs in args."
  [seq & args]
  `(remove-matches ~(apply descriptor args) ~seq))



(defn first-match
  "Find the first element of seq which matches the descriptor"
  [descriptor seq]
  (g/find-first (partial descriptor-matches? descriptor) seq))

(defmacro first-element
  "Returns the first element matching a descriptor made from the key/value pairs in args."
  [seq & args]
  `(first-match ~(apply descriptor args) ~seq))



(defn first-mismatch
  "Find the first element of seq which does not match the descriptor"
  [descriptor seq]
  (g/find-first (complement (partial descriptor-matches? descriptor)) seq))



(defn some-match
  "Returns true if some element in seq matches the descriptor"
  [descriptor seq]
  (some (partial descriptor-matches? descriptor) seq))

(defmacro some-element
  "Returns true if some element in seq matches a descriptor made from the key/value pairs in args."
  [seq & args]
  `(some-match ~(apply descriptor args) ~seq))


(defn all-match
  "Returns true if every element in seq matches the descriptor."
  [descriptor seq]
  (every? (partial descriptor-matches? descriptor) seq))

(defmacro all-elements
  "Returns true if every element in seq matches a descriptor made from the key/value pairs in args."
  [seq & args]
  `(all-match ~(apply descriptor args) ~seq))

(defn none-match
  "Returns true if no element in seq matches the descriptor"
  [descriptor seq]
  (not-any? (partial descriptor-matches? descriptor) seq))

(defmacro not-any-element
  "Returns true if no element in seq matches a descriptor made from the key/value pairs in args."
  [seq & args]
  `(none-match ~(apply descriptor args) ~seq))



(defn rand-match
  "Returns a random element in seq which matches the descriptor, or nil if none match."
  [descriptor seq]
  (g/rand-if-any (filter-matches descriptor seq)))

(defmacro rand-element
  "Returns a random element matching a descriptor made from the
  key/value pairs in args, or nil if none match"
  [seq & args]
  `(rand-match ~(apply descriptor args) ~seq))




(defn get-descriptor
  "Returns the first descriptor which matches the element"
  [element descriptors]
  (g/find-first #(descriptor-matches? % element) descriptors))

(defn get-descriptors
  "Returns a sequence of descriptors matching the elements"
  [elements descriptors]
  (map #(get-descriptor % descriptors) elements))

(defn descriptors-match?
  "Given parallel seqs of elements and descriptors,
  determines whether each element matches its corresponding descriptor.

  An :ordered tag can optionally be provided which determines whether
  the elements and descriptors need to match in the same ordering.
  By default, :ordered is true."
  [descriptors elements & {:keys [ordered] :or {ordered true}}]
  (and (= (count elements) (count descriptors))
       (if ordered
          (every? true? (map descriptor-matches? descriptors elements))
          (some (partial descriptors-match? descriptors)
                (combo/permutations elements)))))




;;-------------------------------Labeling Descriptors-------------------------------

(defn label-descriptor
  "Attach a string label to any element matching this descriptor"
  [descriptor label]
  (assoc descriptor :label label))

(defn get-label
  "Obtain the label of the object by matching it to an object-descriptor.
   Returns nil if there's no match."
  [object object-descriptors]
  (:label (get-descriptor object object-descriptors)))

(defn get-labels
  "Obtain the labels for each object by matching them to object-descriptors."
  [objects object-descriptors]
  (map #(-> % (get-descriptor object-descriptors) :label) objects))

(defn label-equals
  "A test for equality between objects based solely on task-specific labels."
  ([d1 d2] (= (:label d1) (:label d2)))
  ([o1 o2 descriptors]
   (= (get-label o1 descriptors) (get-label o2 descriptors))))




;;-------------------------------Modifying Descriptors-------------------------------

(defn- merge-property
  "When extending a descriptor, ensure that arguments with multiple constraints
  match all of the constraints conjunctively."
  [p1 p2]
  (cond
    (and (= p1 ANY-VALUE) (= p2 ANY-VALUE)) ANY-VALUE
    (= p1 ANY-VALUE) p2
    (= p2 ANY-VALUE) p1
    :else
    (fn [value] (and (property-matches? p1 value) (property-matches? p2 value)))))

(defn extend-descriptor
  "Given an descriptor, make a new descriptor that's a copy of the
  old one, with additional arguments specified in args"
  [super-descriptor & {:as args}]
  (assoc (merge-with merge-property super-descriptor (select-keys args toplevel-keys))
         :arguments (merge-with merge-property
                                (:arguments super-descriptor)
                                (apply dissoc args toplevel-keys))))

(defn object-descriptor
  "Creates a standardized object-descriptor used to label objects.
  label is a label (string) representing an object.
  optional arguments are keys and their values reflecting
  arguments of the labeled object."
  ([] (descriptor :name "object" :type "instance"))
  ([label & args]
   (label-descriptor (apply descriptor
                            (concat args (list :name "object" :type "instance")))
                     label)))

(defn extend-object-descriptor
  "Refine an object descriptor with additional arguments, giving the
  new sub-descriptor a label"
  [super-descriptor label & args]
  (label-descriptor (apply extend-descriptor (conj args super-descriptor)) label))





;;-------------------------------Constraint Matching----------------------------

;; a constraint is a sequence of descriptors.

(defn matches-constraint?
  "Determine whether a sequence of elements matches the constraint"
  [constraint elements]
  (descriptors-match? constraint elements))

(defn get-constraint-matches
  "Obtain a sequence of all sequences of elements in content
  which satisfy the constraint"
  [constraint content]
  (->> constraint
       (map #(filter-matches % content))
       (apply combo/cartesian-product)))


;;-------------------------------Object Updating----------------------------
;; NOTE: this function is a viable alternative to arcadia.utility.objects/updated-object.
(defn vstm-object
  "Returns the current version of an object (old-obj) as it exists in vstm,
  or old-obj if it no longer exists in vstm."
  [old-obj object-descriptors content]
  (or (g/find-first (partial descriptor-matches?
                             (extend-descriptor
                              (get-descriptor old-obj object-descriptors)
                              :world "vstm"))
                    content)
      old-obj))

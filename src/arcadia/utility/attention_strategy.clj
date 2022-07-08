(ns
  ^{:doc "Helper functions for constructing attentional strategies."}
  arcadia.utility.attention-strategy
  (:require [arcadia.utility [objects :as obj] [general :as g] [descriptors :as d]]))

(defn default-strategy 
  "A general default-mode attentional program that is useful across models."
  [expected]
  (or (d/rand-element expected :type "action")
      (d/rand-element expected :name "object" :type "instance" :world nil)
      (d/rand-element expected :name "fixation")
      (g/rand-if-any (seq expected))))


(defn get-object-fixations
  "Returns a seq of fixations with the specified key/value pairings in their :object argument."
  [content & args]
  (d/filter-elements content :name "fixation"
                     :object (if (empty? args) some?
                                 #(d/descriptor-matches? (apply d/descriptor args) %))))

;; This fn provides a means of inhibiting fixation request that match objects currently
;; stored in VSTM. You can specify no arguments, indicating we want to inhibit everything
;; in VSTM, or you can specify key/value pairs for arguments of the objects you want to inhibit.
;;
;; This code does not look direction at VSTM. Rather, it looks at top-down fixation requests to
;; objects. So if your objects in VSTM are not generating top-down fixation requests, this won't
;; do anything.
(defn remove-object-fixations
  "Filters out any elements that share regions with top-down fixations with the specified
  0 or more key/value pairs in the arguments of their objects."
  [content-to-filter total-content & args]
  (remove (fn [item] (some #(obj/same-region? item % total-content)
                           (apply get-object-fixations total-content args)))
          content-to-filter))

(defn get-fixations-to-region
  "Returns a list of all fixations to a specified region"
  [content region]
  (filter #(and (= (:name %) "fixation") (obj/same-region? % region content)) content))

;; functions to hide the implementation details of partial strategies
(defn partial-priority
  "Returns the priority for the partial strategy."
  [ps]
  (:priority ps))

(defn partial-strategy
  "Takes a function that selects an interlingua element from a set, a priority
  for that function within a strategy, and optionally a value to increment the
  function's priority on each cycle to avoid element starvation."
  ([focus-selector priority]
   (partial-strategy focus-selector priority 0))
  ([focus-selector priority step]
   {:function focus-selector :priority priority :step step :base priority}))

(declare build-dynamic-strategy)

(defn- dynamic-strategy-function
  "Takes a list of partial strategies and applies them to the content, updating their
  associated priorities on each application. Returns a focus of attention and the
  updated version of itself."
  [partial-strategies content]
  (let [sorted-updated-partial-strategies (sort-by :priority > (map #(update % :priority + (:step %)) partial-strategies))
        [f ps] (arcadia.utility.general/seek first
                                             (map #(vector ((:function %) content) %) sorted-updated-partial-strategies)
                                             [nil nil])]
    (if ps
      [f (build-dynamic-strategy (conj (remove #{ps} sorted-updated-partial-strategies) (assoc ps :priority (:base ps))))]
      [f (build-dynamic-strategy sorted-updated-partial-strategies)])))

(defn build-dynamic-strategy
  "Constructs an attentional strategy that returns a focus element and an updated
  version of itself where the internal priorities are adjusted."
  [partial-strategies]
  (partial dynamic-strategy-function partial-strategies))

(defn combine-strategies
  ;; TODO: raise issues at meeting
  "Currently does complete replacement of the old strategy with the new one."
  [old new] ;TODO update weights smartly, move to support or core file
  new)

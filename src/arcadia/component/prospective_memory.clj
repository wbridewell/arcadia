(ns arcadia.component.prospective-memory
  "Focus Responsive
    Yes. When adopt-plan is the focus, this component stores information about that operator.
    When task-configuration is the focus, the associated operator is set to active. 
    When outcome-achieved is the focus, the operator is no longer active and no longer tracked.
   
  Default Behavior
   Checks the activation :conditions for each operator in memory against the current episode. 
   When there is a match, a task-configuration is produced. 

  Produces
    * task-configuration 
        :configuration - instantiated task set
        :conditions - descriptors that activate the prospective memory
        :matches - a sequence of the aspects of the episode that matched the conditions
        :p-memory - a prospective memory
    The prospective memory has the following structure.
      {:conditions - seq of descriptors
       :basis - the source for the memory (e.g., a plan operator)
       :result - function that takes the matches to the conditions and produces 
                 an instantiated task set}"  
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;
;; prospective memory data structure
;; {:conditions - seq of descriptors
;;  :basis - the source for the memory (e.g., a plan operator)
;;  :result - function that takes the matches to the conditions and produces 
;;            an instantiated task set}


;; stores conditions for activating task sets
;; when the current episode matches a condition, instantiate the associated task set schema
;; track which conditions are active so that you don't have duplicate task sets 
;;  e.g., if you see two objects that would trigger the memory, only go with the 
;;  first one you notice. otherwise you could end up in a Buriden's Donkey situation
;;
;; the objects that match the conditions are then passed as arguments to the schema
;; how do we map the descriptor matches to the schema arguments?
;; e.g., we see a locked red door, and we want to pick up a red key
;;
;; the result should be an instantiated task set schema 


(def ^:parameter initial-memories "prospective memories to include from the outset" nil)

;; NOTE: there's a concern here that if we're trying to pick out a specific object at a 
;; specific location that we might get the locations mixed up, especially if two objects
;; differ in properties only by position. but it's unclear when this will make a difference
;; that makes a difference.
(defn- match
  "Returns the first item in episode e that matches the condition c, which is a descriptor."
  [e c]
  (or
   (d/first-match c (-> e :arguments :temporal))
   (d/first-match c (-> e :arguments :conceptual))
   (d/first-match c (-> e :arguments :spatial))))

(defn- instantiate-configuration
  "When there are matches, returns a map that includes 
    :configuration - an instantiated task set schema
    :conditions - conditions from the matching prospective memory
    :matches - a sequence of the aspects of the episode that matched the conditions
    :p-memory - the complete prospective memory item."
  [memory matches]
  (when (seq matches)
    {:configuration (apply (:result memory) matches)
     :conditions (:conditions memory)
     :matches matches
     :p-memory memory}))

(defn- matches
  "Returns a sequence that contains an item that matches each ordered condition in 
   a single memory element, m, or nil if one of the conditions fails to match an item."
  [e m]
  (when e
    (loop [c (:conditions m)
           result []]
      ;; c is a constraint, so it's a sequence of descriptors
      ;; we need to find the first match to each of these descriptors in 
      ;; each of the possible data sources.
      (if (empty? c)
        (instantiate-configuration m result)

        (when-let [x (match e (first c))]
          (recur (rest c) (conj result x))))))) 

(defn- recall 
  "Search for a matching prospective memory and return a tuple containing the resulting 
  configuration and the matching memory."
  [e memory]
  (first (remove nil? (map #(matches e %) memory))))

(defn- encode-plan [p]
  {:basis (:handle p)
   :conditions (:conditions p)
   :result (:task-set p)})

(defrecord ProspectiveMemory [buffer memory active]
  Component
  (receive-focus
    [component focus content]
   ;; keep track of active prospective memory items so that you don't instantiate 
   ;; them multiple times and so that you can monitor them
    (when (d/element-matches? focus :name "task-configuration" :type "instance" :world nil)
      (swap! active conj (-> focus :arguments :p-memory)))

    (reset! buffer
            (when-let [configuration (recall (d/first-element content :name "episode" :world nil :type "instance")
                                             (remove (set @active) @memory))]
              {:name "task-configuration"
               :arguments configuration
               :type "instance"
               :world nil}))

    ;; if the focus is a termination signal, remove the requisite items from both active and memory
    (when (d/element-matches? focus :name "outcome-achieved" :type "instance" :world nil)
      (reset! active (seq (remove #{(-> focus :arguments :p-memory)} @active)))
      (reset! memory (seq (remove #{(-> focus :arguments :p-memory)} @memory))))
    ;; we need to pick a representation that lets us add to memory
    (when (d/element-matches? focus :name "adopt-plan" :type "action" :world nil)
      (swap! memory conj (encode-plan (-> focus :arguments :plan)))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method ProspectiveMemory [comp ^java.io.Writer w]
  (.write w (format "ProspectiveMemory{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->ProspectiveMemory (atom nil) (atom (:initial-memories p)) (atom nil))))
(ns arcadia.component.intention-memory
  "?

  Focus Responsive
  ?

  Default Behavior
  ?

  Produces
  ?"
  (:require [arcadia.utility [general :as g]
                             [descriptors :as d]]
            [arcadia.component.core :refer [Component merge-parameters]]
            [clojure.string]
            [clojure.set]))

(def ^:parameter delay-start "cycles to avoid intention evaluation" 0)
(def ^:parameter equality-fn "function to compare the intentional object to tracked objects" =)

;; condition monitoring functions

(defn- evaluate-preconds 
 "Evaluates the precondition of all stored, active intentions on each cycle, and updates an evaluation history for each.  On the first match,
  the :precondition-met field is changed to true in order to signal the beginning action execution."
  [intentions content]
  (into #{} (map #(merge %
                    (let [evaluation (apply (-> % :activation-condition) (cons content (-> % :parameters)))]
                      {:evaluation-history (cons evaluation (take 10 (-> % :evaluation-history)))
                       :precondition-met evaluation}))
                 intentions)))

(defn- evaluate-termination-conds 
  "Evaluates the termination-conditions of all intentions on each cycle, marking terminated? true when met."
  [intentions content]
  (into #{}
    (map #(assoc % :terminated? (apply (-> % :termination-condition) (list % content)))
         intentions)))

(defn- evaluate-satisfaction-conds 
 "Evaluates the satisfaction-conditions of all intentions on each cycle, marking satisfied? true when met."
  [intentions content]
  (let [street (first (-> (d/first-element content :name "local-plan") :arguments :completed-plan-steps))
        target (d/first-element content
                               :subcategory #(clojure.string/includes? % "0006")
                               :tracked? true
                               :world "vstm")])

 (into #{}
   (map #(assoc % :satisfied? (apply (-> % :satisfaction-condition) (list % content)))
        intentions)))

(defn- update-intentional-objects
  "Replace the intentional objects in intentions with their current state if
  possible, using eqfn to determine if the new objects are proper updates."
  [intentions visual-equality-elts eqfn]
  (loop [match-int intentions
         results ()]
    (if (empty? match-int)
      results
      (let [matching-veq
            ;; if the intentional object is the old object in a visual-equality
            ;; relation, then replace the intentional object with the new version.
            (g/find-first #(eqfn (-> match-int first :intentional-object)
                                 (-> % :arguments :old))
              visual-equality-elts)]

        (recur (rest match-int)
               (cons (if matching-veq
                       (assoc (first match-int) :intentional-object (-> matching-veq :arguments :new))
                       (first match-int))
                     results))))))

(defn- intention-arguments
  "Creates the arguments map for an interlingua element by selecting a subset of
  properties of an intention."
  [i]
  (let [kws [:constraints
             :evaluation-history
             :formed-at
             :intentional-object
             :precondition-met
             :satisfied?
             :subtype
             :task
             :terminated?]]
    (zipmap kws ((apply juxt kws) i))))

(defn- make-intention-elements
  "Creates an interlingua element with name kind for the sequence of intentions."
  [kind intentions component]
  (map #(hash-map :id (gensym)
                  :name kind
                  :arguments (intention-arguments %)
                  :world "intention-memory"
                  :source component
                  :type "instance")
      intentions))

(defrecord IntentionMemory [buffer activated-intentions retained-intentions cycle-num params]
  Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)
   ;; replace intentional objects with their current state based on information
   ;; from VSTM.
   (let [visual-equality-elts (d/filter-elements content :name "visual-equality")
         new-intention (d/first-element content :name "form-intention")]
     (reset! (:retained-intentions component)
             (update-intentional-objects @(:retained-intentions component) visual-equality-elts (-> component :params :equality-fn)))
     (reset! (:activated-intentions component)
             (update-intentional-objects @(:activated-intentions component) visual-equality-elts (-> component :params :equality-fn)))

     ;; start tracking any new intentions
     (when new-intention
       (swap! (:retained-intentions component)
              conj
              (merge (:arguments new-intention)
                     {:formed-at (dec @(:cycle-num component))
                      :satisfied? false
                      :terminated? false
                      :evaluation-history ()
                      :precondition-met false}))))

   ;; NOTE: some models may need a burn-in period before processing intentions.
   (when (> @(:cycle-num component) (-> component :params :delay-start))
     ;; Find all active intentions whose preconditions are met, store them in buffer, and
     ;; remove them from the list of retained intentions
     (let [[met unmet] ((juxt filter remove) :precondition-met (evaluate-preconds @(:retained-intentions component) content))]
       (reset! (:retained-intentions component) (into #{} unmet))
       (swap! (:activated-intentions component) clojure.set/union (into #{} met))))

   ;; determine which intentions are terminated or satisfied
   (reset! (:retained-intentions component)
       (evaluate-satisfaction-conds
        (evaluate-termination-conds @(:retained-intentions component) content)
        content))

   (reset! (:activated-intentions component)
       (evaluate-satisfaction-conds
           (evaluate-termination-conds @(:activated-intentions component) content)
           content))

   ;; remove any terminated achievement and maintenance intentions and any satisfied achievement intentions
   (let [satisfied-retained-intentions (into #{} (filter #(and (:satisfied? %) (= (:subtype %) "achievement")) @(:retained-intentions component)))
         satisfied-activated-intentions (into #{} (filter #(and (:satisfied? %) (= (:subtype %) "achievement")) @(:activated-intentions component)))
         terminated-retained-intentions (into #{} (filter :terminated? @(:retained-intentions component)))
         terminated-activated-intentions (into #{} (filter :terminated? @(:activated-intentions component)))]

     (swap! (:retained-intentions component)
            clojure.set/difference terminated-retained-intentions satisfied-retained-intentions)

     (swap! (:activated-intentions component)
            clojure.set/difference terminated-activated-intentions satisfied-activated-intentions)

     (reset! (:buffer component)
             (concat (make-intention-elements "activated-intention" @(:activated-intentions component) component)
                     (make-intention-elements "retained-intention" @(:retained-intentions component) component)
                     (make-intention-elements "satisfied-intention"
                                              (clojure.set/union satisfied-retained-intentions satisfied-activated-intentions)
                                              component)
                     (make-intention-elements "terminated-intention"
                                              (clojure.set/union terminated-retained-intentions terminated-activated-intentions)
                                              component))))
   (swap! (:cycle-num component) inc))

  (deliver-result
   [component]
   (into #{} @(:buffer component))))

(defmethod print-method IntentionMemory [comp ^java.io.Writer w]
  (.write w (format "IntentionMemory{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->IntentionMemory (atom nil) (atom #{}) (atom #{}) (atom 0) p)))

(ns arcadia.component.outcome-memory
  "Focus Responsive
    Yes. When adopt-plan is the focus, this component stores information about that operator.
    When task-configuration is the focus, the associated operator is set to active and is 
    monitored for termination conditions. 
    When outcome-achieved is the focus, the operator is no longer active and no longer tracked.
   
  Default Behavior
   Checks the outcome conditions for each active plan operator to determine if they can be 
   terminated. When there is a match, that outcome is stored and produced in content until 
   it becomes the focus of attention.

  Produces
    * outcome-achieved 
        :basis - a keyword that is name of the source for the memory (e.g., a plan operator handle)
        :outcome - seq of descriptors that determine when the outcome is achieved
        :satisfiers - interlingua elements that matched outcome
        :p-memory - the prospective memory that activated the outcome memory
        :matches - interlingua elements that activated the operator"
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;;; Internal Data Structures
;; outcome memory data structure
;; {:basis - the source for the memory (e.g., a plan operator)
;;  :outcome - function that takes the matches to the conditions from a task-configuration 
;;             (from prospective memory) and produces a seq of descriptors for testing success}
;;
;; active outcomes data structure
;; {:basis - the source for the memory (e.g., a plan operator)
;;  :outcome - seq of descriptors from outcome memory :outcome function
;;  :matches - elements that were passed to the outcome fn
;;  :p-memory - the prospective memory that activated the outcome memory}
;;
;; achieved outcomes data structure
;; {:matches - elements that satisfy the outcome
;;  :outcome - seq of descriptors from outcome memory :outcome function}
;;;;

(def ^:parameter initial-memories "outcome memories to include from the outset" nil)

(defn- satisfy-outcome [active-outcome satisfiers]
  {:name "outcome-achieved"
   :arguments (assoc active-outcome :satisfiers satisfiers)
   :world nil
   :type "instance"})

(defn- terminate [active-outcome content]
  (when-let [matches (first (d/get-constraint-matches (:outcome active-outcome) content))]
    (satisfy-outcome active-outcome matches)))

(defn- configuration-basis [c]
  (-> c :arguments :p-memory :basis))

(defn- configuration-matches [c]
  (-> c :arguments :matches))

(defn- encode-plan [p]
  {:basis (:handle p)
   :outcome (:outcome p)})

(defrecord OutcomeMemory [buffer memory active achieved]
  Component
  (receive-focus
    [component focus content]
   ;; we need to pick a representation that lets us add to memory
    (cond (d/element-matches? focus :name "adopt-plan" :type "action" :world nil)
          (swap! memory conj (encode-plan (-> focus :arguments :plan))) 

          (d/element-matches? focus :name "task-configuration" :type "instance" :world nil)
         ;; get the matches out of the configuration and store an active outcome
          (when-let [outcome (g/find-first #(= (:basis %) (configuration-basis focus)) @memory)]
           ;; instantiate outcome, track the arguments to the outcome, and track the source 
           ;; prospective memory
            (swap! active conj (merge outcome {:outcome (apply (:outcome outcome)
                                                               (configuration-matches focus))
                                               :matches (configuration-matches focus)
                                               :p-memory (-> focus :arguments :p-memory)})))

         ;; this is our termination signal, so update the queue of achieved outcomes
         ;; NOTE: we should also remove the item from memory as happens in prospective memory      
          (and (d/element-matches? focus :name "outcome-achieved" :type "instance" :world nil)
               (= focus (peek @achieved)))
          (swap! achieved pop)

          :else
         ;; check all active outcomes to see if they match anything 
         ;; if they do, store the first match in an achieved outcome memory queue
         ;; NOTE: perhaps this should just match against the current episode. unclear.(?)
          (let [tuples (filter #(second %) (map #(vector % (terminate % content)) @active))]
           ;; remove matched outcomes from the active list to avoid producing duplicate matches
            (swap! active #(remove (set (map first tuples)) %))
           ;; add the termination signals to the queue of achieved outcomes
            (swap! achieved g/append-queue (map second tuples))))

   ;; on each cycle, report one achieved outcome.
    (reset! buffer (when (seq @achieved) (peek @achieved))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method OutcomeMemory [comp ^java.io.Writer w]
  (.write w (format "OutcomeMemory{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->OutcomeMemory (atom nil) (atom (:initial-memories p)) (atom nil) (atom (g/queue)))))
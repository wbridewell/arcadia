(ns arcadia.component.scheduler 
  "Focus Responsive
    Yes. When task-configuration is the focus, the associated task set is added to the active
    list of task sets. When outcome-achieved is the focus, the relevant task set is no longer
    tracked.
   
  Default Behavior
   Stores task sets that are currently being processed. Switches among them on a regular basis
   defined by the value of active-cycles. Only one attentional strategy is active at a time, but
   all stimulus-response links are active simultaneously. Initial responses are triggered each time
   the scheduler switches the active task set. 

  Produces
    * adopt-attentional-strategy
        :strategy - the attentional strategy to adopt
        :reason - scheduler
        :handle - keyword, the name of the task set configuration

    * task
        :world - task-wm
        :stimulus-responses - the stimulus-response links of every task set in the scheduler
        :configuration - the currently active task set configuration"  
  (:require [arcadia.utility.attention-strategy :as att]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]
            [arcadia.component.core :refer [Component merge-parameters]]))

;; every 10 or 20 cycles seems reasonable, but it's going to be domain dependent. 
(def ^:parameter active-cycles "minimum cycles a strategy is active before being switched out" 10)

;; we need a way for sr-link-processor to get all the sr-links it needs. we'll put them here 
;; for now so that we don't have to edit that component.
(defn- task-wmify
  "Build an element that outputs the working memory representation of the current task."
  [task sr-links]
  {:name "task"
   :arguments (merge (:configuration task) {:stimulus-responses sr-links})
   :type "instance"
   :world "task-wm"})

;; we may need to change the "handle" here, which probably isn't used anyway.
(defn- adopt-strategy
  "Build an element that requests a new attentional strategy be adopted. The
  strategy function is built from the specification in the task."
  [task]
  {:name "adopt-attentional-strategy"
   :arguments {:strategy (att/build-dynamic-strategy (-> task :configuration :strategy))
               :reason "scheduler"
               :handle (-> task :configuration :handle)}
   :world nil
   :type "action"})

;;;; Data Structures
;; active contains
;; {:basis - the name of the prospective memory that created this configuration
;;  :matches - the elements that were used to instantiate the task set
;;  :configuration - the instantiated task set}

(defn- activate [tc]
  {:basis (-> tc :arguments :p-memory :basis)
   :matches (-> tc :arguments :matches)
   :configuration (-> tc :arguments :configuration)})

(defn- outcome-matches?
  "True when the outcome reported is for the configuration derived from the same :basis
   and :matches as the active memory element."
  [oa x]
  (and (= (-> oa :arguments :basis) (:basis x))
       (= (-> oa :arguments :matches) (:matches x))))

(defn- sr-links [active]
  (mapcat #(-> % :configuration :stimulus-responses) active))

;; the first element of active is the currently active task configuration 
(defrecord Scheduler [buffer parameters active cycle-count initial?]
  Component
  (receive-focus
    [component focus content]
    (swap! cycle-count inc)

   ;; adopt-attentional-strategy doesn't need focus for focus selector to work.
    (when (d/first-element content :name "adopt-attentional-strategy" :type "action" :reason "scheduler")
     ;; update the q if we are actually adopting the new task instance 
     ;; round-robin scheduling with the current item going to the back of the queue
      (reset! active (conj (pop @active) (peek @active))))

   ;; adding and removing task sets from the scheduler
    (cond (d/element-matches? focus :name "task-configuration" :type "instance" :world nil)
         ;; add task instance to the active queue 
          (swap! active conj (activate focus))

          (d/element-matches? focus :name "outcome-achieved" :type "instance" :world nil)
         ;; remember to turn the seq from remove back into a queue.
          (reset! active (g/queue (remove #(outcome-matches? focus %) @active))))

   ;; ideally, we will always have an active task, but if we ever do not, reset the 
   ;; flag that will ensure the first task's attentional strategy gets adopted.
    (when (empty? @active) (reset! initial? true))

;;    (display/elem "Active Schemas" (map :basis (seq @active)))
    (reset! buffer
            (when (seq @active)
             ;; cycle through task sets when it's time.
              (cond (and (< (:active-cycles parameters) @cycle-count)
                         (> (count @active) 1)) ;; make sure there's something to cycle
                    (do (reset! cycle-count 0)
                        [(adopt-strategy (peek (pop @active)))
                         (task-wmify (peek @active) (sr-links @active))])

                   ;; this is the first task set added to the active list, so 
                   ;; adopt its strategy
                    (and (= (count @active) 1) @initial?)
                    (do (reset! initial? false)
                        (reset! cycle-count 0)
                        [(adopt-strategy (peek @active))
                         (task-wmify (peek @active) (sr-links @active))])

                    :else
                    [(task-wmify (peek @active) (sr-links @active))]))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method Scheduler [comp ^java.io.Writer w]
  (.write w (format "Scheduler{%s,%d}"
                    (-> comp :active deref peek :handle)
                    (count (-> comp :active deref)))))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->Scheduler (atom nil) p (atom (g/queue)) (atom 0) (atom true))))
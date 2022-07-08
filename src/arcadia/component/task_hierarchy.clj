(ns arcadia.component.task-hierarchy
  "This component instantiates a task hierarchy and manages the currently active
  task, updating it when requested. Task switching takes two cycles. First, a
  focal switch-task activates this component. Second, this component outputs a
  representation of the task that is used to update other components and an
  action request to adopt an attentional strategy.

  Focus Responsive
    * update-task-hierarchy, changes the current hierarchy to a new one
    * switch-task, changes to a new task instance within the current hierarchy

  Default Behavior
  outputs the current task instance in the world \"task-wm\"

  Produces
  task - the working memory version of the task, which includes
    :instance-name, the name of the task instance
    :handle, the name of the task schema
    :initial-responses, rules to fire when the task is first activated
    :stimulus-responses, regular sr-links
    :strategy, attentional strategy
    :completion-condition, a descriptor that when matched indicates that the task is done

  memory-retrieval - a request to retrieve various task schemata from memory
    :lexemes, a sequence of names of task schemata

  adopt-attentional-strategy - a request to switch to the newly adopted task's
    attentional strategy
    :strategy, the attentional strategy function for the focus selector to use
    :handle, the name of the task associated with the strategy"
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.tasks :as t]
            [arcadia.utility.attention-strategy :as att]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required hierarchy "the initial task network to instantiate (required)" nil)

(defn- task-wmify
  "Build an element that outputs the working memory representation of the current task."
  [task source]
  {:name "task"
   :arguments task
   :type "instance"
   :world "task-wm"
   :source source})

(defn- adopt-strategy
  "Build an element that requests a new attentional strategy be adopted. The
  strategy function is built from the specification in the task."
  [task source]
  {:name "adopt-attentional-strategy"
   :arguments {:strategy (att/build-dynamic-strategy (:strategy task))
               :handle (:handle task)}
   :world nil
   :source source
   :type "action"})

(defn- memory-retrieval
  "Build an element that requests the task schemas with the given names."
  [task-names source]
  {:name "memory-retrieval"
   :arguments {:lexemes task-names}
   :type "action"
   :world nil
   :source source})

(defn- switch-task
  "Build the response for a task-switch request."
  [task]
  {:name "switch-task"
   :arguments {:task-name task}})

(defn- edge-link
  "Given an edge in the hierarchy, create a stimulus-response link that will transition
  to the subtask."
  [e]
  (t/sr-link [(:trigger e)] (switch-task (:child e))))

;; child-edges is the seq of edges for the current node [{:parent p :trigger t :child c}]
;; nodes is the {name task-schema-atom} structure of all nodes in the hierarchy
;;   and shouldn't change (except in through atom updates) over recursive calls
;; edges is the result of group-by :parent on the edges of the hierarchy and shouldn't
;;   change over recursive calls
;; up-links = rules from child back to parents (completion condition for parent task)
;;            this is so that when the completion condition for an ancestor task is
;;               met, the current task will return to the ancestor's parent.
;;            we need one of these for each ancestor with a parent, and it's
;;               the same all the way down the task hierarchy.
;; parent = task that is the direct parent of the current task (used to create
;;             the return link for this subtask).
;;          the link created from this parent is added to the up-links for any
;;             recursive calls to child nodes
(defn- expand-network!
  "Add the stimulus-response links implied by the hierarchy into the instantiated tasks."
  [instance-name child-edges nodes edges parent up-links]
  (if child-edges
    (let [task (get nodes instance-name)
          ;; down-links = set of rules from parent to child (activation condition for subtask)
          down-links (map edge-link child-edges)
          ;; up-link = whenever this task is done, go up the hierarchy
          up-link (t/sr-link [(:completion-condition @task)] (switch-task parent))]
      ;; update the task schema for x in nodes to include the down-links and up-links that
      ;; do not lead to nil (a special case for the top task)
      (swap! task update-in [:stimulus-responses] concat (conj up-links up-link) down-links)
      ;; if we don't associate this with the task instance, we'll lose the information in the
      ;; interlingua element.
      (swap! task assoc :instance-name instance-name)
      (doseq [c child-edges]
        (expand-network! (:child c) (get edges (:child c)) nodes edges (:parent c) (conj up-links up-link))))
    ;; no children, so only build the backward links
    (let [task (get nodes instance-name)
          ;; up-link = whenever this task is done, go up the hierarchy
          up-link (t/sr-link [(:completion-condition @task)] (switch-task parent))]
      (swap! task update-in [:stimulus-responses] concat up-links [up-link])
      (swap! task assoc :instance-name instance-name))))

;;; XXX problems still unsolved
;;; completion conditions are either descriptors or predicates. if they are
;;; predicates, then we cannot use them in s-r rules. if they are descriptors,
;;; then they cannot support checking for absence. (see kill-my-uncle task)
;;;
;;; NOTE: complete
;;; breaking the interdependence between rules that point to parent or child
;;; tasks without exposing state in interlingua elements will be...difficult.
;;; however, since responses can be functions, there may be a way to handle this.
;;;
;;; ultimately, i think the hierarchy will need to keep stateful versions of the
;;; tasks around, have the s-r rules make requests for the instantiated, non-stateful
;;; tasks, and have the hierarchy issue a grounded task-switch request in
;;; response to the order from the s-r rules.
;;; basically, the s-r link will request an instantiated task by its name. the
;;; hierarchy will then request a task switch passing along the task stored in
;;; @(get (:tasks component) name). the pattern here is to dereference by name
;;; within a component using an interlingua element to specify the item's name.
;;;
;;; when finished, i should write up this pattern in the documentation so that
;;; others can use it when needed.

(defn- instantiate-hierarchy
  "Instantiate the hierarchy using the task schemata and return a map of task names to
  instantiated task representations."
  [hierarchy schemata]
  ;; associate the node names with their task schema, but we want
  ;; the tasks to be modifiable until the hierarchy is fully
  ;; instantiated, so store them as atoms.
  (let [nodes (reduce-kv #(assoc %1 %2 (atom (get schemata %3))) {} (:nodes hierarchy))
        edges (group-by :parent (:edges hierarchy))
        top (get edges (:top hierarchy))]
    ;; replace the tasks in nodes with correctly instantiated tasks
    (expand-network! (:top hierarchy) top nodes edges nil nil)
    ;; return an immutable map so we don't need to remember an extra deref
    (reduce-kv #(assoc %1 %2 (deref %3)) {} nodes)))

(defrecord TaskHierarchy [buffer tasks hierarchy]
  Component
  (receive-focus
   [component focus content]
   ;; update the hierarchy if requested
   (when (d/element-matches? focus :name "update-task-hierarchy" :type "action" :world nil)
     (reset! (:tasks component) nil)
     (reset! (:hierarchy component) (-> focus :arguments :task-hierarchy)))

   ;; schemata = {"task-name-1" task-schema-1 "task-name-2" task-schema-2 ...}
   (let [schemata (apply hash-map (mapcat #(vector (-> % :arguments :task-name) (-> % :arguments :task))
                                          (d/filter-elements content :name "task-schema")))]
     (cond
       ;; this option occurs when you have requested the tasks mentioned by a
       ;; new hierarchy and they were made available by long term memory.
       (and (seq schemata) (nil? @(:tasks component)))
       (let [inst-hierarchy (instantiate-hierarchy @(:hierarchy component) schemata)
             top-task (get inst-hierarchy (-> component :hierarchy deref :top))]
        ;; automatically enter the top task in the hierarchy
        (reset! (:tasks component) inst-hierarchy)
        (reset! (:buffer component) [(task-wmify top-task component)
                                     (adopt-strategy top-task component)]))

       ;; this option occurs when you just changed hierarchies and need to get
       ;; the tasks that it refers to
       (nil? @(:tasks component))
       (reset! (:buffer component) [(memory-retrieval (-> component :hierarchy deref :nodes vals set)
                                                     component)])

       ;; process task switches if we're not in the process of rebuilding the hierarchy
       ;; need to do this last or else we might get stale task switches instead
       ;; activates the task specified by the name in the task switch
       (d/element-matches? focus :name "switch-task" :type "action" :world nil)
       (when-let [task (get @(:tasks component) (-> focus :arguments :task-name))]
         (reset! (:buffer component)
                 [(task-wmify task component)
                  (adopt-strategy task component)]))

      ;; in the normal case, we just report the current task
      ;; the take will drop the adopt-strategy element if it exists
      :else
      (reset! (:buffer component) (take 1 @(:buffer component))))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method TaskHierarchy [comp ^java.io.Writer w]
  (.write w (format "TaskHierarchy{%s}" (-> comp :hierarchy deref :top))))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->TaskHierarchy (atom nil) (atom nil) (atom (:hierarchy p)))))

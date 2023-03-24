(ns arcadia.component.task-ltm
  "This component stores the schemata for all the tasks available.

  Focus Responsive
    * memory-retrieval

  Produces an element for each recognized task name specified in the :lexemes
  argument of the memory-retrieval element. 

  Default Behavior
  None

  Produces
  task-schema - includes a task schema associated with the name used to retrieve
  the schema. the argument structure is
    :schema, the task schema
    :name, the retrieval cue that prompted the output of this schema

  Task schemas are assumed to be of the form returned by arcadia.utility.tasks/task"
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;; TODO change this component to take a file where tasks are defined instead
;;; of a sequence of tasks so that we can start to consolidate background
;;; knowledge in the form of the content for intentions

(def ^:parameter ^:required tasks "sequence of task definitions (required)" nil)

(defn- task-schema [name task]
  {:name "task-schema"
   :arguments {:task-name name :task task}
   :type "instance"
   :world nil})

(defrecord TaskLTM [buffer tasks]
  Component
  (receive-focus
    [component focus content]
    (if (d/element-matches? focus :name "memory-retrieval" :type "action" :world nil)
      (reset! (:buffer component) (map #(task-schema % (get (:tasks component) %))
                                       (-> focus :arguments :lexemes)))
      (reset! (:buffer component) nil)))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method TaskLTM [comp ^java.io.Writer w]
  (.write w (format "TaskLTM{%s}" (str (count (:tasks comp)) " tasks"))))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->TaskLTM (atom nil) (:tasks p))))

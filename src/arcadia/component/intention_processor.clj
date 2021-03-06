(ns arcadia.component.intention-processor
  ""
  (:require [arcadia.utility [descriptors :as d]
                             [general :as g]]
            [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required default-task "task to switch to if there are no active intentions" nil)
(def ^:parameter priorities "ordered list indicating preferences over intentions (earlier are preferred)" nil)

(defn- switch-task
  "Build the response for a task-switch request."
  [task component]
  {:id (gensym)
   :name "switch-task"
   :arguments {:task-name task}
   :source component
   :type "action"
   :world nil})

(defn- preferred
  [intentions priorities]
  (loop [p priorities]
    (if (seq p)
      (if-let [found (g/find-first #(= (first p) (-> % :arguments :task)) intentions)]
        found
        (recur (rest p)))
      (first intentions))))

(defrecord IntentionProcessor [buffer task-default priorities]
  Component
  (receive-focus
   [component focus content]
   (let [task (d/first-element content :name "task" :type "instance" :world "task-wm")
         preferred-intention (preferred (d/filter-elements content
                                                           :name "activated-intention"
                                                           :world "intention-memory")
                                        (:priorities component))]
     ; (display-element-for :debug-status-display "Preferred" (str (-> preferred-intention :arguments :task)))
     ; (display-element-for :debug-status-display "Current Task" (str (-> task :arguments :instance-name)))

     (reset! (:buffer component) nil)
     (if preferred-intention
       ;; get the highest priority activated intention and switch to its task set.
       ;; only switch if the current task instance the preferred task instance.
       (when (and (-> task :arguments :instance-name) ;; while switching there might not be a current task
                  (not= (-> preferred-intention :arguments :task)
                        (-> task :arguments :instance-name)))
         (reset! (:buffer component) (switch-task (-> preferred-intention :arguments :task) component)))
       ;; if there is no preferred active intention, switch to the default task if you're not already there
       (when (not= task-default (-> task :arguments :instance-name))
        (reset! (:buffer component) (switch-task task-default component))))))

  (deliver-result
   [component]
   #{@(:buffer component)}))

(defmethod print-method IntentionProcessor [comp ^java.io.Writer w]
  (.write w (format "IntentionProcessor{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->IntentionProcessor (atom nil) (:default-task p) (:priorities p))))

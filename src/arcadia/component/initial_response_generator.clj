(ns arcadia.component.initial-response-generator
  "Produces initial responses on initiating a task. This component is based on
   the sr-link-processor, but unlike sr-links, initial-responses are generated
   only once, on beginning a task, and they are always generated.

  Focus Responsive
  Parameters specify what action should receive focus on the cycle before the
  initial responses trigger.

  Default Behavior
  None.

  Produces
   * various response names & actions"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            [arcadia.utility [descriptors :as d]]))

(defrecord InitialResponseGenerator [prior-responses responses buffer]
  Component
  (receive-focus
    [component focus content]
    (when-let [task (d/first-element content :name "task" :type "instance" :world "task-wm")]
     ;; no task-set loaded or a new task-set was just loaded
      (reset! responses (-> task :arguments :initial-responses))
      (if (or (nil? @responses) (not= @prior-responses @responses))
        (do (reset! prior-responses (-> task :arguments :initial-responses))
            ;; some of the initial responses will be automations, so we don't want to
            ;; force "action" types like we do with sr-links.
            (reset! buffer (map :response @responses)))
        (reset! buffer nil))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method InitialResponseGenerator [comp ^java.io.Writer w]
  (.write w (format "InitialResponseGenerator{%d}" (count @(:responses comp)))))

(defn start
  [& {:as args}]
  (let [p (merge-parameters args)]
    (->InitialResponseGenerator (atom nil) (atom nil) (atom nil))))

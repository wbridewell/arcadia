(ns arcadia.component.sr-link-processor
  "Active tasks are associated with stimulus-response links that are activated
  in parallel irrespective of the focus of attention. These links connect
  actions to stimuli. The stimuli can be any sort of element in accessible
  content, but the responses must be executable mental or physical actions
  (e.g., push-button, memorize).

  As long as the stimulus is available in accessible content, the link will
  generate a response. The response may not receive attention, so execution is
  not guaranteed.

  Focus Responsive
  * No

  Default Behavior
  When the test in a stimulus-response link matches an element in accessible
  content, request that the action associated with the response be carried out.

  Produces
   * various response names & action, type
       essentially takes the response associated with the stimulus-response
       link and converts it into an action request. All arguments are carried
       along as-is."
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [descriptors :as d]
             [tasks :as tasks]]))

(defn- action-request [request]
  (assoc request :type "action" :world nil))

(defrecord SRLinkProcessor [buffer]
  Component
  (receive-focus
    [component focus content]
   ;; always grabs the current set of sr-links.
   ;; does not pause processing during a task switch, so stale rules can fire.
    (when-let [task (d/first-element content :name "task" :type "instance" :world "task-wm")]
      (reset! (:buffer component)
              (map #(action-request %)
                   (tasks/collect-responses (-> task :arguments :stimulus-responses) content)))))

  (deliver-result
    [component]
    @buffer))

(defmethod print-method SRLinkProcessor [comp ^java.io.Writer w]
  (.write w (format "SRLinkProcessor{}")))

(defn start [& {:as args}]
  (->SRLinkProcessor (atom nil)))

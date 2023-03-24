(ns arcadia.component.wm-handle-refresher
  "When tasks are adopted, they are treated as goals stored in working memory.
  The currently active tasks are ordered by when they were most recently
  refreshed. This component treats goal refresh as a mental action that is
  triggered by the subvocalization of the name of the corresponding task.

  Focus Responsive
    * subvocalize

  Creates an action request to refresh the working memory elements that
  have a handle (i.e., a name) identical to the subvocalized word.

  Default Behavior
  None

  Produces
   * wm-refresh
       includes an :element argument that contains the working memory element
       to be refreshed."
  (:require [arcadia.component.core :refer [Component]]))

(defn- make-action-request [element]
  {:name "wm-refresh"
   :arguments {:element element}
   :world nil 
   :type "action"})

(defrecord WMHandleRefresher [buffer]
  Component
  (receive-focus
   [component focus content]
   (if (and (= (:name focus) "subvocalize")
            (= (:type focus) "action")
            (nil? (:world focus)))
     ;; grab all the WM elements whose linguistic handles match the subvocalized word.
     (reset! (:buffer component) (filter #(and (= (:world %) "working-memory")
                                               (= (-> % :arguments :handle)
                                                  (-> focus :arguments :lexeme)))
                                         content))
     (reset! (:buffer component) nil)))

  (deliver-result
   [component]
   (map #(make-action-request %)
        @(:buffer component))))

(defmethod print-method WMHandleRefresher [comp ^java.io.Writer w]
  (.write w (format "WMHandleRefresher{}")))

(defn start []
  (->WMHandleRefresher (atom nil)))

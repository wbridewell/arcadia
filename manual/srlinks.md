Stimulus-Response Links
===
One of the most common operations in ARCADIA, as in other cognitive systems, is the conditional operation. Most of the time, these operations are implemented as components of an ARCADIA model. However, components have their drawbacks; they are only interchangeable at the architecture level of ARCADIA, and they are implemented as raw Clojure functions which can't easily be inspected or modified dynamically. For this reason, ARCADIA now has a lightweight conditional operation that's suitable for storage, execution, and modification inside of ARCADIA's accessible content: stimulus-response links (SR-links). Although SR-links get their name from their use in task-switching, they are also currently used in the definitions of relations, and are general enough to be used in other areas of code.

### Usage Note

When using stimulus-response links in task definitions, the response should always be an action request.

## Representation
As the name suggests, SR-links have two major parts: a stimulus and a response. Whenever the stimulus is detected in accessible content, an SR-link is fired, and its response is output back into accessible content.

### Stimulus
A stimulus is a constraint on when the SR-link should fire. In ARCADIA, this is expressed through the presence of some interlingua elements in accessible content. This problem is perfectly suited for ARCADIA's descriptor utilities; look here to find out more about descriptors. An SR-link's `:stimulus` is a sequence of these descriptors interpreted as such: an SR-link will fire if and only if for every descriptor `di` in its `:stimulus`, there exists a matching element `ei` in accessible content. Trivially, an SR-link with an empty `:stimulus` will always fire. Generally, the form of the `:stimulus` will look like this:

```Clojure
[(descriptor ...)
 ...
 (descriptor ...)]
 ```

### Response
SR-links, like components, must create output in the form of interlingua elements. Sometimes the output of an SR-link is fixed; in these cases, the `:response` of the SR-link is simply the interlingua element it should output. If we were constructing an SR-link to push the "A" key whenever some condition holds, the `:response` would look something like this:

```Clojure
{:name "push-button"
 :arguments {:button-id "A"}
 :type "action"
 :world nil}
 ```

For more flexible behavior, an SR-link's `:response` can also be a function of its `:stimulus` and accessible content as a whole. When this is needed, the `:response` is a Clojure function that takes an argument for every stimulus from accessible content, as well as an argument for accessible content as a whole, and then returns an interlingua element to be output by the SR-link. If we needed an SR-link to push a key whose label is stored in the :value argument of the first `:stimulus`, for example, we could use this response function:
```Clojure
(fn [stimulus1 stimulus2 ... stimulusN content]
 {:name "push-button"
  :arguments {:button-id (:value (:arguments stimulus1))}
  :type "action"
  :world nil}
```

SR-Links
To make SR-links, use the function `arcadia.utility.tasks/sr-link`, which takes a stimulus sequence and a response. Here's an SR-link that pushes a button whenever an object-property containing its character is observed:
```Clojure
(sr-link [(descriptor :name "object-property" :property :character)]
 (fn [obj-property content] {:name "push-button" 
                             :arguments {:button-id (-> obj-property :arguments :value)}
                             :type "action"
                             :world nil}))
```

## Initial Responses
When defining tasks, there are special SR-links that can be fired only on task entry. These are called initial responses and are created with `arcadia.utility.tasks/initial-response`. These links do not have a stimulus condition because task-entry serves as the activation criterion. As a result, you only specify the response part of the link. Although SR-links defined in tasks should always produce action requests, initial responses can also produce elements having type `"automation"`. These elements are used to parameterize components for the particular task just entered.

## SR-Link Processing
The library `arcadia.utility.tasks` also includes functionality for firing SR-links. In it, the function responses is used to collect all possible responses from an SR-link given accessible content. It does this by finding all matching elements for each descriptor in the SR-link's `:stimulus`, and then firing the SR-link for each combination of the stimuli, and finally returning all of the non-`nil` responses. The function `collect-responses` fires a sequence of SR-links over content, and likewise returns a sequence of the non-`nil` responses. Normally, however, SR-links belong to a task or relation. For these cases, the component `arcadia.component.sr-link-processor` fires all of the current SR-links every cycle, and should be preferred over firing them elsewhere.

## When to Use SR-links
Consider using SR-links instead of task-specific components when the following three conditions hold.

1. You want to transform information from N interlingua elements into one interlingua element.
1. The mapping operation does not rely on the focus of attention.
1. The mapping operation does not require state from prior cycles.

For instance, suppose that the task involves scanning the environment for multiple objects and then reporting once they are all found. If objects are stored in working memory as they are located, then they will appear in accessible content on each cycle. An SR-link could have descriptions of each object in the stimulus and an action request to report success in the response. This way, once the task specific set of objects is found, an action request will be made available for focus selection on each subsequent cycle until the task is completed or working memory degrades.

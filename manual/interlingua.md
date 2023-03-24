Interlingua Elements
===

ARCADIA's interlingua provides a skeletal structure for the information packets shared by components. Each interlingua element contains six fields that provide benefits for communicating, filtering, sorting, and tracking information as ARCADIA operates within an environment.

| field | content | purpose |
|-|-|-|
| `:name` | a string | The name of an element is analogous to a class name in object-oriented programming or to a relation name in first-order logic. This is the primary sorting and filtering field for elements.. |
| `:arguments` | a map of argument names and values | Most of the information associated with an element will be in the argument map. The names of the arguments are keywords and the values can be any data structure supported by Clojure. Examples include images, matrices, audio streams, functions, strings, and so forth. In some cases, arguments may also include previously constructed interlingua elements. |
| `:type` | a string | An element's type is a second-order field that enables the grouping of items with different names. Common types include "instance," "action," "event," and "relation." |
| `:world` | a string or nil | Although many elements are directly tied to perception or representations of the world as it is, others may be reconstructed from memory or may constitute alternative points of view or counterfactual situations. A value of nil indicates that the element is treated as part of reality, whereas other elements may be associated with memory components like "vstm," "working-memory," or "episodic memory." |
| `:source` | keyword | The value is the identifier of the component that generated the element. The source of an element is primarily used to trace provenance during model execution. This field is only accessible as metadata. |
| `:cycle` | integer | The cycle that an element was produced on. This field is only accessible as metadata.

## Example

The interlingua elements are encoded as Clojure maps. The structure resembles the following. The world in this case is `"vstm"` to indicate that this element is currently stored in visual short-term memory. To access `:source` or `:cycle` use calls to the Clojure function `meta`.

```Clojure
{:name "object"
 :arguments {:color "blue"
             :shape "rectangle"}
 :type "instance"
 :world "vstm"}
```

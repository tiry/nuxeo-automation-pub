## About

This module provides Automation Operations to be able to publish a Document between 2 Nuxeo servers.

The module contains 3 contributions:

 - an Operation to publish to a remote Nuxeo Server: `Remote.Publish`
     - this is the sending / client part
 - an Operation to receive a publication from a Remote Server: `Remote.CreatePublishedDocument`
     - this is the receiving / server part
 - a `remotePub` Schema associated with a `RemotePub` Facet
     - used to store cross link between source and target

## Motivations for these operations

Nuxeo provides "out of the box" a remote publisher feature.

However, this feature was built a long time ago, especially before Nuxeo Automation and the REST API was available. As a result, the remote publisher :

 - uses a custom Marshaling 
 - uses a custom JAX-RS EndPoint

In addition, the scope for the Remote Publisher is more than just send Document between 2 Nuxeo instances since it also provides a complete abstraction on top of the publication tree. This abstraction make the infrastructure complex and closely tied to the way the default Nuxeo DM UI gives access to publishing.

Having 2 simples operations makes it easier to handle the integration with a custom business process.

## Implementation notes 

All the communication relies on Nuxeo default marshaling, however, when the Document is sent between the 2 Nuxeo server it is sent a Zip Export Blob.

Doing so has some advantages:

 - we can send all attributes and blobs in a network efficient manner
 - import is faster

Both the Source Document and the Target Document hold a `RemotePub` that stores the references between the 2 documents.

The "Server endpoint" provided by `Remote.CreatePublishedDocument` will try to do a simple resolution of the path provided to get the target container where the document must be published.

You can provide an additional `containerResolver` parameter that should be the name of a Chain or Operation that will be used to do find or create the container.

The target Operation should :

 - accept a DocumentModel as input: the DocumentModel representing the document to publish 
 - use the `containerPath` context entry to retrieve the value of the `containerResolver` parameter
 - return a DocumentModel
    
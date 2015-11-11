package org.nuxeo.automation.publish;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.dom4j.Element;
import org.nuxeo.common.utils.Path;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.CleanupHandler;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentLocation;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.api.impl.DocumentModelImpl;
import org.nuxeo.ecm.core.api.impl.blob.FileBlob;
import org.nuxeo.ecm.core.io.DocumentPipe;
import org.nuxeo.ecm.core.io.DocumentReader;
import org.nuxeo.ecm.core.io.DocumentTransformer;
import org.nuxeo.ecm.core.io.DocumentTranslationMap;
import org.nuxeo.ecm.core.io.DocumentWriter;
import org.nuxeo.ecm.core.io.ExportConstants;
import org.nuxeo.ecm.core.io.ExportedDocument;
import org.nuxeo.ecm.core.io.impl.DocumentPipeImpl;
import org.nuxeo.ecm.core.io.impl.DocumentTranslationMapImpl;
import org.nuxeo.ecm.core.io.impl.plugins.DocumentModelWriter;
import org.nuxeo.ecm.core.io.impl.plugins.NuxeoArchiveReader;
import org.nuxeo.ecm.platform.web.common.vh.VirtualHostHelper;
import org.nuxeo.runtime.api.Framework;

@Operation(id = CreatePublishedDocument.ID, category = Constants.CAT_DOCUMENT, label = "Publish from remote", description = "")
public class CreatePublishedDocument {

    public static final String ID = "Remote.CreatePublishedDocument";

    @Context
    protected OperationContext ctx;

    @Param(name = "container")
    protected String container;

    @Param(name = "name")
    protected String name;

    @Param(name = "resolver", required = false)
    protected String resolver;

    @OperationMethod
    public DocumentModel publish(Blob source) throws Exception {

        CoreSession session = ctx.getCoreSession();

        DocumentPipe pipe = new DocumentPipeImpl();

        DocumentModel containerDoc = null;
        if (resolver != null && !resolver.isEmpty()) {
            // need to get input document for the resolver !

            // save the blob for being able to read it twice
            final File tmp = File.createTempFile("nuxeo-pub-import-", ".zip");
            source.transferTo(tmp);

            source = new FileBlob(tmp);

            ctx.addCleanupHandler(new CleanupHandler() {
                @Override
                public void cleanup() {
                    tmp.delete();
                }
            });

            DocumentReader reader = new NuxeoArchiveReader(tmp);
            pipe.setReader(reader);
            InMemoryDocumentModelWriter writer = new InMemoryDocumentModelWriter(session, "/");
            pipe.setWriter(writer);

            pipe.addTransformer(new DocumentTransformer() {
                @Override
                public boolean transform(ExportedDocument xDoc) throws IOException {
                    Element root = xDoc.getDocument().getRootElement();
                    Element sys = root.element("system");
                    for (Object f : sys.elements("facet")) {
                        if ("Immutable".equals(((Element) f).getTextTrim())) {
                            ((Element) f).detach();
                        }
                    }
                    return true;
                }
            });

            pipe.run();

            DocumentModel sourceDoc = writer.getDoc();

            AutomationService as = Framework.getService(AutomationService.class);
            OperationContext c = new OperationContext(ctx.getCoreSession(), ctx.getVars());
            c.setInput(sourceDoc);
            c.getVars().put("containerPath", container);
            containerDoc = (DocumentModel) as.run(c, resolver);
        } else {
            containerDoc = resolveContainer(session, container);
        }

        DocumentModel published = null;

        if (containerDoc != null) {

            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();

            DocumentModel previous = null;

            if (session.hasChild(containerDoc.getRef(), name)) {
                previous = session.getChild(containerDoc.getRef(), name);
            }

            if (previous != null) {
                entries = (List<Map<String, Object>>) previous.getPropertyValue("rpub:pubEntries");
                session.checkIn(previous.getRef(), VersioningOption.MINOR, "Version before publish");
            }

            pipe = new DocumentPipeImpl();
            DocumentReader reader = new NuxeoArchiveReader(source.getStream());
            pipe.setReader(reader);
            DocumentWriter writer = new DocumentModelWriter(session, containerDoc.getPathAsString());
            pipe.setWriter(writer);

            final Map<String, Serializable> sourceInfo = new HashMap<String, Serializable>();

            pipe.addTransformer(new DocumentTransformer() {
                @Override
                public boolean transform(ExportedDocument xDoc) throws IOException {
                    sourceInfo.put("sourceUID", xDoc.getId());
                    sourceInfo.put("sourceRepository", xDoc.getSourceLocation().getServerName());

                    Path srcPath = xDoc.getPath();
                    if (!srcPath.lastSegment().equals(name)) {
                        // rename the doc
                        xDoc.setPath(srcPath.removeLastSegments(1).append(name));
                    }

                    Element root = xDoc.getDocument().getRootElement();
                    Element sys = root.element("system");
                    for (Object f : sys.elements("facet")) {
                        if ("Immutable".equals(((Element) f).getTextTrim())) {
                            ((Element) f).detach();
                        }
                    }

                    return true;
                }
            });

            pipe.run();

            published = session.getChild(containerDoc.getRef(), name);

            Map<String, Object> entry = new HashMap<String, Object>();

            if (previous != null) {
                entry.put("operation", "update");
            } else {
                entry.put("operation", "create");
            }
            entry.put("remoteUser", session.getPrincipal().getName());
            entry.put("pubDate", Calendar.getInstance());

            entry.put("targetRepository", session.getRepositoryName());
            entry.put("targetPath", published.getPathAsString());
            entry.put("targetUID", published.getId());
            HttpServletRequest request = (HttpServletRequest) ctx.get("request");
            if (request != null) {
                entry.put("targetURL", VirtualHostHelper.getBaseURL(request));
            }

            entry.putAll(sourceInfo);
            entries.add(entry);

            // save map
            if (!published.hasFacet("RemotePub")) {
                published.addFacet("RemotePub");
            }
            published.setPropertyValue("rpub:pubEntries", (Serializable) entries);
            published = session.saveDocument(published);

        }

        return published;

    }

    protected static class InMemoryDocumentModelWriter extends DocumentModelWriter {

        protected DocumentModel doc = null;

        public InMemoryDocumentModelWriter(CoreSession session, String parentPath) {
            super(session, parentPath);
        }

        @Override
        public DocumentTranslationMap write(ExportedDocument xdoc) throws IOException {

            Path toPath = xdoc.getPath();
            toPath = root.append(toPath);

            Path parentPath = toPath.removeLastSegments(1);
            String name = toPath.lastSegment();

            doc = new DocumentModelImpl(parentPath.toString(), name, xdoc.getType());

            // set lifecycle state at creation
            Element system = xdoc.getDocument().getRootElement().element(ExportConstants.SYSTEM_TAG);
            String lifeCycleState = system.element(ExportConstants.LIFECYCLE_STATE_TAG).getText();
            doc.putContextData("initialLifecycleState", lifeCycleState);

            // loadFacets before schemas so that additional schemas are not skipped
            loadFacetsInfo(doc, xdoc.getDocument());

            // then load schemas data
            loadSchemas(xdoc, doc, xdoc.getDocument());

            DocumentLocation source = xdoc.getSourceLocation();
            return new DocumentTranslationMapImpl(source.getServerName(), doc.getRepositoryName());

        }

        public DocumentModel getDoc() {
            return doc;
        }

    }

    protected DocumentModel resolveContainer(CoreSession session, String container) throws Exception {

        DocumentRef ref;

        if (container.contains("/")) {
            ref = new PathRef(container);
        } else {
            ref = new IdRef(container);
        }

        if (session.exists(ref)) {
            return session.getDocument(ref);
        }

        return null;
    }
}

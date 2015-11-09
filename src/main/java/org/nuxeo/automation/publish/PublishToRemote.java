package org.nuxeo.automation.publish;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.nuxeo.ecm.automation.CleanupHandler;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.client.AutomationClient;
import org.nuxeo.ecm.automation.client.OperationRequest;
import org.nuxeo.ecm.automation.client.Session;
import org.nuxeo.ecm.automation.client.jaxrs.impl.HttpAutomationClient;
import org.nuxeo.ecm.automation.client.model.Document;
import org.nuxeo.ecm.automation.client.model.FileBlob;
import org.nuxeo.ecm.automation.client.model.PropertyList;
import org.nuxeo.ecm.automation.client.model.PropertyMap;
import org.nuxeo.ecm.automation.core.Constants;
import org.nuxeo.ecm.automation.core.annotations.Context;
import org.nuxeo.ecm.automation.core.annotations.Operation;
import org.nuxeo.ecm.automation.core.annotations.OperationMethod;
import org.nuxeo.ecm.automation.core.annotations.Param;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentRef;
import org.nuxeo.ecm.core.api.IdRef;
import org.nuxeo.ecm.core.api.VersioningOption;
import org.nuxeo.ecm.core.io.DocumentPipe;
import org.nuxeo.ecm.core.io.impl.DocumentPipeImpl;
import org.nuxeo.ecm.core.io.impl.plugins.DocumentTreeReader;
import org.nuxeo.ecm.core.io.impl.plugins.NuxeoArchiveWriter;

@Operation(id = PublishToRemote.ID, category = Constants.CAT_DOCUMENT, label = "Publish to remote", description = "")
public class PublishToRemote {

    public static final String ID = "Remote.Publish";

    @Context
    protected OperationContext ctx;

    @Param(name = "remoteServer")
    protected String remoteServer;

    @Param(name = "remoteRepositoryName", required = false)
    protected String remoteRepository;

    @Param(name = "targetContainerPath")
    protected String targetContainerPath;

    @Param(name = "remoteLogin")
    protected String remoteLogin;

    @Param(name = "remotePassword")
    protected String remotePassword;

    @Param(name = "targetName")
    protected String targetName;

    @Param(name = "snapshotSource", required = false)
    protected Boolean snapshotSource;

    @Param(name = "containerResolver", required = false)
    protected String containerResolver;

    @OperationMethod
    public DocumentModel publish(DocumentModel source) throws Exception {

        CoreSession session = ctx.getCoreSession();

        // deal with versions
        DocumentModel liveDoc;
        if (source.isVersion()) {
            liveDoc = session.getDocument(new IdRef(source.getSourceId()));
        } else {
            liveDoc = source;
            if (snapshotSource) {
                DocumentRef vRef = session.checkIn(liveDoc.getRef(), VersioningOption.MINOR, "Version before publish");
                source = session.getDocument(vRef);
            }
        }

        AutomationClient client = new HttpAutomationClient(remoteServer);

        Session remoteSession = client.getSession(remoteLogin, remotePassword);
        remoteSession.setDefaultSchemas("remotePub");

        OperationRequest request = remoteSession.newRequest(CreatePublishedDocument.ID);

        request.setHeader("X-NXRepository", remoteRepository);

        if (targetName == null || targetName.isEmpty()) {
            targetName = liveDoc.getName();
        }

        request.set("container", targetContainerPath);
        request.set("name", targetName);
        if (containerResolver != null) {
            request.set("resolver", containerResolver);
        }

        DocumentPipe exportPipe = new DocumentPipeImpl();

        exportPipe.setReader(new DocumentTreeReader(session, source));

        final File tmp = File.createTempFile("nuxeo-pub-export-", ".zip");
        NuxeoArchiveWriter writer = new NuxeoArchiveWriter(tmp);
        exportPipe.setWriter(writer);
        ctx.addCleanupHandler(new CleanupHandler() {
            @Override
            public void cleanup() {
                tmp.delete();
            }
        });

        exportPipe.run();
        writer.close();

        request.setInput(new FileBlob(tmp));

        Document publishedDoc = (Document) request.execute();

        PropertyList rpEntries = publishedDoc.getProperties().getList("rpub:pubEntries");

        if (!liveDoc.hasFacet("RemotePub")) {
            liveDoc.addFacet("RemotePub");
        }

        List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();

        for (int i = 0; i < rpEntries.size(); i++) {
            PropertyMap rpEntry = rpEntries.getMap(i);
            entries.add(rpEntry.map());
        }

        liveDoc.setPropertyValue("rpub:pubEntries", (Serializable) entries);

        return liveDoc;
    }

}

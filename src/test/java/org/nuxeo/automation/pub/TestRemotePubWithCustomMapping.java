package org.nuxeo.automation.pub;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.nuxeo.automation.publish.PublishToRemote;
import org.nuxeo.ecm.automation.AutomationService;
import org.nuxeo.ecm.automation.OperationContext;
import org.nuxeo.ecm.automation.test.EmbeddedAutomationServerFeature;
import org.nuxeo.ecm.core.api.Blob;
import org.nuxeo.ecm.core.api.CoreSession;
import org.nuxeo.ecm.core.api.DocumentModel;
import org.nuxeo.ecm.core.api.DocumentModelList;
import org.nuxeo.ecm.core.api.PathRef;
import org.nuxeo.ecm.core.api.impl.blob.StringBlob;
import org.nuxeo.ecm.core.test.TransactionalFeature;
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.Jetty;
import org.nuxeo.runtime.test.runner.LocalDeploy;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;

@Jetty(port = 18080)
@RunWith(FeaturesRunner.class)
@Deploy({ "org.nuxeo.ecm.automation.scripting", "org.nuxeo.automation.publish" })
@LocalDeploy("org.nuxeo.automation.publish:automation-scripting-contrib.xml")
@Features({ TransactionalFeature.class, EmbeddedAutomationServerFeature.class })
public class TestRemotePubWithCustomMapping {

    @Inject
    CoreSession session;

    @Inject
    AutomationService as;

    DocumentModel sourceFolder;

    DocumentModel sourceDoc1;

    DocumentModel sourceDoc2;

    DocumentModel destFolder;

    protected void dump(StringBuffer sb, DocumentModelList alldocs) {
        for (DocumentModel doc : alldocs) {
            sb.append(doc.getId());
            sb.append(" - ");
            sb.append(doc.getPathAsString());
            sb.append(" - ");
            sb.append(doc.getType());
            sb.append(" - ");
            sb.append(doc.getTitle());
            sb.append(" - ");
            sb.append(doc.isVersion() ? "version" : "doc");
            sb.append(" - ");
            sb.append(doc.getVersionLabel());
            sb.append("\n");
        }
    }

    protected void dump() {
        DocumentModelList docs = session.query("select * from Document order by ecm:path");
        StringBuffer sb = new StringBuffer();
        dump(sb, docs);
        System.out.println(sb.toString());
    }

    // @Before
    public void initTree() throws Exception {

        sourceFolder = session.createDocumentModel("/", "sourceFolder", "Folder");
        sourceFolder = session.createDocument(sourceFolder);

        sourceDoc1 = session.createDocumentModel("/sourceFolder/", "source", "File");
        sourceDoc1.setPropertyValue("dc:title", "Test Document");
        sourceDoc1.setPropertyValue("dc:source", "A");
        Blob blob = new StringBlob("FakeContent");
        blob.setFilename("fake.txt");
        blob.setMimeType("text/plain");
        sourceDoc1.setPropertyValue("file:content", (Serializable) blob);
        sourceDoc1 = session.createDocument(sourceDoc1);

        sourceDoc2= session.createDocumentModel("/sourceFolder/", "source", "File");
        sourceDoc2.setPropertyValue("dc:title", "Test Document");
        sourceDoc2.setPropertyValue("dc:source", "B");
        blob = new StringBlob("FakeContent");
        blob.setFilename("fake.txt");
        blob.setMimeType("text/plain");
        sourceDoc2.setPropertyValue("file:content", (Serializable) blob);
        sourceDoc2 = session.createDocument(sourceDoc2);



        destFolder = session.createDocumentModel("/", "destFolder", "Folder");
        destFolder.setPropertyValue("dc:source", "A");
        destFolder = session.createDocument(destFolder);
        session.save();

        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

    }

    @Test
    public void shouldPublishViaHttp() throws Exception {

        initTree();

        OperationContext oc = new OperationContext(session);
        oc.setInput(sourceDoc1);

        Map<String, Object> params = new HashMap<String, Object>();

        params.put("remoteServer", "http://127.0.0.1:18080/automation");
        params.put("remoteRepositoryName", session.getRepositoryName());
        params.put("targetContainerPath", "/destFolder");
        params.put("remoteLogin", "Administrator");
        params.put("remotePassword", "Administrator");
        params.put("targetName", "pubDoc");
        params.put("snapshotSource", true);
        params.put("containerResolver", "Scripting.GetOrCreateContainer");


        // run initial publication
        DocumentModel localDoc = (DocumentModel) as.run(oc, PublishToRemote.ID, params);
        Assert.assertNotNull(localDoc);

        DocumentModel pubDoc1 = session.getDocument(new PathRef("/destFolder/pubDoc"));
        Assert.assertNotNull(pubDoc1);


        oc.setInput(sourceDoc2);
        DocumentModel localDoc2 = (DocumentModel) as.run(oc, PublishToRemote.ID, params);
        Assert.assertNotNull(localDoc2);

        DocumentModel pubDoc2 = session.getDocument(new PathRef("/B/pubDoc"));
        Assert.assertNotNull(pubDoc2);



    }
}

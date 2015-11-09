package org.nuxeo.automation.pub;

import java.io.Serializable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Assert;
import org.junit.Before;
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
import org.nuxeo.runtime.test.runner.Deploy;
import org.nuxeo.runtime.test.runner.Features;
import org.nuxeo.runtime.test.runner.FeaturesRunner;
import org.nuxeo.runtime.test.runner.Jetty;
import org.nuxeo.runtime.transaction.TransactionHelper;

import com.google.inject.Inject;

@Jetty(port = 18080)
@RunWith(FeaturesRunner.class)
@Deploy({"org.nuxeo.automation.publish"})
@Features({ EmbeddedAutomationServerFeature.class })
public class TestRemotePub {

    @Inject
    CoreSession session;

    @Inject
    AutomationService as;

    DocumentModel sourceFolder;
    DocumentModel sourceDoc;
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
            sb.append(doc.isVersion()?"version":"doc");
            sb.append(" - ");
            sb.append(doc.getVersionLabel());
            sb.append("\n");
        }
    }

    protected void dump() {
        DocumentModelList docs = session.query("select * from Document order by ecm:path");
        StringBuffer sb = new StringBuffer();
        dump(sb,docs);
        System.out.println(sb.toString());
    }

    @Before
    public void initTree() throws Exception {

        sourceFolder = session.createDocumentModel("/", "sourceFolder", "Folder");
        sourceFolder = session.createDocument(sourceFolder);

        sourceDoc = session.createDocumentModel("/sourceFolder/", "source", "File");
        sourceDoc.setPropertyValue("dc:title", "Test Document");
        Blob blob = new StringBlob("FakeContent");
        blob.setFilename("fake.txt");
        blob.setMimeType("text/plain");
        sourceDoc.setPropertyValue("file:content", (Serializable) blob);
        sourceDoc = session.createDocument(sourceDoc);

        destFolder = session.createDocumentModel("/", "destFolder", "Folder");
        destFolder = session.createDocument(destFolder);
        session.save();

        TransactionHelper.commitOrRollbackTransaction();
        TransactionHelper.startTransaction();

    }

    @Test
    public void shouldPublishViaHttp() throws Exception {

        OperationContext oc = new OperationContext(session);
        oc.setInput(sourceDoc);

        Map<String, Object> params = new HashMap<String, Object>();

        params.put("remoteServer", "http://127.0.0.1:18080/automation");
        params.put("remoteRepositoryName", session.getRepositoryName());
        params.put("targetContainerPath", "/destFolder");
        params.put("remoteLogin", "Administrator");
        params.put("remotePassword", "Administrator");
        params.put("targetName", "pubDoc");
        params.put("snapshotSource", true);


        // run initial publication
        DocumentModel localDoc = (DocumentModel) as.run(oc, PublishToRemote.ID, params);
        Assert.assertNotNull(localDoc);

        DocumentModel pubDoc = session.getDocument(new PathRef("/destFolder/pubDoc"));
        Assert.assertNotNull(pubDoc);


        dump();

        // get publication log
        List<Map<String, Serializable>> props = (List<Map<String, Serializable>>) localDoc.getPropertyValue("rpub:pubEntries");
        Assert.assertNotNull(props);

        // should only have one entry
        Assert.assertEquals(1, props.size());

        // check properties regarding target
        Assert.assertEquals(pubDoc.getId(), props.get(0).get("targetUID"));
        Assert.assertEquals(pubDoc.getPathAsString(), props.get(0).get("targetPath"));
        Assert.assertEquals(pubDoc.getRepositoryName(), props.get(0).get("targetRepository"));

        // check meta
        Assert.assertEquals("create", props.get(0).get("operation"));
        Assert.assertNotNull(props.get(0).get("pubDate"));

        // check that source was versioned
        List<DocumentModel> sourceVersions = session.getVersions(sourceDoc.getRef());
        Assert.assertEquals(1, sourceVersions.size());

        // check properties regarding source
        String sourceVersionId = sourceVersions.get(0).getId();
        Assert.assertEquals(sourceVersionId, props.get(0).get("sourceUID"));
        Assert.assertEquals(sourceDoc.getRepositoryName(), props.get(0).get("sourceRepository"));

        // check published document
        Assert.assertEquals("Test Document", pubDoc.getTitle());
        Assert.assertEquals("fake.txt", ((Blob)pubDoc.getPropertyValue("file:content")).getFilename());
        Assert.assertEquals("FakeContent", ((Blob)pubDoc.getPropertyValue("file:content")).getString());


        sourceDoc.setPropertyValue("dc:title", "Test Document Modified");
        sourceDoc = session.saveDocument(sourceDoc);
        oc.setInput(sourceDoc);

        // do a second publication
        localDoc = (DocumentModel) as.run(oc, PublishToRemote.ID, params);
        Assert.assertNotNull(localDoc);

        pubDoc = session.getDocument(new PathRef("/destFolder/pubDoc"));
        Assert.assertNotNull(pubDoc);

        // check published document
        Assert.assertEquals("Test Document Modified", pubDoc.getTitle());

        // check that source was versioned
        sourceVersions = session.getVersions(sourceDoc.getRef());
        Assert.assertEquals(2, sourceVersions.size());

        List<DocumentModel> targetVersions = session.getVersions(pubDoc.getRef());
        Assert.assertEquals(1, targetVersions.size());


    }
}

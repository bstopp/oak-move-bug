package com.example.test.servlet;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;

import org.apache.jackrabbit.JcrConstants;
import org.apache.jackrabbit.commons.JcrUtils;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.apache.sling.api.servlets.ServletResolverConstants.*;

@Component(
    service = { Servlet.class },
    property = {
        SLING_SERVLET_PATHS + "=/bin/bug/test",
        SLING_SERVLET_METHODS + "=GET"
    }
)
public class TestServlet extends SlingSafeMethodsServlet {

  private final Logger logger = LoggerFactory.getLogger(TestServlet.class);
  
  @Reference
  private ResourceResolverFactory resourceResolverFactory;

  private void setup() throws LoginException, RepositoryException {

    ResourceResolver rr = null;
    try {
      rr = login();
      Session session = rr.adaptTo(Session.class);
      // Create the node tree we'll be updating/moving
      JcrUtils.getOrCreateByPath("/var/oak-bug/parent/child", JcrConstants.NT_UNSTRUCTURED, JcrConstants.NT_UNSTRUCTURED, session, false);
      session.save();
    } catch (RepositoryException e) {
      rr.revert();
      throw e;
    } finally {
      if (rr != null) {
        rr.close();
      }
    }
  }

  private void moveParent() throws LoginException, RepositoryException {
    ResourceResolver rr = null;
    try {
      rr = login();
      Session session = rr.adaptTo(Session.class);
      // Create the new node to move the parent tree onto
      JcrUtils.getOrCreateByPath("/var/oak-bug/test", JcrConstants.NT_UNSTRUCTURED, JcrConstants.NT_UNSTRUCTURED, session, false);
      // Move the parent tree - this operation in this session, i believe, eventually causes the error later.
      session.move("/var/oak-bug/parent", "/var/oak-bug/test/parent");
      session.save();
    } catch (RepositoryException e) {
      rr.revert();
      throw e;
    } finally {
      if (rr != null) {
        rr.close();
      }
    }
  }

  private void generateNPE() throws LoginException, RepositoryException {

    ResourceResolver rr = null;
    // Generate NPE
    try {
      rr = login();
      Session session = rr.adaptTo(Session.class);

      // We rename the parent to a new node, as we're going to replace it with new content that needs to be under the original name.
      session.move("/var/oak-bug/test/parent", "/var/oak-bug/test/tmp-1234");

      // Create the new node using the original name "parent"
      Node parent = JcrUtils.getOrCreateByPath("/var/oak-bug/test/parent", JcrConstants.NT_UNSTRUCTURED, JcrConstants.NT_UNSTRUCTURED, session, false);

      // We need to preserve the original parent's children, so grab the child from the previous parent that was renamed,
      Node child = session.getNode("/var/oak-bug/test/tmp-1234/child");

      // What we really want to do is copy the child, but creating a new node with the same name is sufficient.
      // In the real world, this would have content on it, so all the properties and children would be copied - for testing it doesn't matter.
      // JcrUtil.copy(child, parent, "child", false);
      parent.addNode(child.getName(), child.getPrimaryNodeType().getName());

      assert session.hasPendingChanges(); // None of these changes have been persisted yet. This is to verify that no auto-saves have occurred.

      // Now, we need to actually process the child, so we need to move it so a new node can be created in its place, with the name "child".
      session.move("/var/oak-bug/test/parent/child", "/var/oak-bug/test/parent/tmp-4321"); // NPE On this Call.

      session.save();
    } catch (RepositoryException e) {
      rr.revert();
      throw e;
    } finally {
      if (rr != null) {
        rr.close();
      }
    }
  }

  private void cleanup() throws LoginException, RepositoryException {
    ResourceResolver rr = null;
    // Here we're just cleaning up the nodes that may have been created before the NPE occurred.
    try {
      rr = login();
      Session session = rr.adaptTo(Session.class);
      if (session.nodeExists("/var/oak-bug/test")) {
        session.getNode("/var/oak-bug/test").remove();
      }
      if (session.nodeExists("/var/oak-bug/parent")) {
        session.getNode("/var/oak-bug/parent").remove();
      }
      session.save();
    } catch (RepositoryException e) {
      rr.revert();
      throw e;
    } finally {
      if (rr != null) {
        rr.close();
      }
    }
  }

  @Override
  protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) {

    NullPointerException npe = null;
    try {
      setup();
      // The parent move must be done in a separate Session first, to cause the NPE in the child move in the second Session.
      moveParent();
      try {
        generateNPE();
      } catch (NullPointerException ex) {
        npe = ex;
      }
      // Clean up
      cleanup();
    } catch (LoginException ex) {
      logger.error("Unable to log in using service user to perform conversion", ex);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    } catch (RepositoryException ex) {
      logger.error("Error during test.", ex);
      response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
    }
    if (npe != null) {
      throw npe;
    }
    response.setStatus(HttpServletResponse.SC_OK);
  }

  private ResourceResolver login() throws LoginException {
    return resourceResolverFactory.getAdministrativeResourceResolver(null);
  }
}

package com.appirio.support;

import java.io.IOException;
import java.util.List;

import javax.jdo.JDOHelper;
import javax.jdo.PersistenceManager;
import javax.jdo.PersistenceManagerFactory;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.appirio.support.model.SupportAgent;

@SuppressWarnings("serial")
public class CleanupServlet extends HttpServlet {
  
  private static final PersistenceManagerFactory PMF1 = JDOHelper
    .getPersistenceManagerFactory("transactions-optional");
  
  public void doGet(HttpServletRequest req, HttpServletResponse res)
  throws IOException {
    
    // reset all of the agents' status
    toggleAgentStatus("jdouglas@appirio.com","Available");
    toggleAgentStatus("jeff@appirio.com","Available");
    res.getOutputStream().println("Cleanup done.");
    
  }
  
  @SuppressWarnings("unchecked")
  private void toggleAgentStatus(String agentEmail, String status) {
    
    PersistenceManager pm = PMF1.getPersistenceManager();

    try {

      String query = "select from " + SupportAgent.class.getName()
          + " where email == '"+agentEmail+"'";
      List<SupportAgent> records = (List<SupportAgent>) pm
          .newQuery(query).execute();

      records.get(0).setStatus(status);

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }

  }

}

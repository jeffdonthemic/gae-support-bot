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
import com.appirio.support.model.SupportQuestion;
import com.appirio.support.model.SupportReply;
import com.google.appengine.api.xmpp.JID;
import com.google.appengine.api.xmpp.Message;
import com.google.appengine.api.xmpp.MessageBuilder;
import com.google.appengine.api.xmpp.SendResponse;
import com.google.appengine.api.xmpp.XMPPService;
import com.google.appengine.api.xmpp.XMPPServiceFactory;

@SuppressWarnings("serial")
public class XMPPInboundServlet extends HttpServlet {
  
  private static final PersistenceManagerFactory PMF = JDOHelper
    .getPersistenceManagerFactory("transactions-optional");
  XMPPService xmpp = null;
  
  public void doPost(HttpServletRequest req, HttpServletResponse res)
      throws IOException {
    
    xmpp = XMPPServiceFactory.getXMPPService();
    Message message = xmpp.parseMessage(req);
            
    switch (messageType(message)) {
      case 1: 
        // from user
        Long questionId = processQuestion(message); 
        // set the user's latest question to the session
        // TODO -- send an error message if question id is 0
        if (questionId != 0) 
          req.getSession().setAttribute(message.getFromJid().getId(), questionId);
        break;
      case 2: 
        // from agent
        processAnswer(message); 
        break;
      case 3: 
        // from user
        processThankYou(message, (Long)req.getSession().getAttribute(message.getFromJid().getId())); 
        break;
      case 4: 
        // from user OR agent
        processReply(message, (Long)req.getSession().getAttribute(message.getFromJid().getId())); 
        break;
      case 5: 
        // from user
        processRating(message, (Long)req.getSession().getAttribute(message.getFromJid().getId())); 
        break;
      case 6: 
        returnHelp(message); 
        break;
      case 7: 
        break;
      case 8: 
        returnStatus(message); 
        break;
      default: 
        System.out.println("XMPP processed nothing."); break;
    }

  }
  
  private Long processQuestion(Message message) {
    
    Long questionId = 0L;
    String userEmail = message.getFromJid().getId();
    String body = message.getBody();
    String agentEmail = findAvailableAgent(userEmail);
    
    // persist the new question to bigtable
    SupportQuestion q = new SupportQuestion();
    q.setQuestion(body.substring(9, body.length()));
    q.setUser(userEmail);
    q.setStatus("Unanswered");
    if (agentEmail != null)
      q.setAgent(agentEmail);
    
    PersistenceManager pm = PMF.getPersistenceManager();
    try {
      pm.makePersistent(q);
      questionId = q.getId();
      System.out.println("Successfully saved question");
    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
    if (agentEmail == null) {
      sendMessage(userEmail,"Please hold, while we find an available helpdesk agent.");
    } else {
      toggleAgentStatus(agentEmail, "Not Available");
      sendMessage(agentEmail, userEmail + " is in need of support, perhaps you can answer the question: " + body.substring(9, body.length()));
    }
    
    return questionId;
    
  }

  private void processAnswer(Message message) {
    
    // get the id of the question the agent is currently work on based upon the user's email
    Long questionId = getAgentsCurrentQuestionId(message.getFromJid().getId());
   
    String body = message.getBody();
    PersistenceManager pm = PMF.getPersistenceManager();
    
    try {
      
      SupportQuestion question = pm.getObjectById(SupportQuestion.class, questionId);
      
      // send the answer to the user
      sendMessage(question.getUser(),body.substring(8, body.length()));
      // update the answer
      question.setAnswer(body.substring(8, body.length()));
      System.out.println("Successfully updated answer");

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
  }
  
  private void processThankYou(Message message, Long questionId) {

    PersistenceManager pm = PMF.getPersistenceManager();

    try {
      
      SupportQuestion q = pm.getObjectById(SupportQuestion.class, questionId);
      q.setStatus("Closed");
      
      // reset the agent's status
      toggleAgentStatus(q.getAgent(), "Available");
      
      // send the follow up request for rating
      sendMessage(q.getUser(), "How helpful did our support agent respond to your question? Please, rate between 1-5; 1 as unhelpful, 5 as very helpful");
            
      System.out.println("Successfully updated question from thank you.");

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
  }
  
  private void processReply(Message message, Long questionId) {
    
    String body = message.getBody();
    
    // from the agent
    if (questionId == null)
      questionId = getAgentsCurrentQuestionId(message.getFromJid().getId());
    
    // persist the new reply to bigtable
    SupportReply sr = new SupportReply();
    sr.setFrom(message.getFromJid().getId());
    sr.setQuestionId(questionId);
    sr.setReply(body.substring(7, body.length()));

    PersistenceManager pm = PMF.getPersistenceManager();
    try {
      pm.makePersistent(sr);
      System.out.println("Successfully saved reply");
    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
  }
  
  private void processRating(Message message, Long questionId) {
    
    String body = message.getBody().toString();
    PersistenceManager pm = PMF.getPersistenceManager();

    try {
      
      SupportQuestion q = pm.getObjectById(SupportQuestion.class, questionId);
      q.setRating(Integer.parseInt(body.substring(body.length()-1, body.length())));
      System.out.println("Successfully updated rating.");

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
  }
  
  @SuppressWarnings("unchecked")
  private Long getAgentsCurrentQuestionId(String agentEmail) {
    
    Long questionId = 0L;
    PersistenceManager pm = PMF.getPersistenceManager();
    
    try {

      String query = "select from " + SupportQuestion.class.getName()
        + " where agent == '" + agentEmail + "' && status == 'Unanswered' order by id desc";
      List<SupportQuestion> records = (List<SupportQuestion>) pm
          .newQuery(query).execute();

      questionId = (Long)records.get(0).getId();
      System.out.println("The agent's current question id: " + questionId);

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
    return questionId;
    
  }
  
  private void sendMessage(String email, String message) {
    
    JID jid = new JID(email);
    boolean messageSent = false;
    
    Message msg = new MessageBuilder()
        .withRecipientJids(jid)
        .withBody(message)
        .build();
        
    if (xmpp.getPresence(jid).isAvailable()) {
        SendResponse status = xmpp.sendMessage(msg);
        messageSent = (status.getStatusMap().get(jid) == SendResponse.Status.SUCCESS);
    }
    
    if (!messageSent)
        System.out.println("Error sending message");
    
  }
  
  private void returnHelp(Message message) {
    sendMessage(message.getFromJid().getId(), "/support -- ask a new support question\n/reply -- reply to support agent\n" +
      "/thankyou -- close out a support question\n/rate -- rate the effectiveness of the support agent");
  }
  
  @SuppressWarnings("unchecked")
  private void returnStatus(Message message) {
    
    String agentEmail = message.getFromJid().getId();
    PersistenceManager pm = PMF.getPersistenceManager();
    int answered = 0;
    int ratingsTotal = 0;

    try {

      String query = "select from " + SupportQuestion.class.getName()
        + " where agent == '" + agentEmail + "' && status == 'Closed'";
      List<SupportQuestion> records = (List<SupportQuestion>) pm
        .newQuery(query).execute();

      answered = records.size();
      
      for (SupportQuestion sq : records)
        ratingsTotal = ratingsTotal + sq.getRating();
      

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
    sendMessage(message.getFromJid().getId(), "Number of questions answered: " + answered + ".\nAverage rating: " + ((answered == 0) ? 0 : ratingsTotal / answered) );
  }
  
  @SuppressWarnings("unchecked")
  private void toggleAgentStatus(String agentEmail, String status) {
    
    PersistenceManager pm = PMF.getPersistenceManager();

    try {

      String query = "select from " + SupportAgent.class.getName()
          + " where email == '"+agentEmail+"'";
      List<SupportAgent> records = (List<SupportAgent>) pm
          .newQuery(query).execute();

      records.get(0).setStatus(status);
      System.out.println("Successfully updated agent's status to: " + status);

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }

  }
  
  @SuppressWarnings("unchecked")
  private String findAvailableAgent(String userEmail) {
    
    PersistenceManager pm = PMF.getPersistenceManager();
    String agentEmail = null;

    try {
      
      // search for an agent with a question from an existing user
      String query = "select from " + SupportQuestion.class.getName()
        + " where user == '" + userEmail + "' && status == 'Unanswered'";
      List<SupportQuestion> questions = (List<SupportQuestion>) pm
        .newQuery(query).execute();

      if (questions.size() == 0) {
      
        query = "select from " + SupportAgent.class.getName()
            + " where status == 'Available'";
        List<SupportAgent> agents = (List<SupportAgent>) pm
            .newQuery(query).execute();
  
        agentEmail = agents.get(0).getEmail();
      
      } else {
        agentEmail = questions.get(0).getAgent();
      }

    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
    return agentEmail;
  }
  
  private void createAgent(String name, String email) {
        
    // persist the new question to bigtable
    SupportAgent a = new SupportAgent();
    a.setName(name);
    a.setEmail(email);
    a.setStatus("Available");
    PersistenceManager pm = PMF.getPersistenceManager();

    try {
      pm.makePersistent(a);
      System.out.println("Successfully saved agent");
    } catch (Exception e) {
      System.out.println("pmf exception=" + e.getMessage());
    } finally {
      pm.close();
    }
    
  }
  
  private int messageType(Message message) {
    
    String body = message.getBody();
    
    if ((body.indexOf("/support") != -1) || (body.indexOf("/Support") != -1)) {
      return 1;
    } else if ((body.indexOf("/answer") != -1) || (body.indexOf("/Answer") != -1)) {
      return 2;
    } else if ((body.indexOf("/thankyou") != -1) || (body.indexOf("/Thankyou") != -1)) {
      return 3;
    } else if ((body.indexOf("/reply") != -1) || (body.indexOf("/Reply") != -1)) {
      return 4;
    } else if ((body.indexOf("/rate") != -1) || (body.indexOf("/Rate") != -1)) {
      return 5;
    } else if ((body.indexOf("/help") != -1) || (body.indexOf("/Help") != -1)) {
      return 6;
    } else if ((body.indexOf("/askme") != -1) || (body.indexOf("/Askme") != -1)) {
      return 7;
    } else if ((body.indexOf("/status") != -1) || (body.indexOf("/Status") != -1)) {
      return 8;
    } else {
      return 0;
    }
    
  }
  
}
